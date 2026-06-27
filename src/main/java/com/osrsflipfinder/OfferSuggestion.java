package com.osrsflipfinder;

/**
 * A live GE offer paired with its model {@link Suggestion} (nullable when the
 * item has no usable two-sided price). Built on the client thread from an
 * immutable snapshot of the offer, so the panel can render it on the EDT without
 * touching the game client.
 */
class OfferSuggestion
{
	final int itemId;
	final String itemName;
	/** True for a SELL offer, false for a BUY. */
	final boolean sell;
	/** The player's offer price (gp). */
	final int offerPrice;
	final int totalQty;
	final int qtySold;
	/** Model suggestion for this item, or null when none was available. */
	final Suggestion suggestion;

	OfferSuggestion(int itemId, String itemName, boolean sell, int offerPrice,
		int totalQty, int qtySold, Suggestion suggestion)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.sell = sell;
		this.offerPrice = offerPrice;
		this.totalQty = totalQty;
		this.qtySold = qtySold;
		this.suggestion = suggestion;
	}
}
