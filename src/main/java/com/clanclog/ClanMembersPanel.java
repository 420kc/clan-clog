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
		statusLabel.setText(rosterStatus(roster));
		membersView.renderRoster(roster);
	}

	public void renderSearchResults(String query, WomGroup[] results, IntConsumer onPick)
	{
		titleLabel.setText("clan matches");
		int count = results != null ? results.length : 0;
		statusLabel.setText(searchStatus(query, count, "public clan", "public clans"));
		membersView.renderSearchResults(results, onPick);
	}

	public void renderProfileSearchResults(String query,
		List<KillclogApiClient.ClanSearchMatch> results, Consumer<String> onPick)
	{
		titleLabel.setText("killclog.com matches");
		int count = results != null ? results.size() : 0;
		statusLabel.setText(searchStatus(query, count, "profile", "profiles"));
		membersView.renderProfileSearchResults(results, onPick);
	}

	public void showPlaceholder(String text)
	{
		titleLabel.setText("members");
		statusLabel.setText(" ");
		membersView.showPlaceholder(text);
	}

	private static String rosterStatus(List<ClanMember> roster)
	{
		if (roster == null || roster.isEmpty())
		{
			return "0 members";
		}

		int hiscores = 0;
		int clogs = 0;
		for (ClanMember member : roster)
		{
			if (member.getHiscore() != null)
			{
				hiscores++;
			}
			if (member.getClog() != null)
			{
				clogs++;
			}
		}

		StringBuilder status = new StringBuilder();
		status.append(roster.size()).append(" ").append(noun(roster.size(), "member", "members"));
		if (hiscores > 0)
		{
			status.append(" · ").append(hiscores).append(" ")
				.append(noun(hiscores, "hiscore", "hiscores"));
		}
		if (clogs > 0)
		{
			status.append(" · ").append(clogs).append(" ")
				.append(noun(clogs, "clog", "clogs"));
		}
		return status.toString();
	}

	private static String searchStatus(String query, int count, String singular, String plural)
	{
		String countText = count + " " + noun(count, singular, plural);
		if (query == null || query.isBlank())
		{
			return countText;
		}
		return query.trim() + " · " + countText;
	}

	private static String noun(int count, String singular, String plural)
	{
		return count == 1 ? singular : plural;
	}
}
