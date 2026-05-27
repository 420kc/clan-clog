package com.clanclog;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import org.apache.commons.lang3.StringUtils;

/**
 * Pure static utility functions , zero state.
 * Formatting, image manipulation, clog tier logic, account helpers.
 */
final class ClogHelper
{
	static final String[] CLOG_TIERS = {
		"bronze", "iron", "steel", "black", "mithril", "adamant", "rune", "dragon", "gilded"
	};

	static final int[] CLOG_TIER_THRESHOLDS = {100, 300, 500, 700, 900, 1000, 1100, 1200};

	private ClogHelper()
	{
	}

	// -------------------------------------------------------------------------
	// Boss name -> Temple clog category key mapping
	// -------------------------------------------------------------------------

	/**
	 * Boss name -> TempleOSRS category key overrides. Ported from kcpdev's
	 * ClogService. Only entries where the boss name doesn't auto-convert
	 * cleanly to a Temple key are needed.
	 */
	private static final Map<String, String> BOSS_CATEGORY_OVERRIDES = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	static
	{
		BOSS_CATEGORY_OVERRIDES.put("Artio", "callisto_and_artio");
		BOSS_CATEGORY_OVERRIDES.put("Callisto", "callisto_and_artio");
		BOSS_CATEGORY_OVERRIDES.put("Cal'varion", "vetion_and_calvarion");
		BOSS_CATEGORY_OVERRIDES.put("Vet'ion", "vetion_and_calvarion");
		BOSS_CATEGORY_OVERRIDES.put("Venenatis", "venenatis_and_spindel");
		BOSS_CATEGORY_OVERRIDES.put("Spindel", "venenatis_and_spindel");
		BOSS_CATEGORY_OVERRIDES.put("Dagannoth Prime", "dagannoth_kings");
		BOSS_CATEGORY_OVERRIDES.put("Dagannoth Rex", "dagannoth_kings");
		BOSS_CATEGORY_OVERRIDES.put("Dagannoth Supreme", "dagannoth_kings");
		BOSS_CATEGORY_OVERRIDES.put("Kree'Arra", "kree_arra");
		BOSS_CATEGORY_OVERRIDES.put("K'ril Tsutsaroth", "kril_tsutsaroth");
		BOSS_CATEGORY_OVERRIDES.put("Chambers of Xeric: Challenge Mode", "chambers_of_xeric");
		BOSS_CATEGORY_OVERRIDES.put("Theatre of Blood: Hard Mode", "theatre_of_blood");
		BOSS_CATEGORY_OVERRIDES.put("Tombs of Amascut: Expert Mode", "tombs_of_amascut");
		BOSS_CATEGORY_OVERRIDES.put("TzTok-Jad", "the_fight_caves");
		BOSS_CATEGORY_OVERRIDES.put("TzKal-Zuk", "the_inferno");
		BOSS_CATEGORY_OVERRIDES.put("Sol Heredit", "fortis_colosseum");
		BOSS_CATEGORY_OVERRIDES.put("Nightmare", "the_nightmare");
		BOSS_CATEGORY_OVERRIDES.put("Phosani's Nightmare", "the_nightmare");
		BOSS_CATEGORY_OVERRIDES.put("The Corrupted Gauntlet", "the_gauntlet");
		BOSS_CATEGORY_OVERRIDES.put("The Hueycoatl", "hueycoatl");
		BOSS_CATEGORY_OVERRIDES.put("The Royal Titans", "royal_titans");
		BOSS_CATEGORY_OVERRIDES.put("Lunar Chests", "moons_of_peril");
	}

	/**
	 * Convert a hiscore boss name to its TempleOSRS collection log category key.
	 * Used by the tooltip builder and clog union aggregator to map boss cells
	 * to their clog categories.
	 */
	static String bossToCategory(String bossName)
	{
		String override = BOSS_CATEGORY_OVERRIDES.get(bossName);
		if (override != null)
		{
			return override;
		}
		return bossName.toLowerCase().replace("'", "")
			.replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
	}

	// -------------------------------------------------------------------------
	// Clog data helpers
	// -------------------------------------------------------------------------

	static Set<Integer> getObtainedIds(String category, ClogResult clogResult)
	{
		if (clogResult == null) return new HashSet<>();
		Set<Integer> ids = new HashSet<>();
		List<ClogResult.ClogItem> obtained = clogResult.getObtainedItems().get(category);
		if (obtained != null)
		{
			for (ClogResult.ClogItem item : obtained) ids.add(item.getId());
		}
		return ids;
	}

	static int countObtained(List<Integer> allItems, Set<Integer> obtainedIds)
	{
		int count = 0;
		for (int id : allItems) if (obtainedIds.contains(id)) count++;
		return count;
	}

