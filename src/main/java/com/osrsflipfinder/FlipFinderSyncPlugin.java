package com.osrsflipfinder;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Flip Finder Sync",
	description = "Auto-sync your Grand Exchange flips, buys & sells to a profit journal",
	tags = {
		"ge", "flipping", "merch", "merchanting", "tracker", "profit",
		"tax", "gp", "gold", "money", "margin", "journal"
	}
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

	@Inject
	private ClientThread clientThread;

	// Per-slot cumulative state, so each event yields only the newly-filled delta.
	private final long[] lastQtySold = new long[SLOTS];
	private final long[] lastSpent = new long[SLOTS];
	// Per-slot offerKey we last reported a BUY for, so a freshly-placed offer is
	// reported once (as "buying 0/target") without re-sending on every tick.
	private final String[] lastBuyOfferKey = new String[SLOTS];
	// Per-slot SELL-offer listed state, so a placed sell shows "selling" before any
	// unit sells. The item is remembered so the clear can name it after collect.
	private final boolean[] sellListed = new boolean[SLOTS];
	private final int[] sellListedItem = new int[SLOTS];

	private int sessionCount;

	private FlipFinderSyncPanel panel;
	private NavigationButton navButton;

	// Suggestions: throttle + dedupe the /api/suggest polling, and cache the last
	// response so an offer change can re-render the panel instantly (with cached
	// prices) without waiting on the network. lastSuggestions is read on the
	// client thread and written on the OkHttp callback thread, hence volatile.
	private static final long SUGGEST_DEDUPE_WINDOW_MS = 8_000L;
	private long lastSuggestFetchMs;
	private Set<Integer> lastFetchedItemIds = Collections.emptySet();
	private volatile Map<Integer, Suggestion> lastSuggestions = Collections.emptyMap();

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
		panel = new FlipFinderSyncPanel(this::testConnection, () -> refreshSuggestions(true));
		navButton = NavigationButton.builder()
			.tooltip("Flip Finder Sync")
			.icon(icon())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		// Auto-connect: verify the configured key the moment the plugin loads, so
		// the panel shows live connection status without a manual button click.
		if (!config.apiKey().trim().isEmpty())
		{
			testConnection();
		}
		refreshSuggestions(true);
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
	public void onConfigChanged(ConfigChanged event)
	{
		if (!FlipFinderSyncConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		// Re-verify whenever the key changes, so pasting/clearing it updates the
		// panel's connection status immediately.
		if ("apiKey".equals(event.getKey()))
		{
			if (!config.apiKey().trim().isEmpty())
			{
				testConnection();
			}
			else if (panel != null)
			{
				panel.setStatus("Not connected", ColorScheme.LIGHT_GRAY_COLOR);
			}
		}
		// Re-pull suggestions when the key, the toggle, or the risk dial changes.
		final String key = event.getKey();
		if ("apiKey".equals(key) || "showSuggestions".equals(key) || "risk".equals(key))
		{
			refreshSuggestions(true);
		}
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
			// Clear the now-stale offer view; the post-login burst repopulates it.
			lastFetchedItemIds = Collections.emptySet();
			lastSuggestions = Collections.emptyMap();
			if (panel != null)
			{
				panel.setSuggestions(Collections.emptyList());
			}
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

		// Any offer change (placed, filled, cancelled, collected) can change the
		// live-offer view, so refresh suggestions. Independent of the sync toggle
		// and throttled inside refreshSuggestions.
		refreshSuggestions(false);

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
			// starts from zero. If a sell was still flagged listed, clear it —
			// naming the remembered item, since the offer's id is now gone.
			if (config.enableSync() && sellListed[slot])
			{
				submitListing(sellListedItem[slot], false, 0, System.currentTimeMillis());
			}
			lastQtySold[slot] = 0;
			lastSpent[slot] = 0;
			lastBuyOfferKey[slot] = null;
			sellListed[slot] = false;
			sellListedItem[slot] = 0;
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
			api.submit(config.apiKey(), Collections.singletonList(cancel),
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

		// Sell-offer listed lifecycle — report "selling" the moment a sell offer is
		// placed (even at 0 sold) and clear it on cancel or full sale. Sent as its
		// own update so it fires even when no units filled this event.
		if (sell)
		{
			if (state == GrandExchangeOfferState.SELLING && !sellListed[slot])
			{
				sellListed[slot] = true;
				sellListedItem[slot] = itemId;
				submitListing(itemId, true, offer.getPrice(), txAt);
			}
			else if ((state == GrandExchangeOfferState.CANCELLED_SELL
				|| state == GrandExchangeOfferState.SOLD) && sellListed[slot])
			{
				sellListed[slot] = false;
				submitListing(itemId, false, offer.getPrice(), txAt);
			}
		}

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

		api.submit(config.apiKey(), Collections.singletonList(tx),
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

	/** Report a sell offer's listed-state on its own (fires even at 0 sold). */
	private void submitListing(int itemId, boolean active, long price, long txAt)
	{
		final GeSyncTx tx = GeSyncTx.sellListed(itemId, itemName(itemId), active, price, txAt);
		api.submit(config.apiKey(), Collections.singletonList(tx),
			(ok, message) ->
			{
				if (panel != null && !ok)
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
		api.testConnection(config.apiKey(), (ok, message) ->
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

	/** Periodic refresh so suggestions track moving prices even without an event. */
	@Schedule(period = 25, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void scheduledSuggestRefresh()
	{
		refreshSuggestions(false);
	}

	/**
	 * Refresh the live-offer suggestions. Only config is read here; the GE offers
	 * are read on the client thread (this may run on a scheduler thread).
	 * {@code force} bypasses the dedupe window (panel open / manual refresh / start).
	 */
	private void refreshSuggestions(boolean force)
	{
		if (panel == null || !config.showSuggestions())
		{
			return;
		}
		final String key = config.apiKey();
		if (isBlank(key))
		{
			panel.setSuggestionsHint("Add your API key in the config to see price suggestions.");
			return;
		}
		clientThread.invokeLater(() -> collectAndFetch(force, key));
	}

	/** Client-thread: snapshot the live offers, paint from cache, and fetch if due. */
	private void collectAndFetch(boolean force, String key)
	{
		if (panel == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}

		final List<OfferSuggestion> snapshot = new ArrayList<>();
		final Set<Integer> ids = new LinkedHashSet<>();
		for (final GrandExchangeOffer offer : offers)
		{
			if (offer == null)
			{
				continue;
			}
			final GrandExchangeOfferState state = offer.getState();
			final int itemId = offer.getItemId();
			if (!isActive(state) || itemId <= 0)
			{
				continue;
			}
			snapshot.add(new OfferSuggestion(itemId, itemName(itemId), isSell(state),
				offer.getPrice(), offer.getTotalQuantity(), offer.getQuantitySold(),
				lastSuggestions.get(itemId)));
			ids.add(itemId);
		}

		if (snapshot.isEmpty())
		{
			lastFetchedItemIds = Collections.emptySet();
			panel.setSuggestions(Collections.emptyList());
			return;
		}

		// Paint immediately from cached prices so new offers / fills show at once.
		panel.setSuggestions(snapshot);

		// Throttle the network call: skip when the same item set was just fetched.
		final long now = System.currentTimeMillis();
		if (!force
			&& now - lastSuggestFetchMs < SUGGEST_DEDUPE_WINDOW_MS
			&& ids.equals(lastFetchedItemIds))
		{
			return;
		}
		lastSuggestFetchMs = now;
		lastFetchedItemIds = ids;

		final List<int[]> items = new ArrayList<>(snapshot.size());
		for (final OfferSuggestion s : snapshot)
		{
			items.add(new int[]{s.itemId, s.offerPrice});
		}

		api.fetchSuggestions(key, items, config.risk() / 100.0,
			new FlipFinderApiClient.SuggestionsCallback()
			{
				@Override
				public void onSuccess(Map<Integer, Suggestion> byItemId)
				{
					lastSuggestions = byItemId;
					if (panel == null)
					{
						return;
					}
					final List<OfferSuggestion> rows = new ArrayList<>(snapshot.size());
					for (final OfferSuggestion s : snapshot)
					{
						rows.add(new OfferSuggestion(s.itemId, s.itemName, s.sell,
							s.offerPrice, s.totalQty, s.qtySold, byItemId.get(s.itemId)));
					}
					panel.setSuggestions(rows);
				}

				@Override
				public void onError(String message)
				{
					if (panel != null)
					{
						panel.setSuggestionsError(message);
					}
				}
			});
	}

	/** An offer that occupies a slot we want to annotate (not empty/cancelled). */
	private static boolean isActive(GrandExchangeOfferState state)
	{
		return state != GrandExchangeOfferState.EMPTY
			&& state != GrandExchangeOfferState.CANCELLED_BUY
			&& state != GrandExchangeOfferState.CANCELLED_SELL;
	}

	private static boolean isBlank(String s)
	{
		return s == null || s.trim().isEmpty();
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
			sellListed[i] = false;
			sellListedItem[i] = 0;
		}
	}

	/** The OSRS coins (gp stack) icon — the same item sprite the website uses. */
	private static BufferedImage icon()
	{
		return ImageUtil.loadImageResource(FlipFinderSyncPlugin.class, "coins.png");
	}
}
