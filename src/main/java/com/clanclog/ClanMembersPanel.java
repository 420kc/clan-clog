package com.clanclog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

@Singleton
public class ClanMembersPanel extends PluginPanel
{
	private static final Color TEXT_DIM = new Color(160, 160, 160);
	private static final Color KC_TEXT = new Color(215, 215, 215);

	private final JLabel titleLabel = new JLabel("members");
	private final JLabel statusLabel = new JLabel(" ");
	private final ClanMembersView membersView = new ClanMembersView();

	@Inject
	public ClanMembersPanel()
	{
		super(false);
		setLayout(new BorderLayout(0, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(8, 8, 8, 8));

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleLabel.setFont(FontManager.getRunescapeFont());
		titleLabel.setForeground(KC_TEXT);
		titleLabel.putClientProperty(
			java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(TEXT_DIM);
		statusLabel.putClientProperty(
			java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		header.add(titleLabel, BorderLayout.NORTH);
		header.add(statusLabel, BorderLayout.SOUTH);

		add(header, BorderLayout.NORTH);
		add(membersView, BorderLayout.CENTER);
		showPlaceholder("open your clan tab in-game");
	}

	public void renderRoster(String clanName, List<ClanMember> roster)
	{
		titleLabel.setText(clanName == null || clanName.isBlank() ? "members" : clanName + " members");
		statusLabel.setText(roster == null ? "0 members" : roster.size() + " members");
		membersView.renderRoster(roster);
	}

	public void renderSearchResults(String query, WomGroup[] results, IntConsumer onPick)
	{
		titleLabel.setText("clan matches");
		statusLabel.setText(query == null || query.isBlank() ? "search results" : query);
		membersView.renderSearchResults(results, onPick);
	}

	public void renderProfileSearchResults(String query,
		List<KillclogApiClient.ClanSearchMatch> results, Consumer<String> onPick)
	{
		titleLabel.setText("killclog.com matches");
		statusLabel.setText(query == null || query.isBlank() ? "search results" : query);
		membersView.renderProfileSearchResults(results, onPick);
	}

	public void showPlaceholder(String text)
	{
		titleLabel.setText("members");
		statusLabel.setText(" ");
		membersView.showPlaceholder(text);
	}
}
