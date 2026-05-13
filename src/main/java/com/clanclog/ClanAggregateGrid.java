package com.clanclog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The collective hiscore grid: kc-style cell layout, but each cell shows the
 * clan-wide best KC / score / level instead of a single player's number.
 * Holder attribution + ranked-member count + per-cell top-N tooltip make the
 * collective story legible at a glance.
 *
 * <p>Sections, top to bottom: skills (2-col), activities (2-col), bosses (2-col).
 * All scroll together inside a single viewport so the user can flick through
 * the whole clan snapshot.
 *
 * <p>First-cut visuals: text-only cells (no boss / skill / activity sprite
 * icons yet -- that polish needs SpriteManager + ItemManager + HiscoreSkill
 * mapping, deferred to a follow-up).
 */
public class ClanAggregateGrid extends JPanel
{
	private static final Color TEXT_DIM = new Color(160, 160, 160);
	private static final Color KC_TEXT = new Color(215, 215, 215);
	private static final Color KC_BRIGHT = new Color(255, 152, 31);

	private final JPanel skillsGrid = new JPanel(new GridLayout(0, 2, 4, 2));
	private final JPanel activitiesGrid = new JPanel(new GridLayout(0, 2, 4, 2));
	private final JPanel bossesGrid = new JPanel(new GridLayout(0, 2, 4, 2));

	public ClanAggregateGrid()
	{
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setBackground(ColorScheme.DARK_GRAY_COLOR);
		stack.setBorder(new EmptyBorder(4, 4, 4, 4));

		stack.add(buildSectionHeader("skills"));
		styleGrid(skillsGrid);
		stack.add(skillsGrid);
		stack.add(Box.createVerticalStrut(8));

		stack.add(buildSectionHeader("activities"));
		styleGrid(activitiesGrid);
		stack.add(activitiesGrid);
		stack.add(Box.createVerticalStrut(8));

		stack.add(buildSectionHeader("bosses"));
		styleGrid(bossesGrid);
		stack.add(bossesGrid);
		stack.add(Box.createVerticalGlue());

		JScrollPane scroll = new JScrollPane(stack,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.setViewportBorder(null);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		showEmpty();
	}

	public void renderRoster(List<ClanMember> roster)
	{
		if (roster == null || roster.isEmpty())
		{
			showEmpty();
			return;
		}
		fillGrid(skillsGrid, ClanAggregateHiscore.skills(roster), "lvl");
		fillGrid(activitiesGrid, ClanAggregateHiscore.activities(roster), "score");
		fillGrid(bossesGrid, ClanAggregateHiscore.bosses(roster), "kc");
		revalidate();
		repaint();
	}

	public void showEmpty()
	{
		List<JPanel> empties = new ArrayList<>();
		empties.add(skillsGrid);
		empties.add(activitiesGrid);
		empties.add(bossesGrid);
		for (JPanel grid : empties)
		{
			grid.removeAll();
			JLabel hint = new JLabel("(no clan loaded)");
			hint.setFont(FontManager.getRunescapeSmallFont());
			hint.setForeground(TEXT_DIM);
			grid.add(hint);
		}
		revalidate();
		repaint();
	}

	private static JLabel buildSectionHeader(String text)
	{
		JLabel header = new JLabel(text);
		header.setFont(FontManager.getRunescapeFont());
		header.setForeground(KC_TEXT);
		header.setBorder(new EmptyBorder(2, 0, 4, 0));
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		return header;
	}

	private static void styleGrid(JPanel grid)
	{
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
	}

	private static void fillGrid(JPanel grid,
		Map<String, ClanAggregateHiscore.CellAggregate> aggregates, String unit)
	{
		grid.removeAll();
		for (Map.Entry<String, ClanAggregateHiscore.CellAggregate> e : aggregates.entrySet())
		{
			grid.add(buildCell(e.getKey(), e.getValue(), unit));
		}
	}

	private static Component buildCell(String name,
		ClanAggregateHiscore.CellAggregate agg, String unit)
	{
		JPanel cell = new JPanel();
		cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cell.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR, 1),
			new EmptyBorder(3, 5, 3, 5)));
		cell.setPreferredSize(new Dimension(0, 34));

		JLabel nameLabel = new JLabel(prettyName(name));
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setForeground(TEXT_DIM);
		nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		cell.add(nameLabel);

		JLabel valueLabel = new JLabel();
		valueLabel.setFont(FontManager.getRunescapeSmallFont());
		valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (agg != null && agg.hasAny())
		{
			valueLabel.setText(agg.clanBest + " " + unit + " · " + agg.holder);
			valueLabel.setForeground(KC_BRIGHT);
		}
		else
		{
			valueLabel.setText("·");
			valueLabel.setForeground(TEXT_DIM);
		}
		cell.add(valueLabel);

		cell.setToolTipText(buildTooltip(name, agg, unit));

		return cell;
	}

	private static String buildTooltip(String name,
		ClanAggregateHiscore.CellAggregate agg, String unit)
	{
		if (agg == null || !agg.hasAny())
		{
			return "<html><b>" + escape(name) + "</b><br>no ranked clan members</html>";
		}
		StringBuilder sb = new StringBuilder("<html><b>")
			.append(escape(name)).append("</b><br>")
			.append(agg.rankedCount).append(" members ranked &middot; total ")
			.append(agg.total).append(' ').append(unit).append("<br><br>");
		int rank = 1;
		for (ClanAggregateHiscore.MemberValue mv : agg.top)
		{
			sb.append(rank++).append(". ")
				.append(escape(mv.rsn)).append(" &mdash; ")
				.append(mv.value).append(' ').append(unit).append("<br>");
		}
		sb.append("</html>");
		return sb.toString();
	}

	private static String prettyName(String raw)
	{
		if (raw == null)
		{
			return "";
		}
		return raw;
	}

	private static String escape(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
