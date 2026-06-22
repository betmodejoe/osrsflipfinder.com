package com.osrsflipfinder;

import lombok.Value;

/**
 * One completed (or partially-filled) Grand Exchange transaction to send to
 * OSRS Flip Finder. Field names match the ingest endpoint's JSON contract
 * exactly, so Gson serialises this directly.
 */
@Value
public class GeFill
{
	/** Deterministic id: accountHash:slot:type:itemId:cumulativeQtySold. */
	String clientTxId;
	/** "buy" or "sell". */
	String type;
	int itemId;
	String itemName;
	/** Average unit price of this fill (spentDelta / qtyDelta). */
	long price;
	/** Newly-filled quantity in this delta. */
	int qty;
	/** Time of the fill, unix ms. */
	long txAt;
}
