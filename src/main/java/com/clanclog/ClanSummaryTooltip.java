package com.clanclog;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.ui.FontManager;

/**
 * Compact native row tooltip for clan aggregate summary cells.
 */
public class ClanSummaryTooltip extends TitleTooltip
{
	private static final int VALUE_GAP = 6;

	private final List<Row> rows = new ArrayList<>();

	public ClanSummaryTooltip(String title)
	{
		setTitle(title);
	}

	public void addLine(String label, String value)
	{
		addLine(label, value, Color.WHITE);
	}

	public void addLine(String label, String value, Color valueColor)
	{
		rows.add(new Row(label, value, valueColor));
	}

	@Override
	protected Dimension getContentSize(int availableWidth)
	{
		FontMetrics fm = getFontMetrics(FontManager.getRunescapeSmallFont());
		int labelWidth = 0;
		int valueWidth = 0;

		for (Row row : rows)
		{
			labelWidth = Math.max(labelWidth, fm.stringWidth(row.label));
			valueWidth = Math.max(valueWidth, fm.stringWidth(row.value));
		}

		int width = labelWidth + VALUE_GAP + valueWidth;
		int height = Math.max(LINE_HEIGHT, rows.size() * LINE_HEIGHT);
		return new Dimension(width, height);
	}

	@Override
	protected void paintBody(Graphics2D g2, int w, int h, int startY)
	{
		g2.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = g2.getFontMetrics();

		int labelWidth = 0;
		for (Row row : rows)
		{
			labelWidth = Math.max(labelWidth, fm.stringWidth(row.label));
		}

		int x = getInset();
		int valueX = x + labelWidth + VALUE_GAP;
		int y = startY + fm.getAscent();

		if (rows.isEmpty())
		{
			g2.setColor(NOTICE_COLOR);
			g2.drawString("No clan data", x, y);
			return;
		}

		for (Row row : rows)
		{
			g2.setColor(OSRS_ORANGE);
			g2.drawString(row.label, x, y);
			g2.setColor(row.valueColor);
			g2.drawString(row.value, valueX, y);
			y += LINE_HEIGHT;
		}
	}

	private static class Row
	{
		private final String label;
		private final String value;
		private final Color valueColor;

		private Row(String label, String value, Color valueColor)
		{
			this.label = label;
			this.value = value;
			this.valueColor = valueColor;
		}
	}
}
