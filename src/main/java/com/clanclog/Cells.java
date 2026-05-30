/*
 * Copyright (c) 2026, 420 kc <dyl@420kc.dev>
 * Owns the cells of the clan clog panel: builds them, holds the labels,
 * caches tooltip + icon data, renders clan-aggregate kc/clog values from a
 * ClanClogResult, and routes single-flavor sprite tooltips. ClanClogPanel
 * (layout) and ClanLookupSession (data) feed it.
 */
package com.clanclog;

import java.awt.Color;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToolTip;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * Clan-flavored sibling of kill clog's Cells. Same cell shape (sprite-icon
 * grid cells with rich ImgTooltip rendering), different data source:
 * {@link ClanClogResult#getBosses()} (clan_total_kc + top3) and
 * {@link ClanClogResult.ClogUnion} (per-item holder counts + first-seen
 * history).
 *
 * <p>Architectural differences from kcpdev's Cells (refactor-cells-collapse,
 * 741 LOC, single-player flavored):
 * <ul>
 *   <li>String-name keyed label maps (matches clan-clog's HiscoreService.bossNames()
 *       architecture; HiscoreSkill enum is used only as a sprite-id lookup
 *       helper, never as a label-map key).</li>
 *   <li>Single-flavor tooltips only. No ComparisonController -- clan has no
 *       1v1 mode. No FourTwentyMode -- kill-clog easter egg, not clan.</li>
 *   <li>Summary cells read clan aggregate activity totals and clog union
 *       category counts; no per-player activity cells or 1v1 compare mode.</li>
 *   <li>No SinglePlayerTooltipBuilder injection -- clan UI is global, no
 *       sync-notice text reaches in from the panel.</li>
 *   <li>Renderers read backend aggregate (ClanClogResult.bosses[name].clanTotalKc
 *       + clog.itemsByCategory + clog.itemMeta) instead of per-player
 *       HiscoreResult + ClogResult.</li>
 * </ul>
 *
 * <p>Per project_clan_hiscores_plugin.md sub-phase 3b.3.4: the visible
 * clan-aggregate surface for boss kc and clog category coverage. The text-only
 * {@link ClanAggregateGrid} stays in the panel as the roster-side supplement
 * (skills + activities + bosses from per-member HiscoreResults via
 * {@link ClanAggregateHiscore}); the two surfaces co-exist until 3b.3.5+
 * decides their relationship.
 */
@Singleton
public class Cells
{
	// ── Inlined constants (clan-clog has no PanelData; keep them here) ────────

	private static final int[] CLUE_TIER_ITEM_IDS = {23182, 2677, 2801, 2722, 12073, 19835};
	private static final String[] CLUE_TIER_NAMES = {
		"Clue Scrolls (beginner)", "Clue Scrolls (easy)", "Clue Scrolls (medium)",
		"Clue Scrolls (hard)", "Clue Scrolls (elite)", "Clue Scrolls (master)"
	};

	/** Clue tier name -> Temple clog category (matches backend item_by_category keys). */
	private static final Map<String, String> CLUE_CATEGORIES = new LinkedHashMap<>();
	static
	{
		CLUE_CATEGORIES.put("Clue Scrolls (beginner)", "beginner_treasure_trails");
		CLUE_CATEGORIES.put("Clue Scrolls (easy)",     "easy_treasure_trails");
		CLUE_CATEGORIES.put("Clue Scrolls (medium)",   "medium_treasure_trails");
		CLUE_CATEGORIES.put("Clue Scrolls (hard)",     "hard_treasure_trails");
		CLUE_CATEGORIES.put("Clue Scrolls (elite)",    "elite_treasure_trails");
		CLUE_CATEGORIES.put("Clue Scrolls (master)",   "master_treasure_trails");
	}

	private static final String CLOG_THIRD_AGE = "third_age";
	private static final String CLOG_GILDED = "gilded";
	private static final int THIRD_AGE_ITEM_ID = 10348;
	private static final int GILDED_ITEM_ID = 3481;
	private static final int THIRD_AGE_RING_ITEM_ID = 23185;
	private static final int[] THIRD_AGE_ITEMS = {
		10350, 10348, 10346, 23242, 10352,
		10334, 10330, 10332, 10336,
		10342, 10338, 10340, 10344,
		12426, 12422, 12437, 12424,
		23336, 23339, 23345, 23342,
		20014, 20011, THIRD_AGE_RING_ITEM_ID
	};
	private static final int[] GILDED_ITEMS = {
		3486, 3481, 3483, 3485, 3488,
		20146, 20149, 20152, 20155, 20158, 20161,
		12389, 12391, 23258, 23261, 23264, 23267,
		23276, 23279, 23282
	};

	private static final String RARE_HARD = "hard_rare";
	private static final String RARE_ELITE = "elite_rare";
	private static final String RARE_MASTER = "master_rare";

	/** Casket item IDs used as cell icons for the rare rows (Kill Clog parity). */
	private static final int HARD_CASKET_ITEM_ID = 20544;
	private static final int ELITE_CASKET_ITEM_ID = 20543;
	private static final int MASTER_CASKET_ITEM_ID = 19836;

	private static final int[] HARD_RARE_ITEMS = {
		// 3rd age melee + range + mage + amulet (13)
		10350, 10348, 10346, 23242, 10352,
		10334, 10330, 10332, 10336,
		10342, 10338, 10340, 10344,
		// Gilded melee (11)
		3486, 3481, 3483, 3485, 3488,
		20146, 20149, 20152, 20155, 20158, 20161
	};

	private static final int[] ELITE_RARE_ITEMS = {
		// 3rd age melee + range + mage + amulet + weapons + cloak (17)
		10350, 10348, 10346, 23242, 10352,
		10334, 10330, 10332, 10336,
		10342, 10338, 10340, 10344,
		12426, 12422, 12437, 12424,
		// All gilded (20)
		3486, 3481, 3483, 3485, 3488,
		20146, 20149, 20152, 20155, 20158, 20161,
		12389, 12391, 23258, 23261, 23264, 23267,
		23276, 23279, 23282,
		// Lava dragon mask, Ring of nature
		12371, 20005
	};

	private static final int[] MASTER_RARE_ITEMS = {
		// All 3rd age (23)
		10350, 10348, 10346, 23242, 10352,
		10334, 10330, 10332, 10336,
		10342, 10338, 10340, 10344,
		12426, 12422, 12437, 12424,
		23336, 23339, 23345, 23342,
		20014, 20011,
		// All gilded (20)
		3486, 3481, 3483, 3485, 3488,
		20146, 20149, 20152, 20155, 20158, 20161,
		12389, 12391, 23258, 23261, 23264, 23267,
		23276, 23279, 23282,
		// Bucket helm (g), Ring of coins
		20059, 20017
	};