	static int[] clogCounts(String category, ClogResult clogResult)
	{
		if (clogResult == null) return null;
		List<Integer> items = clogResult.getCategoryItems().get(category);
		if (items == null || items.isEmpty()) return null;
		Set<Integer> obtained = getObtainedIds(category, clogResult);
		return new int[]{countObtained(items, obtained), items.size()};
	}

	static int[] sumClogTotals(ClogResult result)
	{
		Set<Integer> allItems = new HashSet<>();
		Set<Integer> allObtained = new HashSet<>();
		for (Map.Entry<String, List<Integer>> entry : result.getCategoryItems().entrySet())
		{
			allItems.addAll(entry.getValue());
			List<ClogResult.ClogItem> obtained = result.getObtainedItems().get(entry.getKey());
			if (obtained != null)
			{
				for (ClogResult.ClogItem item : obtained) allObtained.add(item.getId());
			}
		}
		int obtained = result.getUniqueObtained() > 0
			? result.getUniqueObtained() : allObtained.size();
		int total = result.getUniqueTotal() > 0
			? result.getUniqueTotal() : allItems.size();
		return new int[]{obtained, total};
	}

	// -------------------------------------------------------------------------
	// Clog tier logic
	// -------------------------------------------------------------------------

	/** Kill Clog parity: 4-tier clog completion palette. */
	static final Color COLOR_COMPLETED = new Color(78, 240, 21);
	static final Color COLOR_MISSING_1 = new Color(202, 255, 0);
	static final Color COLOR_IN_PROGRESS = new Color(255, 173, 0);
	static final Color COLOR_EMPTY = new Color(255, 87, 0);

	static Color clogColor(int obtained, int total)
	{
		if (obtained == total) return COLOR_COMPLETED;
		if (obtained == total - 1 && total > 1) return COLOR_MISSING_1;
		if (obtained == 0) return COLOR_EMPTY;
		return COLOR_IN_PROGRESS;
	}

	static String getClogTierName(int obtained, int totalSlots)
	{
		int gildedThreshold = (int) (totalSlots * 0.9) / 25 * 25;
		if (obtained >= gildedThreshold) return "gilded";
		for (int i = CLOG_TIER_THRESHOLDS.length - 1; i >= 0; i--)
		{
			if (obtained >= CLOG_TIER_THRESHOLDS[i]) return CLOG_TIERS[i];
		}
		return null;
	}

	// -------------------------------------------------------------------------
	// Account helpers
	// -------------------------------------------------------------------------

	// GIM modicon indices in the game's modicons sprite sheet (matches IconID enum in runelite-api)
	static final int MODICON_GIM = 41;           // yellow trim
	static final int MODICON_HCGIM = 42;         // red trim
	static final int MODICON_UNRANKED_GIM = 43;  // green trim

	// Cached GIM badge images (loaded from game modicons at runtime)
	private static volatile BufferedImage gimBadge;
	private static volatile BufferedImage hcgimBadge;
	private static volatile BufferedImage unrankedGimBadge;

	static void setGimBadges(BufferedImage gim, BufferedImage hcgim, BufferedImage unrankedGim)
	{
		gimBadge = gim;
		hcgimBadge = hcgim;
		unrankedGimBadge = unrankedGim;
	}

	static BufferedImage getGimBadge(AccountType type)
	{
		if (type == AccountType.GROUP_IRONMAN) return gimBadge;
		if (type == AccountType.HARDCORE_GROUP_IRONMAN) return hcgimBadge;
		if (type == AccountType.UNRANKED_GROUP_IRONMAN) return unrankedGimBadge;
		return null;
	}

	static String accountBadgeResource(AccountType type)
	{
		switch (type)
		{
			case IRONMAN: return "ironman.png";
			case HARDCORE_IRONMAN: return "hardcore_ironman.png";
			case ULTIMATE_IRONMAN: return "ultimate_ironman.png";
			// GIM badges loaded from game modicons , use getGimBadge() instead
			default: return null;
		}
	}

	static String accountLabel(AccountType type)
	{
		switch (type)
		{
			case IRONMAN: return "Ironman";
			case HARDCORE_IRONMAN: return "Hardcore Ironman";
			case ULTIMATE_IRONMAN: return "Ultimate Ironman";
			case GROUP_IRONMAN: return "Group Ironman";
			case HARDCORE_GROUP_IRONMAN: return "Hardcore Group Ironman";
			default: return null;
		}
	}

	// -------------------------------------------------------------------------
	// Formatting
	// -------------------------------------------------------------------------

	static String pad(String text)
	{
		return StringUtils.leftPad(text, 4);
	}

