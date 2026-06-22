package com.osrsflipfinder;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.Collections;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Flip Finder Sync",
	description = "Syncs your Grand Exchange trades to OSRS Flip Finder",
	tags = {"grand", "exchange", "flipping", "flip", "finder", "trade", "journal", "profit", "merch"}
)
public class FlipFinderSyncPlugin extends Plugin
{
	private static final int SLOTS = 8;

	@Inject
	private Client client;

	@Inject
	private FlipFinderSyncConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private FlipFinderApiClient api;

	@Inject
	private ClientToolbar clientToolbar;

	// Per-slot cumulative state, so each event yields only the newly-filled delta.
	private final long[] lastQtySold = new long[SLOTS];
	private final long[] lastSpent = new long[SLOTS];
	// Per-slot offerKey we last reported a BUY for, so a freshly-placed offer is
	// reported once (as "buying 0/target") without re-sending on every tick.
	private final String[] lastBuyOfferKey = new String[SLOTS];

	private int sessionCount;

	private FlipFinderSyncPanel panel;
	private NavigationButton navButton;

	@Provides
	FlipFinderSyncConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlipFinderSyncConfig.class);
	}

	@Override
	protected void startUp()
	{
		resetBaselines();
		sessionCount = 0;
		panel = new FlipFinderSyncPanel(this::testConnection);
		navButton = NavigationButton.builder()
			.tooltip("Flip Finder Sync")
			.icon(icon())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		log.debug("Flip Finder Sync started");
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			// New account / world hop — drop baselines. Cumulative buy upserts and
			// deduped sell ids reconcile the current offer state after re-login.
			resetBaselines();
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		final int slot = event.getSlot();
		final GrandExchangeOffer offer = event.getOffer();
		if (slot < 0 || slot >= SLOTS || offer == null)
		{
			return;
		}

		final GrandExchangeOfferState state = offer.getState();

		// Mirror RuneLite's own GE plugin: ignore the EMPTY burst fired on logout.
		if (state == GrandExchangeOfferState.EMPTY
			&& client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		final long qtySold = offer.getQuantitySold();
		final long spent = offer.getSpent();
		final long prevQty = lastQtySold[slot];
		final long prevSpent = lastSpent[slot];

		// Advance the baseline to the current cumulative state.
		lastQtySold[slot] = qtySold;
		lastSpent[slot] = spent;

		if (state == GrandExchangeOfferState.EMPTY)
		{
			// Offer collected / cleared — reset so the next offer in this slot
			// starts from zero.
			lastQtySold[slot] = 0;
			lastSpent[slot] = 0;
			lastBuyOfferKey[slot] = null;
			return;
		}

		// A buy cancelled before any unit filled leaves a placeholder
		// "buying 0/target" position server-side — signal its removal. A
		// partially-filled buy that's cancelled keeps its bought units, so only
		// act when nothing was bought.
		if (state == GrandExchangeOfferState.CANCELLED_BUY && qtySold == 0)
		{
			lastBuyOfferKey[slot] = null;
			if (!config.enableSync())
			{
				return;
			}
			final int cancelItemId = offer.getItemId();
			final long accountHash = client.getAccountHash();
			final long buyPrice = offer.getPrice();
			final int target = offer.getTotalQuantity();
			final String offerKey =
				accountHash + ":" + slot + ":" + cancelItemId + ":" + buyPrice + ":" + target;
			final GeSyncTx cancel = GeSyncTx.buyCancel(
				offerKey, cancelItemId, itemName(cancelItemId), System.currentTimeMillis());
			api.submit(config.baseUrl(), config.apiKey(), Collections.singletonList(cancel),
				(ok, message) ->
				{
					if (panel != null && !ok)
					{
						panel.setStatus("Sync failed: " + message, ColorScheme.PROGRESS_ERROR_COLOR);
					}
				});
			return;
		}

		final long qtyDelta = qtySold - prevQty;

		if (!config.enableSync())
		{
			return;
		}

		final boolean sell = isSell(state);
		final int itemId = offer.getItemId();
		final long accountHash = client.getAccountHash();
		final long txAt = System.currentTimeMillis();

		final GeSyncTx tx;
		final String summary;
		if (sell)
		{
			if (qtyDelta <= 0)
			{
				return; // sells are reported only when units actually fill
			}
			// Sells are reported as deltas and FIFO-blended into open positions
			// server-side. Deterministic id (cumulative sold) → re-observing the
			// same fill after a relog re-derives the id and the server dedupes it.
			final String itemName = itemName(itemId);
			final long spentDelta = Math.max(0, spent - prevSpent);
			final long avgPrice = spentDelta / qtyDelta;
			final String clientTxId =
				accountHash + ":" + slot + ":sell:" + itemId + ":" + qtySold;
			tx = GeSyncTx.sell(clientTxId, itemId, itemName, (int) qtyDelta, avgPrice, txAt);
			summary = "sold " + itemName + " ×" + qtyDelta;
		}
		else
		{
			// Buys are reported cumulatively and accumulated server-side into one
			// growing position. We send the offer's LISTED price (not the average
			// paid) so it matches the price a user types into a manual journal
			// entry, letting the server merge into it instead of duplicating.
			// offerKey is stable across relogs (offer params don't change once
			// placed), so re-sends reconcile rather than double-count.
			final long buyPrice = offer.getPrice();
			final int target = offer.getTotalQuantity();
			final String offerKey =
				accountHash + ":" + slot + ":" + itemId + ":" + buyPrice + ":" + target;

			// Report the moment the offer is placed — so it shows as "buying
			// 0/target" before anything fills — and again on every new fill. Skip
			// redundant price-only events for an offer we've already reported.
			final boolean newOffer = !offerKey.equals(lastBuyOfferKey[slot]);
			if (qtyDelta <= 0 && !newOffer)
			{
				return;
			}
			lastBuyOfferKey[slot] = offerKey;

			final String itemName = itemName(itemId);
			tx = GeSyncTx.buy(offerKey, itemId, itemName, (int) qtySold, target, buyPrice, txAt);
			summary = "bought " + itemName + " " + qtySold + "/" + target;
		}

		api.submit(config.baseUrl(), config.apiKey(), Collections.singletonList(tx),
			(ok, message) ->
			{
				if (panel == null)
				{
					return;
				}
				if (ok)
				{
					sessionCount++;
					panel.setSessionCount(sessionCount);
					panel.setLastSync(summary);
					panel.setStatus("Connected", ColorScheme.PROGRESS_COMPLETE_COLOR);
				}
				else
				{
					panel.setStatus("Sync failed: " + message, ColorScheme.PROGRESS_ERROR_COLOR);
				}
			});
	}

	private void testConnection()
	{
		if (panel != null)
		{
			panel.setStatus("Testing…", ColorScheme.LIGHT_GRAY_COLOR);
			panel.setTestEnabled(false);
		}
		api.testConnection(config.baseUrl(), config.apiKey(), (ok, message) ->
		{
			if (panel != null)
			{
				panel.setStatus(message, ok
					? ColorScheme.PROGRESS_COMPLETE_COLOR
					: ColorScheme.PROGRESS_ERROR_COLOR);
				panel.setTestEnabled(true);
			}
		});
	}

	private static boolean isSell(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.SELLING
			|| state == GrandExchangeOfferState.SOLD
			|| state == GrandExchangeOfferState.CANCELLED_SELL;
	}

	private String itemName(int itemId)
	{
		try
		{
			return itemManager.getItemComposition(itemId).getName();
		}
		catch (Exception e)
		{
			return "Item " + itemId;
		}
	}

	private void resetBaselines()
	{
		for (int i = 0; i < SLOTS; i++)
		{
			lastQtySold[i] = 0;
			lastSpent[i] = 0;
			lastBuyOfferKey[i] = null;
		}
	}

	/** The OSRS coins (gp stack) icon — the same item sprite the website uses. */
	private static BufferedImage icon()
	{
		return ImageUtil.loadImageResource(FlipFinderSyncPlugin.class, "coins.png");
	}
}