	/**
	 * Hiscore-CSV boss name -> HiscoreSkill.getName() bridge. Only entries
	 * where the two differ are needed; everything else matches by value.
	 * HiscoreService.bossNames()[i] is the CSV form; HiscoreSkill.getName()
	 * is the RuneLite enum form (used for sprite-id resolution).
	 */
	private static final Map<String, String> BOSS_NAME_TO_HISCORE_SKILL_NAME = new LinkedHashMap<>();
	static
	{
		BOSS_NAME_TO_HISCORE_SKILL_NAME.put("Cal'varion", "Calvar'ion");
	}

	/** Standard kc text color (light gray-white) used for any cell that has a non-zero value. */
	static final Color KC_COLOR = new Color(215, 215, 215);

	/**
	 * Boss display order matching Kill Clog's PanelData.BOSSES layout exactly.
	 * This is NOT the hiscore CSV alphabetical order -- it follows the RuneLite
	 * panel convention where "The Hueycoatl" and "The Royal Titans" are placed
	 * near their thematic neighbors instead of at the end of the "The" block.
	 */
	private static final String[] BOSS_DISPLAY_ORDER = {
		"Abyssal Sire", "Alchemical Hydra", "Amoxliatl", "Araxxor",
		"Artio", "Barrows Chests", "Brutus", "Bryophyta", "Callisto",
		"Cal'varion", "Cerberus", "Chambers of Xeric",
		"Chambers of Xeric: Challenge Mode", "Chaos Elemental", "Chaos Fanatic",
		"Commander Zilyana", "Corporeal Beast", "Crazy Archaeologist",
		"Dagannoth Prime", "Dagannoth Rex", "Dagannoth Supreme",
		"Deranged Archaeologist", "Doom of Mokhaiotl", "Duke Sucellus",
		"General Graardor", "Giant Mole", "Grotesque Guardians", "Hespori",
		"The Hueycoatl", "Kalphite Queen", "King Black Dragon", "Kraken",
		"Kree'Arra", "K'ril Tsutsaroth", "Lunar Chests", "Mimic", "Nex",
		"Nightmare", "Phosani's Nightmare", "Obor",
		"Phantom Muspah", "The Royal Titans", "Sarachnis", "Scorpia", "Scurrius",
		"Shellbane Gryphon", "Skotizo", "Sol Heredit", "Spindel", "Tempoross",
		"The Gauntlet", "The Corrupted Gauntlet",
		"The Leviathan", "The Whisperer",
		"Theatre of Blood", "Theatre of Blood: Hard Mode",
		"Thermonuclear Smoke Devil", "Tombs of Amascut",
		"Tombs of Amascut: Expert Mode", "TzKal-Zuk", "TzTok-Jad",
		"Vardorvis", "Venenatis", "Vet'ion", "Vorkath", "Wintertodt",
		"Yama", "Zalcano", "Zulrah"
	};

	private static final int BOSS_GRID_COLS = 3;
	private static final int CLUE_GRID_COLS = 3;
	private static final HiscoreSkill[] CLUE_SUMMARY_TIERS = {
		HiscoreSkill.CLUE_SCROLL_ALL,
		HiscoreSkill.CLUE_SCROLL_BEGINNER,
		HiscoreSkill.CLUE_SCROLL_EASY,
		HiscoreSkill.CLUE_SCROLL_MEDIUM,
		HiscoreSkill.CLUE_SCROLL_HARD,
		HiscoreSkill.CLUE_SCROLL_ELITE,
		HiscoreSkill.CLUE_SCROLL_MASTER,
	};
	private static final HiscoreSkill[] PVP_ACTIVITIES = {
		HiscoreSkill.LAST_MAN_STANDING,
		HiscoreSkill.SOUL_WARS_ZEAL,
		HiscoreSkill.PVP_ARENA_RANK,
		HiscoreSkill.BOUNTY_HUNTER_HUNTER,
		HiscoreSkill.BOUNTY_HUNTER_ROGUE,
	};

	// ── Deps ──────────────────────────────────────────────────────────────────

	private final SpriteManager spriteManager;
	private final ItemManager itemManager;
	private final TooltipController tooltipController;
	private final ClanTooltipDataBuilder tooltipDataBuilder;

	// ── Tooltip data caches ───────────────────────────────────────────────────

	private final Map<String, TooltipData> tooltipDataMap = new LinkedHashMap<>();
	private final Map<String, TooltipData> rareTooltips = new LinkedHashMap<>();

	// ── Icon caches ───────────────────────────────────────────────────────────

	private final BufferedImage[] clueIcons = new BufferedImage[8];
	private final BufferedImage[] pvpActivityIcons = new BufferedImage[5];

	// ── Cell labels (String-keyed) ────────────────────────────────────────────

	private final Map<String, JLabel> bossLabels = new LinkedHashMap<>();
	private final Map<String, JLabel> clueTierLabels = new LinkedHashMap<>();
	private final Map<String, ImageIcon> originalIcons = new LinkedHashMap<>();
	private final Map<String, ImageIcon> dimmedIcons = new LinkedHashMap<>();
	@Nullable private JLabel thirdAgeCell;
	@Nullable private JLabel gildedCell;
	@Nullable private JLabel hardRare;
	@Nullable private JLabel eliteRare;
	@Nullable private JLabel masterRare;
	@Nullable private JLabel totalCluesCell;
	@Nullable private JLabel membersCell;
	@Nullable private JLabel totalKillsCell;
	@Nullable private JLabel pvpSummaryCell;
	@Nullable private ClanClogResult summaryResult;

	@Inject
	public Cells(SpriteManager spriteManager, ItemManager itemManager,
		TooltipController tooltipController, ClanTooltipDataBuilder tooltipDataBuilder)
	{
		this.spriteManager = spriteManager;
		this.itemManager = itemManager;
		this.tooltipController = tooltipController;
		this.tooltipDataBuilder = tooltipDataBuilder;
	}

	// ── Cell builders ─────────────────────────────────────────────────────────

	/** Build the boss grid populated with one sprite-icon cell per boss.
	 *  Uses BOSS_DISPLAY_ORDER (Kill Clog parity) instead of hiscore CSV order. */
	public JPanel buildBossGrid()
	{
		JPanel grid = new JPanel(new GridLayout(0, BOSS_GRID_COLS));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		for (String boss : BOSS_DISPLAY_ORDER)
		{
			grid.add(buildBossCell(boss));
		}
		return grid;
	}

