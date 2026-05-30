package com.clanclog;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.IllegalComponentStateException;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * Sprite grid tooltip for collection log data.
 * Header (title, obtained, rank) via TitleTooltip, then auto-wrapping item grid.
 *
 * <p>Standard 32px sprites: {@code new ImgTooltip()}
 * <p>Compact 15px sprites for dense grids: {@code new ImgTooltip(5, 15)}
 */
public class ImgTooltip extends TitleTooltip
{
	private static final int DEFAULT_COLS = 5;

	private static final int DEFAULT_SPRITE_SIZE = 32;
	private static final int PADDING = 4;

	private static final Color QTY_COLOR = new Color(255, 255, 0);
	private static final Color QTY_SHADOW = new Color(0, 0, 0);
	private static final Color ITEM_HOVER_BG = new Color(80, 70, 50);
	private static final int INFO_BAR_GAP = 4;

	private final int gridCols;
	private final int spriteSize;
	private int effectiveCols;
	private int hoveredItemIndex = -1;
	private int paintedGridStartY = -1;
	private String notice = "No Collection Log Data";
	private BufferedImage noticeIcon;

	private int totalItems;
	private List<Integer> allItemIds;
	private Set<Integer> obtainedIds;
	private Map<Integer, Integer> obtainedCounts;
	private Map<Integer, List<ClanClogResult.ItemContributor>> contributors;
	private BufferedImage[] sprites;
	private ItemManager itemManager;

	/** Configurable min column count. */
	public ImgTooltip(int gridCols)
	{
		this(gridCols, DEFAULT_SPRITE_SIZE);
	}

