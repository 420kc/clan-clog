package com.clanclog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Content surface that lists every member of the currently-loaded clan. Same
 * row layout as before the panel restructure: name on the left, role centered,
 * total level + combat on the right.
 */
public class ClanMembersView extends JPanel
{
	private static final Color TEXT_DIM = new Color(160, 160, 160);
	private static final Color KC_TEXT = new Color(215, 215, 215);

	private final JPanel list;

	public ClanMembersView()
	{
		super(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scroll = new JScrollPane(list,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.setViewportBorder(null);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUI(new MinimalScrollBarUI());
		scroll.getVerticalScrollBar().setPreferredSize(new Dimension(7, 0));
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setPreferredSize(new Dimension(0, 320));
		add(scroll, BorderLayout.CENTER);

		showPlaceholder(" ");
	}

	public void renderRoster(List<ClanMember> roster)
	{
		list.removeAll();
		if (roster == null || roster.isEmpty())
		{
			showPlaceholder("no members to show");
			return;
		}
		for (ClanMember m : roster)
		{
			list.add(buildMemberRow(m));
		}
		list.add(Box.createVerticalGlue());
		list.revalidate();
		list.repaint();
	}

	public void renderSearchResults(WomGroup[] results, java.util.function.IntConsumer onPick)
	{
		list.removeAll();
		if (results == null || results.length == 0)
		{
			showPlaceholder("no matches");
			return;
		}
		for (WomGroup g : results)
		{
			list.add(buildSearchRow(g, onPick));
		}
		list.add(Box.createVerticalGlue());
		list.revalidate();
		list.repaint();
	}

	public void renderProfileSearchResults(List<KillclogApiClient.ClanSearchMatch> results,
		Consumer<String> onPick)
	{
		list.removeAll();
		if (results == null || results.isEmpty())
		{
			showPlaceholder("no matches");
			return;
		}
		for (KillclogApiClient.ClanSearchMatch match : results)
		{
			list.add(buildProfileSearchRow(match, onPick));
		}
		list.add(Box.createVerticalGlue());
		list.revalidate();
		list.repaint();
	}

	public void showPlaceholder(String text)
	{
		list.removeAll();
		JLabel hint = new JLabel(text);
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(TEXT_DIM);
		hint.putClientProperty(
			java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		hint.setBorder(new EmptyBorder(8, 4, 4, 4));
		list.add(hint);
		list.add(Box.createVerticalGlue());
		list.revalidate();
		list.repaint();
	}

	private static Component buildMemberRow(ClanMember m)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(true);
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(3, 6, 3, 6));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

		JLabel name = new JLabel(m.getDisplayName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(KC_TEXT);
		name.putClientProperty(
			java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		JLabel meta = new JLabel(memberMeta(m));
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(TEXT_DIM);
		meta.putClientProperty(
			java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		JLabel total = new JLabel();
		total.setFont(FontManager.getRunescapeSmallFont());
		total.putClientProperty(
			java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		HiscoreResult hs = m.getHiscore();
		if (hs != null)
		{
			total.setText("tl " + hs.getTotalLevel() + "  cb " + hs.getCombatLevel());
			total.setForeground(KC_TEXT);
		}
		else
		{
			total.setText("hiscores --");
			total.setForeground(TEXT_DIM);
		}
		total.setHorizontalAlignment(JLabel.RIGHT);

		JLabel clog = new JLabel(clogText(m.getClog()));
		clog.setFont(FontManager.getRunescapeSmallFont());
		clog.setForeground(m.getClog() != null ? ClogHelper.COLOR_COMPLETED : TEXT_DIM);
		clog.setHorizontalAlignment(JLabel.RIGHT);
		clog.putClientProperty(
			java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		JPanel identity = new JPanel(new GridLayout(2, 1, 0, 0));
		identity.setOpaque(false);
		identity.add(name);
		identity.add(meta);

		JPanel scores = new JPanel(new GridLayout(2, 1, 0, 0));
		scores.setOpaque(false);
		scores.add(total);
		scores.add(clog);

		row.add(identity, BorderLayout.CENTER);
		row.add(scores, BorderLayout.EAST);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_COLOR);
			}
		});
		return row;
	}

	private static String memberMeta(ClanMember member)
	{
		String role = prettyRole(member.getRole());
		String account = accountLabel(member);
		String build = member.getBuild();

		StringBuilder out = new StringBuilder();
		appendPart(out, role);
		appendPart(out, account);
		appendPart(out, build);
		if (out.length() == 0)
		{
			return "member";
		}
		return out.toString();
	}

	private static String accountLabel(ClanMember member)
	{
		HiscoreResult hiscore = member.getHiscore();
		AccountType type = hiscore != null && hiscore.getAccountType() != null
			? hiscore.getAccountType() : member.getAccountType();
		String label = ClogHelper.accountLabel(type);
		return label != null ? label : "regular";
	}

	private static String clogText(ClogResult clog)
	{
		if (clog == null)
		{
			return "clog --";
		}
		int obtained = clogObtainedCount(clog);
		String count = obtained >= 0 ? String.format("%,d", obtained) : "--";
		return "clog " + count;
	}

	private static int clogObtainedCount(ClogResult clog)
	{
		if (clog.getUniqueObtained() >= 0)
		{
			return clog.getUniqueObtained();
		}
		if (clog.getObtainedItems() == null)
		{
			return -1;
		}
		Set<Integer> ids = new HashSet<>();
		for (Map.Entry<String, List<ClogResult.ClogItem>> entry : clog.getObtainedItems().entrySet())
		{
			for (ClogResult.ClogItem item : entry.getValue())
			{
				ids.add(item.getId());
			}
		}
		return ids.size();
	}

	private static void appendPart(StringBuilder out, String value)
	{
		if (value == null || value.isBlank())
		{
			return;
		}
		if (out.length() > 0)
		{
			out.append(" · ");
		}
		out.append(value);
	}

	private static Component buildSearchRow(WomGroup g, java.util.function.IntConsumer onPick)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(true);
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(4, 6, 4, 6));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

		JLabel name = new JLabel(g.name != null ? g.name : "(unnamed)");
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(KC_TEXT);

		JLabel meta = new JLabel(g.memberCount + " · #" + g.id);
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(TEXT_DIM);
		meta.setHorizontalAlignment(JLabel.RIGHT);

		row.add(name, BorderLayout.WEST);
		row.add(meta, BorderLayout.EAST);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onPick.accept(g.id);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_COLOR);
			}
		});
		return row;
	}

	private static Component buildProfileSearchRow(KillclogApiClient.ClanSearchMatch match,
		Consumer<String> onPick)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(true);
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(4, 6, 4, 6));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

		JLabel name = new JLabel(match.getDisplayName() != null
			? match.getDisplayName() : match.getSlug());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(KC_TEXT);

		JLabel meta = new JLabel(match.getMemberCount() + " · "
			+ prettyRole(match.getSourceTier()));
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(TEXT_DIM);
		meta.setHorizontalAlignment(JLabel.RIGHT);

		row.add(name, BorderLayout.WEST);
		row.add(meta, BorderLayout.EAST);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (match.getSlug() != null && !match.getSlug().isBlank())
				{
					onPick.accept(match.getSlug());
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_COLOR);
			}
		});
		return row;
	}

	private static String prettyRole(String raw)
	{
		if (raw == null || raw.isEmpty())
		{
			return "";
		}
		return raw.replace('_', ' ');
	}
}