	/** Build a single boss cell. Caller hooks listeners onto {@link #getBossLabel} post-construction if needed. */
	public JPanel buildBossCell(String boss)
	{
		JLabel label = new JLabel()
		{
			@Override
			public JToolTip createToolTip()
			{
				return buildBossTooltip(this, boss);
			}
		};
		styleLabel(label, boss);

		int spriteId = bossSpriteId(boss);
		if (spriteId != -1)
		{
			spriteManager.getSpriteAsync(spriteId, 0, sprite ->
				SwingUtilities.invokeLater(() ->
				{
					if (sprite == null)
					{
						return;
					}
					BufferedImage scaled = ImageUtil.resizeImage(
						ImageUtil.resizeCanvas(sprite, 25, 25), 20, 20);
					ImageIcon icon = new ImageIcon(scaled);
					label.setIcon(icon);
					originalIcons.put(boss, icon);
					dimmedIcons.put(boss, new ImageIcon(ClogHelper.createDimmedImage(icon)));
				}));
		}

		bossLabels.put(boss, label);
		return wrapInCell(label);
	}

	/**
	 * Build the clue tier grid: 2 rows of 3 (beginner -> master).
	 * Compact flag per tier matches Kill Clog: Easy/Medium/Hard compact,
	 * Beginner/Elite/Master full-width tooltips.
	 */
	public JPanel buildClueTierGrid()
	{
		JPanel grid = new JPanel();
		grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Row 1: [Beginner] [Easy*] [Medium*]  (* = compact tooltip)
		JPanel row1 = new JPanel(new GridLayout(1, CLUE_GRID_COLS));
		row1.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row1.setAlignmentX(0f);
		row1.add(buildClueTierCell(CLUE_TIER_NAMES[0], CLUE_TIER_ITEM_IDS[0], false));
		row1.add(buildClueTierCell(CLUE_TIER_NAMES[1], CLUE_TIER_ITEM_IDS[1], true));
		row1.add(buildClueTierCell(CLUE_TIER_NAMES[2], CLUE_TIER_ITEM_IDS[2], true));
		grid.add(row1);

		// Row 2: [Hard*] [Elite] [Master]
		JPanel row2 = new JPanel(new GridLayout(1, CLUE_GRID_COLS));
		row2.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row2.setAlignmentX(0f);
		row2.add(buildClueTierCell(CLUE_TIER_NAMES[3], CLUE_TIER_ITEM_IDS[3], true));
		row2.add(buildClueTierCell(CLUE_TIER_NAMES[4], CLUE_TIER_ITEM_IDS[4], false));
		row2.add(buildClueTierCell(CLUE_TIER_NAMES[5], CLUE_TIER_ITEM_IDS[5], false));
		grid.add(row2);

		return grid;
	}

	public JPanel buildClueTierCell(String tier, int itemId, boolean compact)
	{
		String displayName = capitalizeTier(tier);
		JLabel label = new JLabel()
		{
			@Override
			public JToolTip createToolTip()
			{
				return buildClueTierTooltip(this, tier, displayName, compact);
			}
		};
		styleLabel(label, tier);
		setItemIcon(label, itemId);
		clueTierLabels.put(tier, label);
		return wrapInCell(label);
	}

	public JPanel buildClueRareCell(String name, int itemId, String clogCategory, boolean isThirdAge)
	{
		JLabel label = new JLabel()
		{
			@Override
			public JToolTip createToolTip()
			{
				return buildClueRareTooltip(this, name, clogCategory);
			}
		};
		styleLabel(label, name);
		setItemIcon(label, itemId);

		if (isThirdAge)
		{
			thirdAgeCell = label;
		}
		else
		{
			gildedCell = label;
		}

		return wrapInCell(label);
	}

	public JPanel buildCustomRareCell(String name, int iconItemId, String rareKey, int[] itemIds)
	{
		JLabel label = new JLabel()
		{
			@Override
			public JToolTip createToolTip()
			{
				return buildCustomRareTooltip(this, name, rareKey, itemIds);
			}
		};
		styleLabel(label, name);
		setItemIcon(label, iconItemId);

		if (RARE_HARD.equals(rareKey))
		{
			hardRare = label;
		}
		else if (RARE_ELITE.equals(rareKey))
		{
			eliteRare = label;
		}
		else if (RARE_MASTER.equals(rareKey))
		{
			masterRare = label;
		}

		return wrapInCell(label);
	}

	/** Convenience: build the "3rd Age" cell with the canonical item icon + category. */
	public JPanel buildThirdAgeCell()
	{
		return buildClueRareCell("3rd Age", THIRD_AGE_ITEM_ID, CLOG_THIRD_AGE, true);
	}

	/** Convenience: build the "Gilded" cell with the canonical item icon + category. */
	public JPanel buildGildedCell()
	{
		return buildClueRareCell("Gilded", GILDED_ITEM_ID, CLOG_GILDED, false);
	}

	/** Convenience: build the Hard / Elite / Master custom rare cells with canonical icons + item lists. */
	public JPanel buildHardRareCell()
	{
		return buildCustomRareCell("Hard Treasure (Rare)", HARD_CASKET_ITEM_ID, RARE_HARD, HARD_RARE_ITEMS);
	}

	public JPanel buildEliteRareCell()
	{
		return buildCustomRareCell("Elite Treasure (Rare)", ELITE_CASKET_ITEM_ID, RARE_ELITE, ELITE_RARE_ITEMS);
	}

	public JPanel buildMasterRareCell()
	{
		return buildCustomRareCell("Master Treasure (Rare)", MASTER_CASKET_ITEM_ID, RARE_MASTER, MASTER_RARE_ITEMS);
	}

	/**
	 * Total Clues cell: aggregate clue scroll completions across all clan members.
	 * Shows "Clue Scrolls (all)" summed. Kill Clog parity: sits between 3rd Age
	 * and Gilded in the top row.
	 */
	public JPanel buildTotalCluesCell()
	{
		JLabel label = new JLabel()
		{
			@Override
			public JToolTip createToolTip()
			{
				return buildClueSummaryTooltip();
			}
		};
		styleLabel(label, "Total Clues");

		// Clue scroll (all) sprite via HiscoreSkill
		for (HiscoreSkill hs : HiscoreSkill.values())
		{
			if ("Clue Scrolls (all)".equals(hs.getName()))
			{
				spriteManager.getSpriteAsync(hs.getSpriteId(), 0, sprite ->
					SwingUtilities.invokeLater(() ->
					{
						if (sprite != null)
						{
							label.setIcon(new ImageIcon(ImageUtil.resizeImage(
								ImageUtil.resizeCanvas(sprite, 25, 25), 20, 20)));
						}
					}));
				break;
			}
		}

		loadClueSummaryIcons();
		totalCluesCell = label;
		return wrapInCell(label);
	}