	static String formatKc(int kc)
	{
		if (kc >= 1_000_000) return kc / 1_000_000 + "m";
		if (kc >= 10_000) return kc / 1_000 + "k";
		return String.valueOf(kc);
	}

	// -------------------------------------------------------------------------
	// Image utilities
	// -------------------------------------------------------------------------

	static BufferedImage iconToImage(ImageIcon icon)
	{
		if (icon == null) return null;
		BufferedImage img = new BufferedImage(
			icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		icon.paintIcon(null, g, 0, 0);
		g.dispose();
		return img;
	}

	static BufferedImage createDimmedImage(ImageIcon icon)
	{
		BufferedImage original = iconToImage(icon);
		BufferedImage dimmed = new BufferedImage(
			original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2 = dimmed.createGraphics();
		g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
		g2.drawImage(original, 0, 0, null);
		g2.dispose();
		return dimmed;
	}

	/**
	 * Boosts RGB channels by a multiplier (e.g. 1.10 = 10% brighter).
	 * Preserves hue and alpha , no white wash, just more vivid color.
	 */
	static BufferedImage createBoostedImage(ImageIcon icon, float factor)
	{
		BufferedImage src = iconToImage(icon);
		int[] pixels = src.getRGB(0, 0, src.getWidth(), src.getHeight(), null, 0, src.getWidth());
		for (int i = 0; i < pixels.length; i++)
		{
			int a = (pixels[i] >> 24) & 0xFF;
			int r = Math.min(255, (int) (((pixels[i] >> 16) & 0xFF) * factor));
			int gr = Math.min(255, (int) (((pixels[i] >> 8) & 0xFF) * factor));
			int b = Math.min(255, (int) ((pixels[i] & 0xFF) * factor));
			pixels[i] = (a << 24) | (r << 16) | (gr << 8) | b;
		}
		src.setRGB(0, 0, src.getWidth(), src.getHeight(), pixels, 0, src.getWidth());
		return src;
	}

	/** Paints a 12x10 hamburger icon , three 2px-thick horizontal lines on transparent. */
	static BufferedImage makeHamburgerIcon(Color barColor)
	{
		int w = 12, h = 10;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(barColor);
		g.fillRect(1, 0, w - 2, 2);   // top line
		g.fillRect(1, 4, w - 2, 2);   // middle line (1px gap)
		g.fillRect(1, 8, w - 2, 2);   // bottom line (1px gap)
		g.dispose();
		return img;
	}

	/** Paints a 15x15 split-color magnifying glass , left half blue, right half red. */
	static BufferedImage makeCompareIcon(Color left, Color right, float brightness)
	{
		int s = 15;
		BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
			java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setStroke(new java.awt.BasicStroke(1.5f));
		// Lens circle: center (6,5), radius 4
		int cx = 6, cy = 5, r = 4;
		// Left half (blue) , clip to left of center
		g.setClip(0, 0, cx, s);
		g.setColor(brighten(left, brightness));
		g.drawOval(cx - r, cy - r, r * 2, r * 2);
		// Right half (red) , clip to right of center
		g.setClip(cx, 0, s, s);
		g.setColor(brighten(right, brightness));
		g.drawOval(cx - r, cy - r, r * 2, r * 2);
		// Handle , no clip
		g.setClip(null);
		g.setColor(brighten(right, brightness));
		g.drawLine(cx + 3, cy + 3, cx + 6, cy + 6);
		g.dispose();
		return img;
	}

	private static Color brighten(Color c, float factor)
	{
		int r = Math.min(255, (int) (c.getRed() * factor));
		int g = Math.min(255, (int) (c.getGreen() * factor));
		int b = Math.min(255, (int) (c.getBlue() * factor));
		return new Color(r, g, b, c.getAlpha());
	}

	/** Paints a 15x15 circular refresh arrow , nearly full circle with arrowhead. */
	static BufferedImage makeRefreshIcon(Color color)
	{
		int s = 15;
		BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
			java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(color);
		g.setStroke(new java.awt.BasicStroke(1.5f));
		// Arc: nearly full circle (300 degrees), gap at top-right
		g.drawArc(2, 2, 10, 10, 30, 300);
		// Arrowhead at the end of the arc (top-right area)
		int ax = 11, ay = 3;
		g.drawLine(ax, ay, ax - 3, ay);
		g.drawLine(ax, ay, ax, ay + 3);
		g.dispose();
		return img;
	}

	static void styleSearchBar(Container container)
	{
		for (Component c : container.getComponents())
		{
			if (c instanceof JButton)
			{
				JButton btn = (JButton) c;
				btn.setOpaque(false);
				btn.setContentAreaFilled(false);
				btn.setBorderPainted(false);
			}
			else if (c instanceof Container)
			{
				styleSearchBar((Container) c);
			}
		}
	}
}