	/** Compact mode , smaller sprites for dense grids like clue tiers. */
	public ImgTooltip(int gridCols, int spriteSize)
	{
		this.gridCols = gridCols;
		this.spriteSize = spriteSize;

		addMouseMotionListener(new MouseMotionAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				updateHoveredItem(e.getX(), e.getY());
			}
		});

		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseExited(MouseEvent e)
			{
				if (hoveredItemIndex != -1)
				{
					hoveredItemIndex = -1;
					repaint();
				}
			}
		});
	}

	@Override
	protected Font getTitleFont()
	{
		return TITLE_FONT_SMALL;
	}

	/**
	 * Set item grid data. Call after setTitle/setObtained/setRank.
	 * Holds strong references to sprites so they survive ItemManager cache eviction.
	 */
	public void setItems(int totalItems, List<Integer> allItemIds, Set<Integer> obtainedIds,
		Map<Integer, Integer> obtainedCounts, ItemManager itemManager)
	{
		this.totalItems = totalItems;
		this.allItemIds = allItemIds;
		this.obtainedIds = obtainedIds;
		this.obtainedCounts = obtainedCounts;
		this.itemManager = itemManager;
		this.hoveredItemIndex = -1;
		this.paintedGridStartY = -1;

		if (allItemIds == null || itemManager == null)
		{
			sprites = null;
			return;
		}

		sprites = new BufferedImage[allItemIds.size()];
		for (int i = 0; i < allItemIds.size(); i++)
		{
			int itemId = allItemIds.get(i);
			int count = obtainedIds != null && obtainedIds.contains(itemId)
				? obtainedCounts.getOrDefault(itemId, 1) : 1;
			BufferedImage img = itemManager.getImage(itemId, count, false);
			final int idx = i;
			if (img instanceof AsyncBufferedImage)
			{
				((AsyncBufferedImage) img).onLoaded(() ->
					SwingUtilities.invokeLater(() ->
					{
						sprites[idx] = resizeSprite(img);
						repaint();
					}));
			}
			sprites[i] = resizeSprite(img);
		}
	}

	public void setNotice(String msg)
	{
		this.notice = msg;
	}

	public void setContributors(Map<Integer, List<ClanClogResult.ItemContributor>> contributors)
	{
		this.contributors = contributors;
	}

	void syncHoveredItemFromScreenPointer()
	{
		PointerInfo pointer;
		try
		{
			pointer = MouseInfo.getPointerInfo();
		}
		catch (HeadlessException | SecurityException e)
		{
			return;
		}
		if (pointer == null)
		{
			return;
		}

		syncHoveredItemFromScreenPoint(pointer.getLocation());
	}

	void syncHoveredItemFromScreenPoint(Point screenPoint)
	{
		if (!isShowing() || screenPoint == null)
		{
			return;
		}

		Point point = new Point(screenPoint);
		try
		{
			SwingUtilities.convertPointFromScreen(point, this);
		}
		catch (IllegalComponentStateException e)
		{
			return;
		}
		updateHoveredItem(point.x, point.y);
	}

	public void setNotice(String msg, BufferedImage icon)
	{
		this.notice = msg;
		this.noticeIcon = icon;
	}

	private BufferedImage resizeSprite(BufferedImage img)
	{
		if (img == null || spriteSize >= DEFAULT_SPRITE_SIZE)
		{
			return img;
		}
		return ImageUtil.resizeImage(
			ImageUtil.resizeCanvas(img, DEFAULT_SPRITE_SIZE, DEFAULT_SPRITE_SIZE),
			spriteSize, spriteSize);
	}

	@Override
	protected Dimension getContentSize(int availableWidth)
	{
		boolean hasItems = allItemIds != null && !allItemIds.isEmpty();
		int itemCount = hasItems ? allItemIds.size() : Math.max(totalItems, 1);
		int cellSize = spriteSize + PADDING;

		// Native parity: at least gridCols wide, expand to fill header-driven width
		effectiveCols = Math.max(gridCols, (availableWidth + PADDING) / cellSize);

		int rows = (itemCount + effectiveCols - 1) / effectiveCols;
		int gridWidth = effectiveCols * cellSize - PADDING;
		int gridHeight = rows * cellSize - PADDING;

		if (!hasItems)
		{
			FontMetrics sfm = getFontMetrics(FontManager.getRunescapeSmallFont());
			int noticeWidth = sfm.stringWidth(notice);
			if (noticeIcon != null)
			{
				noticeWidth += noticeIcon.getWidth() + 3;
			}
			gridWidth = Math.max(gridWidth, noticeWidth);
		}

		if (hasItems)
		{
			FontMetrics sfm = getFontMetrics(FontManager.getRunescapeSmallFont());
			gridHeight += INFO_BAR_GAP + sfm.getHeight();
		}

		return new Dimension(gridWidth, gridHeight);
	}

	@Override
	protected void paintBody(Graphics2D g2, int w, int h, int startY)
	{
		if (getTitle() == null)
		{
			return;
		}

		int inset = getInset();
		boolean hasItems = allItemIds != null && !allItemIds.isEmpty();
		paintedGridStartY = startY;

		// No clog data , center notice in the grid area
		if (!hasItems)
		{
			g2.setFont(FontManager.getRunescapeSmallFont());
			g2.setColor(NOTICE_COLOR);
			String notice = this.notice;
			FontMetrics nfm = g2.getFontMetrics();

			int itemCount = Math.max(totalItems, 1);
			int cols = Math.min(effectiveCols, Math.max(itemCount, 1));
			int rows = (itemCount + cols - 1) / cols;
			int cellSize = spriteSize + PADDING;
			int gridHeight = rows * cellSize - PADDING;

			int totalWidth = nfm.stringWidth(notice);
			int iconW = 0;
			if (noticeIcon != null)
			{
				iconW = noticeIcon.getWidth() + 3;
				totalWidth += iconW;
			}

			int nx = inset + (w - inset * 2 - totalWidth) / 2;
			int ny = startY + (gridHeight - nfm.getHeight()) / 2 + nfm.getAscent();

			if (noticeIcon != null)
			{
				int iconY = ny - noticeIcon.getHeight() + nfm.getDescent();
				g2.drawImage(noticeIcon, nx, iconY, null);
				nx += iconW;
			}
			g2.drawString(notice, nx, ny);
			return;
		}

		// Item grid with auto-wrapped columns
		if (sprites != null)
		{
			g2.setFont(FontManager.getRunescapeSmallFont());
			int cellSize = spriteSize + PADDING;
			int gridWidth = effectiveCols * cellSize - PADDING;
			int gridOffsetX = inset + (w - 2 * inset - gridWidth) / 2;

			for (int i = 0; i < allItemIds.size(); i++)
			{
				int col = i % effectiveCols;
				int row = i / effectiveCols;
				int x = gridOffsetX + col * cellSize;
				int y = startY + row * cellSize;

				if (i == hoveredItemIndex)
				{
					g2.setColor(ITEM_HOVER_BG);
					g2.fillRect(x - 1, y - 1, spriteSize + 2, spriteSize + 2);
				}

				int itemId = allItemIds.get(i);
				boolean obtained = obtainedIds.contains(itemId);
				int count = obtained ? obtainedCounts.getOrDefault(itemId, 1) : 1;

				BufferedImage sprite = i < sprites.length ? sprites[i] : null;
				if (sprite != null)
				{
					g2.setComposite(obtained
						? AlphaComposite.SrcOver
						: AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));

					int sx = x + (spriteSize - sprite.getWidth()) / 2;
					int sy = y + (spriteSize - sprite.getHeight()) / 2;
					g2.drawImage(sprite, sx, sy, null);
					g2.setComposite(AlphaComposite.SrcOver);
				}

				// Quantity overlay , skip on compact sprites where text is unreadable
				if (obtained && count > 1 && spriteSize >= DEFAULT_SPRITE_SIZE)
				{
					FontMetrics qfm = g2.getFontMetrics();
					String qtyText = String.valueOf(count);
					g2.setColor(QTY_SHADOW);
					g2.drawString(qtyText, x + 1, y + qfm.getAscent() + 1);
					g2.setColor(QTY_COLOR);
					g2.drawString(qtyText, x, y + qfm.getAscent());
				}
			}

			paintInfoBar(g2, inset, startY, w);
		}
	}

	private void paintInfoBar(Graphics2D g2, int inset, int gridStartY, int w)
	{
		if (hoveredItemIndex < 0 || allItemIds == null
			|| hoveredItemIndex >= allItemIds.size() || itemManager == null)
		{
			return;
		}

		int cellSize = spriteSize + PADDING;
		int gridRows = (allItemIds.size() + effectiveCols - 1) / effectiveCols;
		int gridHeight = gridRows * cellSize - PADDING;
		int barY = gridStartY + gridHeight + INFO_BAR_GAP;

		int itemId = allItemIds.get(hoveredItemIndex);
		String name;
		try
		{
			name = itemManager.getItemComposition(itemId).getName();
		}
		catch (RuntimeException | AssertionError e)
		{
			return;
		}
		if (name == null || name.isEmpty() || "null".equalsIgnoreCase(name))
		{
			return;
		}

		boolean obtained = obtainedIds != null && obtainedIds.contains(itemId);

		g2.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = g2.getFontMetrics();
		String label = name;
		String contribution = contributionSummary(itemId);
		if (obtained && contribution != null)
		{
			label = name + " - " + contribution;
		}
		label = fitText(label, fm, w - 2 * inset);
		int textW = fm.stringWidth(label);
		int tx = inset + (w - 2 * inset - textW) / 2;

		g2.setColor(obtained ? Color.WHITE : NOTICE_COLOR);
		g2.drawString(label, tx, barY + fm.getAscent());
	}

	private String contributionSummary(int itemId)
	{
		if (contributors == null)
		{
			return null;
		}
		List<ClanClogResult.ItemContributor> rows = contributors.get(itemId);
		if (rows == null || rows.isEmpty())
		{
			return null;
		}

		StringBuilder text = new StringBuilder();
		int shown = 0;
		for (ClanClogResult.ItemContributor row : rows)
		{
			if (row == null || row.getRsn() == null || row.getRsn().isEmpty()
				|| row.getQuantity() <= 0)
			{
				continue;
			}
			if (shown == 3)
			{
				break;
			}
			if (text.length() > 0)
			{
				text.append(", ");
			}
			text.append(row.getRsn()).append(" x").append(row.getQuantity());
			shown++;
		}
		if (shown == 0)
		{
			return null;
		}
		int remaining = rows.size() - shown;
		if (remaining > 0)
		{
			text.append(", +").append(remaining);
		}
		return text.toString();
	}

	private static String fitText(String text, FontMetrics fm, int maxWidth)
	{
		if (fm.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		String suffix = "...";
		int suffixWidth = fm.stringWidth(suffix);
		for (int len = text.length() - 1; len > 0; len--)
		{
			String candidate = text.substring(0, len);
			if (fm.stringWidth(candidate) + suffixWidth <= maxWidth)
			{
				return candidate + suffix;
			}
		}
		return suffix;
	}

	private void updateHoveredItem(int x, int y)
	{
		int idx = getItemIndexAt(x, y);
		if (idx != hoveredItemIndex)
		{
			hoveredItemIndex = idx;
			repaint();
		}
	}

	private int getItemIndexAt(int mx, int my)
	{
		if (allItemIds == null || allItemIds.isEmpty())
		{
			return -1;
		}

		int inset = getInset();
		int w = getWidth();
		int gridStartY = paintedGridStartY >= 0
			? paintedGridStartY
			: inset + getHeaderZoneHeight();
		int cellSize = spriteSize + PADDING;
		int gridWidth = effectiveCols * cellSize - PADDING;
		int gridOffsetX = inset + (w - 2 * inset - gridWidth) / 2;

		int relX = mx - gridOffsetX;
		int relY = my - gridStartY;
		if (relX < 0 || relY < 0)
		{
			return -1;
		}

		int col = relX / cellSize;
		int row = relY / cellSize;
		if (col >= effectiveCols)
		{
			return -1;
		}
		if (relX % cellSize > spriteSize || relY % cellSize > spriteSize)
		{
			return -1;
		}

		int idx = row * effectiveCols + col;
		return idx < allItemIds.size() ? idx : -1;
	}
}