	/**
	 * Members cell: shows the clan member count. Occupies the position where
	 * Kill Clog's combat level / PvM summary sits. Sprite is the clan icon.
	 */
	public JPanel buildMembersCell()
	{
		JLabel label = new JLabel()
		{
			@Override
			public JToolTip createToolTip()
			{
				return buildClanSummaryTooltip();
			}
		};
		styleLabel(label, "Members");

		// Clan icon from bundled resource (sprite 647 doesn't resolve via getSpriteAsync)
		try (InputStream in = Cells.class.getResourceAsStream("icon.png"))
		{
			if (in != null)
			{
				BufferedImage img = ImageIO.read(in);
				if (img != null)
				{
					label.setIcon(new ImageIcon(ImageUtil.resizeImage(img, 20, 20)));
				}
			}
		}
		catch (Exception ignored)
		{
		}

		membersCell = label;
		return wrapInCell(label);
	}

	/**
	 * Total Kills cell: sum of every boss KC across all clan members.
	 * Slayer icon (the PvM skill). Tooltip shows top bosses by clan kc.
	 */
	public JPanel buildTotalKillsCell()
	{
		JLabel label = new JLabel()
		{
			@Override
			public JToolTip createToolTip()
			{
				return buildTotalKillsTooltip();
			}
		};
		styleLabel(label, "Total Kills");

		// Slayer skill sprite
		for (HiscoreSkill hs : HiscoreSkill.values())
		{
			if ("Slayer".equals(hs.getName()))
			{
				spriteManager.getSpriteAsync(hs.getSpriteId(), 0, sprite ->
					SwingUtilities.invokeLater(() ->
					{
						if (sprite != null)
						{
							label.setIcon(new ImageIcon(ImageUtil.resizeImage(
								ImageUtil.resizeCanvas(sprite, 25, 25), 20, 20)));
						}
					}));
				break;
			}
		}

		totalKillsCell = label;
		return wrapInCell(label);
	}

	/**
	 * PvP Summary cell: aggregate PvP activity scores across clan members.
	 * Shows BH Hunter + BH Rogue total (Kill Clog parity). Tooltip breaks
	 * down LMS, Soul Wars, PvP Arena, BH per-activity.
	 */
	public JPanel buildPvpSummaryCell()
	{
		JLabel label = new JLabel()
		{
			@Override
			public JToolTip createToolTip()
			{
				return buildPvpSummaryTooltip();
			}
		};
		styleLabel(label, "PvP Summary");

		// PvP skull sprite (sprite 439, same as Kill Clog)
		// Resize to 16x16 first then pad canvas to 20x20 (Kill Clog parity)
		spriteManager.getSpriteAsync(439, 0, sprite ->
			SwingUtilities.invokeLater(() ->
			{
				if (sprite != null)
				{
					BufferedImage padded = ImageUtil.resizeCanvas(
						ImageUtil.resizeImage(sprite, 16, 16), 20, 20);
					label.setIcon(new ImageIcon(padded));
				}
			}));

		for (int i = 0; i < PVP_ACTIVITIES.length; i++)
		{
			int spriteId = PVP_ACTIVITIES[i].getSpriteId();
			if (spriteId == -1)
			{
				continue;
			}
			final int idx = i;
			spriteManager.getSpriteAsync(spriteId, 0, sprite ->
				SwingUtilities.invokeLater(() ->
				{
					if (sprite != null)
					{
						pvpActivityIcons[idx] = ImageUtil.resizeImage(
							ImageUtil.resizeCanvas(sprite, 25, 25), 13, 13);
					}
				}));
		}

		pvpSummaryCell = label;
		return wrapInCell(label);
	}

	public void installCoverageIcons(JLabel hiscoreLabel, JLabel clogLabel)
	{
		setHiscoreSkillIcon(hiscoreLabel, HiscoreSkill.OVERALL);
		setHiscoreSkillIcon(clogLabel, HiscoreSkill.COLLECTIONS_LOGGED);
	}

	// ── Clan-result rendering ─────────────────────────────────────────────────

	/**
	 * Apply a clan result to every cell: boss cells from result.bosses (clan_total_kc),
	 * clue tier cells from result.clog.itemsByCategory, rare cells from result.clog.
	 * Also rebuilds the per-cell TooltipData cache for the rich-sprite tooltip path.
	 */
	public void renderClanResult(ClanClogResult result)
	{
		if (result == null)
		{
			clearCells();
			return;
		}
		summaryResult = result;
		renderBosses(result);
		renderClueTiers(result);
		renderRares(result);
		renderSummaryCells(result);
		rebuildTooltips(result);
		applyHighlightColors(result);
	}

	/** Reset every cell to its placeholder state. Called when a lookup fails / returns nothing. */
	public void clearCells()
	{
		summaryResult = null;
		for (Map.Entry<String, JLabel> e : bossLabels.entrySet())
		{
			JLabel label = e.getValue();
			label.setText(ClogHelper.pad("--"));
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			ImageIcon dimmed = dimmedIcons.get(e.getKey());
			if (dimmed != null)
			{
				label.setIcon(dimmed);
			}
			label.setToolTipText(e.getKey());
		}
		for (Map.Entry<String, JLabel> e : clueTierLabels.entrySet())
		{
			JLabel label = e.getValue();
			label.setText(ClogHelper.pad("--"));
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			label.setToolTipText(e.getKey());
		}
		resetRare(thirdAgeCell, "3rd Age");
		resetRare(gildedCell, "Gilded");
		resetRare(hardRare, "Hard Treasure (Rare)");
		resetRare(eliteRare, "Elite Treasure (Rare)");
		resetRare(masterRare, "Master Treasure (Rare)");
		resetSummaryCell(totalCluesCell, "Total Clues");
		resetSummaryCell(membersCell, "Members");
		resetSummaryCell(totalKillsCell, "Total Kills");
		resetSummaryCell(pvpSummaryCell, "PvP Summary");
		tooltipDataMap.clear();
		rareTooltips.clear();
	}

	private static void resetRare(@Nullable JLabel label, String name)
	{
		if (label == null)
		{
			return;
		}
		label.setText(ClogHelper.pad("--"));
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setToolTipText(name);
	}

	private static void resetSummaryCell(@Nullable JLabel label, String name)
	{
		if (label == null)
		{
			return;
		}
		label.setText(ClogHelper.pad("--"));
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setToolTipText(name);
	}

