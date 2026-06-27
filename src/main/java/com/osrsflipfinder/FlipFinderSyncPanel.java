package com.osrsflipfinder;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Status panel: connection state, a "Test connection" button, the last synced
 * fill, a session counter, and a live "Live offers" section showing OSRS Flip
 * Finder's suggested prices next to each Grand Exchange offer. All mutators
 * marshal onto the EDT so they are safe to call from OkHttp callback threads.
 */
class FlipFinderSyncPanel extends PluginPanel
{
	private final JLabel status = new JLabel("Not connected");
	private final JLabel lastSync = new JLabel("No trades synced yet");
	private final JLabel sessionCount = new JLabel("Synced this session: 0");
	private final JButton testButton = new JButton("Test connection");
	private final JButton refreshButton = new JButton("Refresh");
	private final JPanel suggestions = new JPanel();
	private final Runnable onRefresh;

	FlipFinderSyncPanel(Runnable onTest, Runnable onRefresh)
	{
		super(true);
		this.onRefresh = onRefresh;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 10));

		final JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel title = new JLabel("Flip Finder Sync");
		title.setForeground(Color.WHITE);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
		title.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel blurb = new JLabel("<html>Grand Exchange trades sync to your OSRS Flip Finder journal automatically.</html>");
		blurb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		blurb.setAlignmentX(Component.LEFT_ALIGNMENT);

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		lastSync.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lastSync.setAlignmentX(Component.LEFT_ALIGNMENT);
		sessionCount.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sessionCount.setAlignmentX(Component.LEFT_ALIGNMENT);

		testButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		testButton.addActionListener(e -> onTest.run());

		content.add(title);
		content.add(strut(8));
		content.add(blurb);
		content.add(strut(12));
		content.add(testButton);
		content.add(strut(12));
		content.add(status);
		content.add(strut(6));
		content.add(lastSync);
		content.add(strut(6));
		content.add(sessionCount);
		content.add(strut(16));

		// "Live offers" header with a manual Refresh button.
		final JLabel offersTitle = new JLabel("Live offers");
		offersTitle.setForeground(Color.WHITE);
		offersTitle.setFont(offersTitle.getFont().deriveFont(Font.BOLD, 13f));
		refreshButton.setFont(refreshButton.getFont().deriveFont(11f));
		refreshButton.addActionListener(e -> onRefresh.run());
		final JPanel offersHeader = new JPanel();
		offersHeader.setLayout(new BoxLayout(offersHeader, BoxLayout.X_AXIS));
		offersHeader.setBackground(ColorScheme.DARK_GRAY_COLOR);
		offersHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		offersHeader.add(offersTitle);
		offersHeader.add(Box.createHorizontalGlue());
		offersHeader.add(refreshButton);
		offersHeader.setMaximumSize(
			new Dimension(Integer.MAX_VALUE, refreshButton.getPreferredSize().height + 4));
		content.add(offersHeader);
		content.add(strut(6));

		suggestions.setLayout(new BoxLayout(suggestions, BoxLayout.Y_AXIS));
		suggestions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		suggestions.setAlignmentX(Component.LEFT_ALIGNMENT);
		suggestions.add(muted("No live offers."));
		content.add(suggestions);

		add(content, BorderLayout.NORTH);
	}

	@Override
	public void onActivate()
	{
		super.onActivate();
		onRefresh.run();
	}

	private static Component strut(int h)
	{
		return Box.createVerticalStrut(h);
	}

	void setStatus(String text, Color color)
	{
		SwingUtilities.invokeLater(() ->
		{
			status.setText(text);
			status.setForeground(color);
		});
	}

	void setLastSync(String text)
	{
		SwingUtilities.invokeLater(() -> lastSync.setText("Last: " + text));
	}

	void setSessionCount(int n)
	{
		SwingUtilities.invokeLater(() -> sessionCount.setText("Synced this session: " + n));
	}

	void setTestEnabled(boolean enabled)
	{
		SwingUtilities.invokeLater(() -> testButton.setEnabled(enabled));
	}

	/** Replace the live-offer list with one row per offer (or an empty message). */
	void setSuggestions(List<OfferSuggestion> rows)
	{
		SwingUtilities.invokeLater(() ->
		{
			suggestions.removeAll();
			if (rows == null || rows.isEmpty())
			{
				suggestions.add(muted("No live offers."));
			}
			else
			{
				for (final OfferSuggestion os : rows)
				{
					suggestions.add(buildRow(os));
				}
			}
			suggestions.revalidate();
			suggestions.repaint();
		});
	}

	void setSuggestionsError(String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			suggestions.removeAll();
			final JLabel l = muted("Suggestions unavailable: " + message);
			l.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
			suggestions.add(l);
			suggestions.revalidate();
			suggestions.repaint();
		});
	}

	void setSuggestionsHint(String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			suggestions.removeAll();
			suggestions.add(muted(message));
			suggestions.revalidate();
			suggestions.repaint();
		});
	}

	/** One offer's card: identity, your-vs-model price, margin, fill/ETA, drift. */
	private static JPanel buildRow(OfferSuggestion os)
	{
		final JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 6, 6, 6)));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		final boolean sell = os.sell;
		final Suggestion s = os.suggestion;

		final JLabel head = new JLabel(os.itemName + "  ·  " + (sell ? "SELL" : "BUY")
			+ "   " + fmt(os.qtySold) + "/" + fmt(os.totalQty));
		head.setForeground(Color.WHITE);
		head.setFont(head.getFont().deriveFont(Font.BOLD, 12f));
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(head);

		if (s == null)
		{
			row.add(bodyLabel("No model price available", ColorScheme.LIGHT_GRAY_COLOR));
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
			return row;
		}

		// Your price vs model + over/under delta.
		final Integer suggested = sell ? s.suggestedSell : s.suggestedBuy;
		if (suggested != null)
		{
			final long delta = os.offerPrice - suggested;
			final String deltaText = delta == 0
				? "at model"
				: fmt(Math.abs(delta)) + (delta > 0 ? " over" : " under") + " model";
			row.add(bodyLabel("You " + fmt(os.offerPrice) + "  ·  Model " + fmt(suggested)
				+ "  (" + deltaText + ")", ColorScheme.LIGHT_GRAY_COLOR));
		}

		// Margin per item + projected profit for the offer quantity.
		if (s.netMargin != null)
		{
			final StringBuilder m = new StringBuilder("Margin " + fmt(s.netMargin) + "/ea");
			if (!sell && s.suggestedSell != null)
			{
				final long tax = s.taxPerItem == null ? 0L : s.taxPerItem;
				final long proj = ((long) s.suggestedSell - tax - os.offerPrice) * os.totalQty;
				m.append("  ·  if sold at model ~").append(gpShort(proj));
			}
			else
			{
				m.append("  ·  cycle ~").append(gpShort(s.netMargin * (long) os.totalQty));
			}
			row.add(bodyLabel(m.toString(),
				s.netMargin >= 0 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR));
		}

		// Fill probability (at the user's price when known) + a rough ETA.
		final Double fp = sell
			? (s.yourSellFillProb != null ? s.yourSellFillProb : s.sellFillProb)
			: (s.yourBuyFillProb != null ? s.yourBuyFillProb : s.buyFillProb);
		if (fp != null)
		{
			final int pct = (int) Math.round(fp * 100);
			final int remaining = Math.max(0, os.totalQty - os.qtySold);
			final long vol = sell
				? (s.highVol == null ? 0L : s.highVol)
				: (s.lowVol == null ? 0L : s.lowVol);
			final Color c = pct >= 60
				? ColorScheme.PROGRESS_COMPLETE_COLOR
				: pct >= 35 ? ColorScheme.PROGRESS_INPROGRESS_COLOR : ColorScheme.PROGRESS_ERROR_COLOR;
			row.add(bodyLabel("Fill " + pct + "%" + (pct < 35 ? " — unlikely" : "")
				+ "  ·  ETA " + etaText(remaining, vol), c));
		}

		// Stale / drift: market has moved versus the placed price.
		final String drift = driftMessage(os, s);
		if (drift != null)
		{
			row.add(bodyLabel(drift, ColorScheme.PROGRESS_INPROGRESS_COLOR));
		}

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** A drift warning when the market has moved away from the placed price, else null. */
	private static String driftMessage(OfferSuggestion os, Suggestion s)
	{
		if (s.instaBuy == null || s.instaSell == null)
		{
			return null;
		}
		final int instaBuy = s.instaBuy;   // the price you SELL into
		final int instaSell = s.instaSell; // the price you BUY at
		final int price = os.offerPrice;
		final double thresh = Math.max(1.0, price * 0.005);
		if (os.sell)
		{
			if (price - instaBuy > thresh)
			{
				return "▼ market " + fmt(instaBuy) + " — sell may stall";
			}
			if (instaSell - price > thresh)
			{
				return "▲ priced below market — leaving gp";
			}
		}
		else
		{
			if (instaSell - price > thresh)
			{
				return "▲ market " + fmt(instaSell) + " — buy may stall";
			}
			if (price - instaBuy > thresh)
			{
				return "▼ buying above market";
			}
		}
		return null;
	}

	private static String etaText(int remaining, long volPerHour)
	{
		if (remaining <= 0)
		{
			return "ready";
		}
		if (volPerHour <= 0)
		{
			return "—";
		}
		final double hours = (double) remaining / volPerHour;
		if (hours < 1.0 / 60)
		{
			return "<1m";
		}
		if (hours < 1)
		{
			return Math.round(hours * 60) + "m";
		}
		if (hours < 24)
		{
			return String.format(Locale.US, "%.1fh", hours);
		}
		return "1d+";
	}

	private static JLabel bodyLabel(String text, Color color)
	{
		final JLabel l = new JLabel(text);
		l.setForeground(color);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		l.setFont(l.getFont().deriveFont(11f));
		return l;
	}

	private static JLabel muted(String text)
	{
		final JLabel l = new JLabel("<html>" + text + "</html>");
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static String fmt(long v)
	{
		return String.format(Locale.US, "%,d", v);
	}

	private static String gpShort(long v)
	{
		final long a = Math.abs(v);
		if (a >= 10_000_000)
		{
			return (v / 1_000_000) + "M";
		}
		if (a >= 1_000_000)
		{
			return String.format(Locale.US, "%.1fM", v / 1_000_000.0);
		}
		if (a >= 100_000)
		{
			return (v / 1_000) + "K";
		}
		if (a >= 1_000)
		{
			return String.format(Locale.US, "%.1fK", v / 1_000.0);
		}
		return String.valueOf(v);
	}
}
