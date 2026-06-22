package com.osrsflipfinder;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Tiny status panel: connection state, a "Test connection" button, the last
 * synced fill, and a session counter. All mutators marshal onto the EDT so they
 * are safe to call from OkHttp callback threads.
 */
class FlipFinderSyncPanel extends PluginPanel
{
	private final JLabel status = new JLabel("Not connected");
	private final JLabel lastSync = new JLabel("No trades synced yet");
	private final JLabel sessionCount = new JLabel("Synced this session: 0");
	private final JButton testButton = new JButton("Test connection");

	FlipFinderSyncPanel(Runnable onTest)
	{
		super(false);
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

		add(content, BorderLayout.NORTH);
	}

	private static Component strut(int h)
	{
		return javax.swing.Box.createVerticalStrut(h);
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
}