	private void renderBosses(ClanClogResult result)
	{
		Map<String, ClanClogResult.BossAggregate> bosses = result.getBosses();
		for (Map.Entry<String, JLabel> entry : bossLabels.entrySet())
		{
			String boss = entry.getKey();
			JLabel label = entry.getValue();
			ClanClogResult.BossAggregate agg = bosses.get(boss);
			long kc = agg != null ? agg.getClanTotalKc() : 0L;
			boolean hasKc = kc > 0;

			int displayKc = kc > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) kc;
			label.setText(ClogHelper.pad(hasKc ? ClogHelper.formatKc(displayKc) : "--"));
			label.setForeground(hasKc ? KC_COLOR : ColorScheme.LIGHT_GRAY_COLOR);

			ImageIcon orig = originalIcons.get(boss);
			if (orig != null)
			{
				label.setIcon(hasKc ? orig : dimmedIcons.get(boss));
			}
		}
	}

	private void renderClueTiers(ClanClogResult result)
	{
		Map<String, Long> activities = result.getActivityTotals();

		for (Map.Entry<String, JLabel> entry : clueTierLabels.entrySet())
		{
			String tier = entry.getKey();
			JLabel label = entry.getValue();
			long score = activities.getOrDefault(tier, 0L);
			boolean has = score > 0;
			int display = score > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) score;
			label.setText(ClogHelper.pad(has ? ClogHelper.formatKc(display) : "--"));
			label.setForeground(has ? KC_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		}
	}

	private void renderRares(ClanClogResult result)
	{
		writeCustomRare(thirdAgeCell, "3rd Age", CLOG_THIRD_AGE, THIRD_AGE_ITEMS, result);
		writeCustomRare(gildedCell, "Gilded", CLOG_GILDED, GILDED_ITEMS, result);
		writeCustomRare(hardRare, "Hard Treasure (Rare)", RARE_HARD, HARD_RARE_ITEMS, result);
		writeCustomRare(eliteRare, "Elite Treasure (Rare)", RARE_ELITE, ELITE_RARE_ITEMS, result);
		writeCustomRare(masterRare, "Master Treasure (Rare)", RARE_MASTER, MASTER_RARE_ITEMS, result);
	}

	private void renderSummaryCells(ClanClogResult result)
	{
		Map<String, Long> activities = result.getActivityTotals();

		// Total Clues: sum of "Clue Scrolls (all)" across members
		if (totalCluesCell != null)
		{
			long allClues = activities.getOrDefault("Clue Scrolls (all)", 0L);
			boolean has = allClues > 0;
			int display = allClues > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) allClues;
			totalCluesCell.setText(ClogHelper.pad(has ? ClogHelper.formatKc(display) : "--"));
			totalCluesCell.setForeground(has ? KC_COLOR : ColorScheme.LIGHT_GRAY_COLOR);

			totalCluesCell.setToolTipText(" ");
		}

		// Members: member count
		if (membersCell != null)
		{
			int count = result.getMemberCount();
			membersCell.setText(ClogHelper.pad(count > 0 ? String.format("%,d", count) : "--"));
			membersCell.setForeground(count > 0 ? KC_COLOR : ColorScheme.LIGHT_GRAY_COLOR);

			membersCell.setToolTipText(" ");
		}

		// Total Kills: sum all boss KCs across the clan
		if (totalKillsCell != null)
		{
			long totalKills = 0;
			for (Map.Entry<String, ClanClogResult.BossAggregate> e : result.getBosses().entrySet())
			{
				long kc = e.getValue().getClanTotalKc();
				if (kc > 0)
				{
					totalKills += kc;
				}
			}
			boolean has = totalKills > 0;
			totalKillsCell.setText(ClogHelper.pad(has ? ClogHelper.formatKc(
				totalKills > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalKills) : "--"));
			totalKillsCell.setForeground(has ? KC_COLOR : ColorScheme.LIGHT_GRAY_COLOR);

			totalKillsCell.setToolTipText(" ");
		}

		// PvP Summary: BH Hunter + BH Rogue (cell text, same as Kill Clog)
		if (pvpSummaryCell != null)
		{
			long bhHunter = activities.getOrDefault("Bounty Hunter - Hunter", 0L);
			long bhRogue = activities.getOrDefault("Bounty Hunter - Rogue", 0L);
			long bhTotal = bhHunter + bhRogue;
			boolean has = bhTotal > 0;
			int display = bhTotal > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bhTotal;
			pvpSummaryCell.setText(ClogHelper.pad(has ? ClogHelper.formatKc(display) : "--"));
			pvpSummaryCell.setForeground(has ? KC_COLOR : ColorScheme.LIGHT_GRAY_COLOR);

			pvpSummaryCell.setToolTipText(" ");
		}
	}

	private JToolTip buildClueSummaryTooltip()
	{
		ClueSummaryTooltip tip = new ClueSummaryTooltip();
		tip.setIcons(clueIcons);
		ClanClogResult result = summaryResult;
		if (result == null)
		{
			return tip;
		}

		ClanClogResult.BossAggregate mimic = result.getBosses().get("Mimic");
		long mimicKc = mimic != null ? mimic.getClanTotalKc() : 0L;
		tip.setClanData(result.getActivityTotals(), mimicKc);
		return tip;
	}

	private JToolTip buildClanSummaryTooltip()
	{
		ClanSummaryTooltip tip = new ClanSummaryTooltip("Clan Summary");
		ClanClogResult result = summaryResult;
		if (result == null)
		{
			return tip;
		}

		long totalBossKc = 0L;
		int bossesWithKc = 0;
		for (ClanClogResult.BossAggregate agg : result.getBosses().values())
		{
			if (agg.getClanTotalKc() > 0)
			{
				totalBossKc += agg.getClanTotalKc();
				bossesWithKc++;
			}
		}

		tip.addLine("Members: ", formatTooltipCount(result.getMemberCount()),
			tooltipValueColor(result.getMemberCount()));
		addOptionalLine(tip, "Source: ", prettyToken(result.getSourceTier()));
		addOptionalLine(tip, "Status: ", prettyToken(result.getBuildStatus()),
			statusColor(result.getBuildStatus()));
		addOptionalLine(tip, "Last sync: ", dateText(result.getLastSyncedAt()));

		ClanClogResult.MemberCoverage coverage = result.getMemberCoverage();
		if (coverage != null && coverage.getTotal() > 0)
		{
			tip.addLine("Kill Clog: ",
				coverage.getClogOk() + "/" + coverage.getTotal(),
				coverageColor(coverage.getClogOk(), coverage.getTotal()));
			tip.addLine("Hiscores: ",
				coverage.getHiscoreRepresented() + "/" + coverage.getTotal(),
				coverageColor(coverage.getHiscoreRepresented(), coverage.getTotal()));
			addPositiveLine(tip, "No data: ", coverage.getNotFound());
			addPositiveLine(tip, "Opted out: ", coverage.getOptedOut());
			addPositiveLine(tip, "Errors: ", coverage.getError(), ClogHelper.COLOR_EMPTY);
		}

		tip.addLine("Total Boss KC: ", formatTooltipCount(totalBossKc),
			tooltipValueColor(totalBossKc));
		tip.addLine("Bosses killed: ", bossesWithKc + "/" + BOSS_DISPLAY_ORDER.length,
			tooltipValueColor(bossesWithKc));

		ClanClogResult.ClogUnion clog = result.getClog();
		if (clog != null)
		{
			tip.addLine("Clog slots: ", formatTooltipCount(clog.getTotalObtained()),
				tooltipValueColor(clog.getTotalObtained()));
		}
		return tip;
	}

	private JToolTip buildTotalKillsTooltip()
	{
		PvmSummaryTooltip tip = new PvmSummaryTooltip();
		ClanClogResult result = summaryResult;
		if (result == null)
		{
			return tip;
		}

		long totalKills = 0L;
		String topBoss = null;
		long topKc = 0L;
		int bossesWithKc = 0;
		for (Map.Entry<String, ClanClogResult.BossAggregate> entry : result.getBosses().entrySet())
		{
			long kc = entry.getValue().getClanTotalKc();
			if (kc <= 0)
			{
				continue;
			}
			totalKills += kc;
			bossesWithKc++;
			if (kc > topKc)
			{
				topKc = kc;
				topBoss = entry.getKey();
			}
		}

		tip.setClanData(totalKills, bossesWithKc, BOSS_DISPLAY_ORDER.length, topBoss, topKc);
		int[] bossCounts = aggregateBossTooltipCounts();
		if (bossCounts != null)
		{
			tip.setCompletion(bossCounts[0], bossCounts[1]);
		}
		tip.setClanRaids(result.getBosses(), result.getClog());
		tip.setClanMegarares(result.getClog(), itemManager);
		return tip;
	}

	private JToolTip buildPvpSummaryTooltip()
	{
		PvpSummaryTooltip tip = new PvpSummaryTooltip();
		tip.setIcons(pvpActivityIcons);
		ClanClogResult result = summaryResult;
		if (result == null)
		{
			tip.setNotice("No clan data");
			return tip;
		}

		tip.setClanData(result.getActivityTotals(), result.getClog());
		return tip;
	}

	private static void addActivityLine(ClanSummaryTooltip tip, String label,
		Map<String, Long> activities, String key)
	{
		long count = activities.getOrDefault(key, 0L);
		tip.addLine(label, formatTooltipCount(count), tooltipValueColor(count));
	}

	private static void addOptionalLine(ClanSummaryTooltip tip, String label, String value)
	{
		addOptionalLine(tip, label, value, Color.WHITE);
	}

	private static void addOptionalLine(ClanSummaryTooltip tip, String label,
		String value, Color valueColor)
	{
		if (value == null || value.isBlank())
		{
			return;
		}
		tip.addLine(label, value, valueColor);
	}

	private static void addPositiveLine(ClanSummaryTooltip tip, String label, int count)
	{
		addPositiveLine(tip, label, count, ClogHelper.COLOR_IN_PROGRESS);
	}

	private static void addPositiveLine(ClanSummaryTooltip tip, String label,
		int count, Color valueColor)
	{
		if (count > 0)
		{
			tip.addLine(label, String.format("%,d", count), valueColor);
		}
	}

	private static String formatTooltipCount(long count)
	{
		return count > 0 ? String.format("%,d", count) : "--";
	}

	private static Color tooltipValueColor(long count)
	{
		return count > 0 ? Color.WHITE : NativeTooltip.NOTICE_COLOR;
	}

	private static Color coverageColor(int synced, int total)
	{
		return synced >= total && total > 0 ? ClogHelper.COLOR_COMPLETED : ClogHelper.COLOR_IN_PROGRESS;
	}

	private static Color statusColor(String status)
	{
		return "ready".equalsIgnoreCase(status) ? ClogHelper.COLOR_COMPLETED : ClogHelper.COLOR_IN_PROGRESS;
	}

	@Nullable
	private static String dateText(@Nullable String raw)
	{
		if (raw == null || raw.isBlank())
		{
			return null;
		}
		int timeIndex = raw.indexOf('T');
		return timeIndex > 0 ? raw.substring(0, timeIndex) : raw;
	}

	@Nullable
	private static String prettyToken(@Nullable String raw)
	{
		if (raw == null || raw.isBlank())
		{
			return null;
		}
		return raw.replace('_', ' ');
	}

	private void writeClueRare(@Nullable JLabel label, String name, String clogCategory,
		ClanClogResult result)
	{
		if (label == null)
		{
			return;
		}
		TooltipData data = tooltipDataBuilder.buildForCategory(name, clogCategory, result);
		if (data == null)
		{
			label.setText(ClogHelper.pad("--"));
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			label.setToolTipText(name);
			return;
		}
		label.setText(ClogHelper.pad(data.obtainedCount > 0 ? ClogHelper.formatKc(data.obtainedCount) : "--"));
		rareTooltips.put(clogCategory, data);
		label.setForeground(data.obtainedCount > 0 ? KC_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		label.setToolTipText(" ");
	}

	private void writeCustomRare(@Nullable JLabel label, String name, String rareKey,
		int[] itemIds, ClanClogResult result)
	{
		if (label == null)
		{
			return;
		}
		TooltipData data = tooltipDataBuilder.buildRareBucketData(name, rareKey, itemIds, result);
		label.setText(ClogHelper.pad(data.obtainedCount > 0 ? ClogHelper.formatKc(data.obtainedCount) : "--"));
		rareTooltips.put(rareKey, data);
		label.setForeground(data.obtainedCount > 0 ? KC_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		label.setToolTipText(" ");
	}

	/**
	 * Apply Kill Clog's 4-tier clog completion colors to every cell that has
	 * data. Always on (no keybind toggle). Reads from the tooltip data cache
	 * built by {@link #rebuildTooltips} so it must run AFTER that method.
	 *
	 * <ul>
	 *   <li>Boss with kc > 0 + clog data: color by completion tier
	 *   <li>Boss with kc > 0, no clog data: empty color (kc but no clog coverage)
	 *   <li>Boss with kc = 0: stays dim gray (default)
	 *   <li>Clue tiers + rares: color by obtained/total from tooltip data
	 * </ul>
	 */
	private void applyHighlightColors(ClanClogResult result)
	{
		Map<String, ClanClogResult.BossAggregate> bosses = result.getBosses();

		for (Map.Entry<String, JLabel> entry : bossLabels.entrySet())
		{
			String boss = entry.getKey();
			JLabel label = entry.getValue();
			ClanClogResult.BossAggregate agg = bosses.get(boss);
			long kc = agg != null ? agg.getClanTotalKc() : 0L;

			if (kc <= 0)
			{
				continue;
			}

			TooltipData data = tooltipDataMap.get(boss);
			if (data != null && data.totalItems > 0)
			{
				label.setForeground(ClogHelper.clogColor(data.obtainedCount, data.totalItems));
			}
			else
			{
				// Has kc but no clog data for this boss
				label.setForeground(ClogHelper.COLOR_EMPTY);
			}
		}

		for (Map.Entry<String, JLabel> entry : clueTierLabels.entrySet())
		{
			String tier = entry.getKey();
			JLabel label = entry.getValue();
			boolean hasValue = result.getActivityTotals().getOrDefault(tier, 0L) > 0;
			if (!hasValue)
			{
				continue;
			}
			TooltipData data = tooltipDataMap.get(tier);
			if (data != null && data.totalItems > 0)
			{
				label.setForeground(ClogHelper.clogColor(data.obtainedCount, data.totalItems));
			}
			else
			{
				label.setForeground(ClogHelper.COLOR_EMPTY);
			}
		}

		applyRareHighlight(thirdAgeCell, CLOG_THIRD_AGE);
		applyRareHighlight(gildedCell, CLOG_GILDED);
		applyRareHighlight(hardRare, RARE_HARD);
		applyRareHighlight(eliteRare, RARE_ELITE);
		applyRareHighlight(masterRare, RARE_MASTER);
		applySummaryHighlights(result);
	}

	private void applyRareHighlight(@Nullable JLabel label, String rareKey)
	{
		if (label == null)
		{
			return;
		}
		TooltipData data = rareTooltips.get(rareKey);
		if (data != null && data.totalItems > 0 && data.obtainedCount > 0)
		{
			label.setForeground(ClogHelper.clogColor(data.obtainedCount, data.totalItems));
		}
	}

	private void applySummaryHighlights(ClanClogResult result)
	{
		if (membersCell != null && result.getMemberCount() > 0)
		{
			membersCell.setForeground(ClogHelper.COLOR_COMPLETED);
		}

		if (totalKillsCell != null)
		{
			totalKillsCell.setForeground(hasAnyBossKc(result)
				? ClogHelper.COLOR_COMPLETED : ColorScheme.LIGHT_GRAY_COLOR);
		}

		if (totalCluesCell != null)
		{
			int[] clueCounts = aggregateCategoryCounts(result, CLUE_CATEGORIES.values());
			totalCluesCell.setForeground(summaryColor(clueCounts,
				result.getActivityTotals().getOrDefault("Clue Scrolls (all)", 0L) > 0));
		}

		if (pvpSummaryCell != null)
		{
			int[] pvpCounts = PvpSummaryTooltip.summaryCounts(result.getClog());
			pvpSummaryCell.setForeground(summaryColor(pvpCounts, hasAnyPvpActivity(result)));
		}
	}

	private static boolean hasAnyBossKc(ClanClogResult result)
	{
		for (ClanClogResult.BossAggregate agg : result.getBosses().values())
		{
			if (agg.getClanTotalKc() > 0)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean hasAnyPvpActivity(ClanClogResult result)
	{
		Map<String, Long> activities = result.getActivityTotals();
		return activities.getOrDefault("LMS - Rank", 0L) > 0
			|| activities.getOrDefault("Soul Wars Zeal", 0L) > 0
			|| activities.getOrDefault("PvP Arena - Rank", 0L) > 0
			|| activities.getOrDefault("Bounty Hunter - Hunter", 0L) > 0
			|| activities.getOrDefault("Bounty Hunter - Rogue", 0L) > 0;
	}

	private static Color summaryColor(@Nullable int[] counts, boolean hasValue)
	{
		if (counts != null && counts[1] > 0)
		{
			return ClogHelper.clogColor(counts[0], counts[1]);
		}
		return hasValue ? ClogHelper.COLOR_EMPTY : ColorScheme.LIGHT_GRAY_COLOR;
	}

	private static int[] aggregateTooltipCounts(Iterable<TooltipData> values)
	{
		int obtained = 0;
		int total = 0;
		for (TooltipData data : values)
		{
			if (data == null || data.totalItems <= 0)
			{
				continue;
			}
			obtained += Math.max(0, data.obtainedCount);
			total += data.totalItems;
		}
		return total > 0 ? new int[]{obtained, total} : null;
	}

	@Nullable
	private int[] aggregateBossTooltipCounts()
	{
		List<TooltipData> bossData = new ArrayList<>();
		for (String boss : BOSS_DISPLAY_ORDER)
		{
			TooltipData data = tooltipDataMap.get(boss);
			if (data != null)
			{
				bossData.add(data);
			}
		}
		return aggregateTooltipCounts(bossData);
	}

	@Nullable
	private static int[] aggregateCategoryCounts(ClanClogResult result, Iterable<String> categories)
	{
		ClanClogResult.ClogUnion clog = result.getClog();
		if (clog == null)
		{
			return null;
		}
		int obtained = 0;
		int total = 0;
		for (String category : categories)
		{
			List<Integer> catalog = clog.getCatalog(category);
			if (catalog == null || catalog.isEmpty())
			{
				continue;
			}
			List<Integer> items = clog.getItemsByCategory().get(category);
			Set<Integer> obtainedIds = items != null ? new HashSet<>(items) : new HashSet<>();
			obtained += ClogHelper.countObtained(catalog, obtainedIds);
			total += catalog.size();
		}
		return total > 0 ? new int[]{obtained, total} : null;
	}

	/**
	 * Rebuild the per-cell TooltipData cache from a clan result. Mirrors the
	 * kcpdev Cells.rebuildPrimaryTooltips path but feeds ClanTooltipDataBuilder
	 * with ClanClogResult instead of TooltipDataBuilder + ClogResult.
	 */
	public void rebuildTooltips(ClanClogResult result)
	{
		tooltipDataMap.clear();
		if (result == null || result.getClog() == null)
		{
			return;
		}

		for (Map.Entry<String, JLabel> entry : bossLabels.entrySet())
		{
			String boss = entry.getKey();
			JLabel label = entry.getValue();
			String clogCategory = ClogHelper.bossToCategory(boss);
			TooltipData data = tooltipDataBuilder.buildForCategory(boss, clogCategory, result);
			if (data == null)
			{
				label.setToolTipText(boss);
				continue;
			}
			tooltipDataMap.put(boss, data);
			label.setToolTipText(" ");
		}

		for (Map.Entry<String, String> entry : CLUE_CATEGORIES.entrySet())
		{
			String tier = entry.getKey();
			JLabel label = clueTierLabels.get(tier);
			if (label == null)
			{
				continue;
			}
			String displayName = capitalizeTier(tier);
			TooltipData data = tooltipDataBuilder.buildForCategory(displayName, entry.getValue(), result);
			if (data == null)
			{
				continue;
			}
			tooltipDataMap.put(tier, data);
			label.setToolTipText(" ");
		}
	}

	// ── Tooltip routing (single-flavor only) ──────────────────────────────────

	private JToolTip buildBossTooltip(JLabel owner, String boss)
	{
		return buildSpriteTooltip(tooltipDataMap.get(boss), 5, boss, false);
	}

	private JToolTip buildClueTierTooltip(JLabel owner, String tier, String displayName, boolean compact)
	{
		return buildSpriteTooltip(tooltipDataMap.get(tier), compact ? 10 : 5, displayName, compact);
	}

	private JToolTip buildClueRareTooltip(JLabel owner, String name, String clogCategory)
	{
		return buildSpriteTooltip(rareTooltips.get(clogCategory), 5, name, false);
	}

	private JToolTip buildCustomRareTooltip(JLabel owner, String name, String rareKey, int[] itemIds)
	{
		return buildSpriteTooltip(rareTooltips.get(rareKey), 5, name, false);
	}

	/**
	 * Build a configured {@link ImgTooltip} from TooltipData. Holds the
	 * single-flavor configuration (title + obtained + rank + items) so every
	 * tooltip routing method funnels through one place.
	 *
	 * <p>Clan-flavor data sets {@code obtainedCounts == holderCounts}, so
	 * ImgTooltip's per-item quantity overlay automatically renders the clan
	 * holder count (how many members own each item) without any branch on
	 * {@link TooltipData#isClanFlavor()} at this layer.
	 */
	private JToolTip buildSpriteTooltip(@Nullable TooltipData data, int gridCols, String name, boolean compact)
	{
		ImgTooltip tip = compact ? new ImgTooltip(gridCols, 15) : new ImgTooltip(gridCols);
		tip.setTitle(name);
		if (data == null)
		{
			tip.setObtained(0, 0);
			tip.setNotice("No clan data");
			return tip;
		}
		tip.setObtained(data.obtainedCount, data.totalItems);
		tip.setRank(data.rank);
		tip.setItems(data.totalItems, data.allItemIds, data.obtainedIds,
			data.obtainedCounts, itemManager);
		return tip;
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	/** Standard grid-cell label styling (used by every cell on the panel). */
	public static void styleLabel(JLabel label, String tooltipText)
	{
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setText(ClogHelper.pad("--"));
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setIconTextGap(4);
		label.setToolTipText(tooltipText);
		label.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
	}

	/** Wrap a label in a standard grid cell panel with hover effect wired. */
	public JPanel wrapInCell(JLabel label)
	{
		JPanel cell = new JPanel();
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cell.setBorder(TooltipController.CELL_BORDER);
		cell.add(label);
		tooltipController.addCellHoverEffect(cell, label);
		return cell;
	}

	private void setItemIcon(JLabel label, int itemId)
	{
		BufferedImage img = itemManager.getImage(itemId, 1, false);
		if (img == null)
		{
			return;
		}
		label.setIcon(new ImageIcon(ImageUtil.resizeImage(img, 20, 20)));
		if (img instanceof AsyncBufferedImage)
		{
			((AsyncBufferedImage) img).onLoaded(() ->
				SwingUtilities.invokeLater(() ->
					label.setIcon(new ImageIcon(ImageUtil.resizeImage(img, 20, 20)))));
		}
	}

	private void setHiscoreSkillIcon(JLabel label, HiscoreSkill skill)
	{
		spriteManager.getSpriteAsync(skill.getSpriteId(), 0, sprite ->
			SwingUtilities.invokeLater(() ->
			{
				if (sprite != null)
				{
					label.setIcon(new ImageIcon(ImageUtil.resizeImage(
						ImageUtil.resizeCanvas(sprite, 20, 20), 16, 16)));
				}
			}));
	}

	private void loadClueSummaryIcons()
	{
		for (int i = 0; i < CLUE_SUMMARY_TIERS.length; i++)
		{
			final int idx = i;
			spriteManager.getSpriteAsync(CLUE_SUMMARY_TIERS[i].getSpriteId(), 0, sprite ->
				SwingUtilities.invokeLater(() ->
				{
					if (sprite != null)
					{
						clueIcons[idx] = ImageUtil.resizeImage(
							ImageUtil.resizeCanvas(sprite, 25, 25), 13, 13);
					}
				}));
		}

		int mimicSpriteId = bossSpriteId("Mimic");
		if (mimicSpriteId != -1)
		{
			spriteManager.getSpriteAsync(mimicSpriteId, 0, sprite ->
				SwingUtilities.invokeLater(() ->
				{
					if (sprite != null)
					{
						clueIcons[CLUE_SUMMARY_TIERS.length] = ImageUtil.resizeImage(
							ImageUtil.resizeCanvas(sprite, 25, 25), 13, 13);
					}
				}));
		}
	}

	/** "Clue Scrolls (hard)" -> "Hard". Public for the panel's tooltip-text builder. */
	public static String capitalizeTier(String tierName)
	{
		int open = tierName.indexOf('(');
		if (open < 0)
		{
			return tierName;
		}
		int close = tierName.indexOf(')', open);
		String inner = tierName.substring(open + 1, close < 0 ? tierName.length() : close).trim();
		if (inner.isEmpty())
		{
			return tierName;
		}
		return Character.toUpperCase(inner.charAt(0)) + inner.substring(1);
	}

	/**
	 * Resolve a hiscore-CSV boss name to a HiscoreSkill sprite id. Falls
	 * back to -1 (no icon) if the enum doesn't carry the boss yet.
	 */
	private static int bossSpriteId(String bossName)
	{
		String hiscoreSkillName = BOSS_NAME_TO_HISCORE_SKILL_NAME.getOrDefault(bossName, bossName);
		for (HiscoreSkill hs : HiscoreSkill.values())
		{
			if (hiscoreSkillName.equals(hs.getName()))
			{
				return hs.getSpriteId();
			}
		}
		return -1;
	}

	// ── Read-only accessors ───────────────────────────────────────────────────

	public Map<String, JLabel> getBossLabels()
	{
		return Collections.unmodifiableMap(bossLabels);
	}

	public Map<String, JLabel> getClueTierLabels()
	{
		return Collections.unmodifiableMap(clueTierLabels);
	}

	public Map<String, ImageIcon> getOriginalIcons()
	{
		return Collections.unmodifiableMap(originalIcons);
	}

	public Map<String, ImageIcon> getDimmedIcons()
	{
		return Collections.unmodifiableMap(dimmedIcons);
	}

	@Nullable
	public JLabel getBossLabel(String boss)
	{
		return bossLabels.get(boss);
	}

	@Nullable
	public JLabel getThirdAgeCell()
	{
		return thirdAgeCell;
	}

	@Nullable
	public JLabel getGildedCell()
	{
		return gildedCell;
	}

	@Nullable
	public JLabel getHardRare()
	{
		return hardRare;
	}

	@Nullable
	public JLabel getEliteRare()
	{
		return eliteRare;
	}

	@Nullable
	public JLabel getMasterRare()
	{
		return masterRare;
	}

	public Map<String, TooltipData> getTooltipDataMap()
	{
		return tooltipDataMap;
	}

	public Map<String, TooltipData> getRareTooltips()
	{
		return rareTooltips;
	}

	public BufferedImage[] getClueIcons()
	{
		return clueIcons;
	}

	@Nullable
	public TooltipData getTooltipData(String name)
	{
		return tooltipDataMap.get(name);
	}

	@Nullable
	public TooltipData getRareTooltip(String key)
	{
		return rareTooltips.get(key);
	}
}
