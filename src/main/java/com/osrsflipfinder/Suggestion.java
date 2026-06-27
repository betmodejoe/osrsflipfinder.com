package com.osrsflipfinder;

/**
 * One item's pricing suggestion from {@code GET /api/suggest}. A Gson DTO: the
 * field names match the JSON exactly and the types are boxed, so a field the
 * server omits stays {@code null} rather than defaulting to 0. All money values
 * are gp; probabilities are 0..1.
 */
class Suggestion
{
	int itemId;
	String name;
	/** GE buy limit per 4h; 0 = unknown / effectively unlimited. */
	Integer buyLimit;
	/** Model-suggested limit prices. */
	Integer suggestedBuy;
	Integer suggestedSell;
	/** Fill probability at the suggested prices. */
	Double buyFillProb;
	Double sellFillProb;
	/** Post-tax economics at the suggested prices, per item. */
	Long taxPerItem;
	Long netMargin;
	Double roi;
	Double roundTripProb;
	Double confidence;
	/** Current market: insta-buy (the price you SELL into) and insta-sell (the
	 * price you BUY at). */
	Integer instaBuy;
	Integer instaSell;
	/** ~1h sampled volumes (sell-leg / buy-leg flow) plus staleness, for a rough
	 * fill ETA. */
	Long highVol;
	Long lowVol;
	Long lastTradeAgeSec;
	/** Fill probability at the user's actual offer price — present only when the
	 * offer price was sent (id:price), making "unlikely to fill" exact. */
	Double yourBuyFillProb;
	Double yourSellFillProb;
}
