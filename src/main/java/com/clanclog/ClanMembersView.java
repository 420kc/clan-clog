package com.clanclog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Slide-out content surface that lists every member of the currently-loaded
 * clan. Lives inside a {@link SlidePanel}, hidden by default. Same row layout
 * as before the panel restructure: name on the left, role centered, total
 * level + combat on the right.
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
		scroll.setPreferredSize(new Dimension(0, 320));
		add(scroll, BorderLayout.CENTER);

		showPlaceholder("no clan loaded");
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

	public void showPlaceholder(String text)
	{
		list.removeAll();
		JLabel hint = new JLabel(text);
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(TEXT_DIM);
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		hint.setBorder(new EmptyBorder(8, 4, 4, 4));
		list.add(hint);
		list.add(Box.createVerticalGlue());
		list.revalidate();
		list.repaint();
	}

	private static Component buildMemberRow(ClanMember m)
	{
		JPanel row = new JPanel(new GridLayout(1, 3, 8, 0));
		row.setOpaque(true);
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(2, 6, 2, 6));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

		JLabel name = new JLabel(m.getDisplayName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(KC_TEXT);

		JLabel role = new JLabel(prettyRole(m.getRole()));
		role.setFont(FontManager.getRunescapeSmallFont());
		role.setForeground(TEXT_DIM);

		JLabel total = new JLabel();
		total.setFont(FontManager.getRunescapeSmallFont());
		HiscoreResult hs = m.getHiscore();
		if (hs != null)
		{
			total.setText("tl " + hs.getTotalLevel() + " · cb " + hs.getCombatLevel());
			total.setForeground(KC_TEXT);
		}
		else
		{
			total.setText("·");
			total.setForeground(TEXT_DIM);
		}
		total.setHorizontalAlignment(JLabel.RIGHT);

		row.add(name);
		row.add(role);
		row.add(total);

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

	private static String prettyRole(String raw)
	{
		if (raw == null || raw.isEmpty())
		{
			return "";
		}
		return raw.replace('_', ' ');
	}

	@SuppressWarnings("unused")
	private static List<ClanMember> nullSafe(List<ClanMember> in)
	{
		return in != null ? in : new ArrayList<>();
	}
}
