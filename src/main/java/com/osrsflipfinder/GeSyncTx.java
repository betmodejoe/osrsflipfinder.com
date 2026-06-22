package com.osrsflipfinder;

import lombok.Value;

/**
 * One Grand Exchange update sent to OSRS Flip Finder. Field names match the
 * ingest endpoint's JSON contract, so Gson serialises this directly.
 *
 * Two shapes share this object (use the static factories):
 *  - {@code buy}  — cumulative offer progress, upserted server-side by
 *                   {@code offerKey} into one growing position ({@code filled} of
 *                   {@code target}).
 *  - {@code sell} — a newly-filled delta ({@code qty}) deduped by
 *                   {@code clientTxId} and FIFO-blended into open positions.
 *  - {@code buyCancel} — an offer cancelled before anything filled; the server
 *                   removes the placeholder "buying 0/target" position.
 */
@Value
public class GeSyncTx
{
	String type;        // "buy" | "sell"
	String offerKey;    // buys: stable per-offer id for upsert
	String clientTxId;  // sells: dedupe id
	int itemId;
	String itemName;
	long price;         // average unit price (cumulative for buys, delta for sells)
	int qty;            // sells: newly-sold quantity in this delta
	int filled;         // buys: cumulative quantity bought so far
	int target;         // buys: the offer's total quantity
	long txAt;
	Boolean active;     // sell-listed: true when a sell offer is live, else false

	static GeSyncTx buy(String offerKey, int itemId, String itemName,
		int filled, int target, long price, long txAt)
	{
		return new GeSyncTx("buy", offerKey, null, itemId, itemName, price, 0, filled, target, txAt, null);
	}

	static GeSyncTx sell(String clientTxId, int itemId, String itemName,
		int qty, long price, long txAt)
	{
		return new GeSyncTx("sell", null, clientTxId, itemId, itemName, price, qty, 0, 0, txAt, null);
	}

	static GeSyncTx buyCancel(String offerKey, int itemId, String itemName, long txAt)
	{
		return new GeSyncTx("buy-cancel", offerKey, null, itemId, itemName, 0, 0, 0, 0, txAt, null);
	}

	// A sell offer's listed-state: active when one is live on the GE (even at 0
	// sold), inactive when cancelled/collected. Lets a bought position show
	// "selling 0 / Y" instead of "holding".
	static GeSyncTx sellListed(int itemId, String itemName, boolean active, long price, long txAt)
	{
		return new GeSyncTx("sell-listed", null, null, itemId, itemName, price, 0, 0, 0, txAt, active);
	}
}
