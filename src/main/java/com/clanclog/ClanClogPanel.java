package com.clanclog;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.FlatTextField;
import net.runelite.client.ui.components.IconTextField;

/**
 * Main clan clog surface. Layout mirrors Kill Clog: search header, collapsible
 * activities tray (clue tiers + rares) above the boss grid, members slide-out
 * below. PluginPanel(true) wraps everything in a single scroll pane with a
 * 7px MinimalScrollBarUI thumb, no nested scroll surfaces.
 *
 * <p>Data source priority per the 2026-05-13 runtime-first lock:
 * {@link InGameClanReader} (primary, no external dependency, refreshes when
 * the user opens their clan tab in-game), {@link WomClient} (secondary, for
 * "look up an arbitrary clan i'm not in" searches).
 */
@Singleton
public class ClanClogPanel extends PluginPanel implements ClanLookupSession.Listener
{
	private static final int SEARCH_RESULT_LIMIT = 10;
	private static final Color TEXT_DIM = new Color(160, 160, 160);
	private static final Color KC_TEXT = new Color(215, 215, 215);
	private static final String SEARCH_PLACEHOLDER = "Search for a Clan...";

	private final ClanClogConfig config;
	private final WomClient womClient;
	private final ClanHiscoreBatch batch;
	private final ClanClogBatch clogBatch;
	private final InGameClanReader clanReader;
	private final KillclogApiClient apiClient;
	private final ClanLookupSession clanLookupSession;
	private final Cells cells;

	private final JLabel statusLabel = new JLabel(" ");
	private final IconTextField searchBar = new IconTextField();
	private final JLabel clanHeader = new JLabel(" ");
	private final JButton clanalyzeButton = new JButton("clanalyze");
	private final JButton syncButton = new JButton("sync to killclog.com");
	private final ClanMembersView membersView = new ClanMembersView();
	private final SlidePanel membersTray = new SlidePanel("members", membersView, false);
	private final ActivitiesTray activitiesTray;

	/** Last backend/fixture ClanClogResult. Merged with hiscore data after batch completes. */
	@Nullable
	private ClanClogResult lastBackendResult;

	/** Last fully-rendered result (with boss + clog data). Used for sync payload. */
	@Nullable
	private ClanClogResult lastRenderedResult;

	/** Roster from the last successful load. Used for sync payload. */
	@Nullable
	private List<ClanMember> lastLoadedRoster;

	/** Display name of the clan currently loaded. */
	@Nullable
	private String lastLoadedClanName;

	/** Slug of the last successfully loaded clan. Used for sync. */
	@Nullable
	private String lastLoadedSlug;

	/** Slug of the clan currently loading/loaded. Guards against duplicate batch fires. */
	@Nullable
	private String currentLoadSlug;

	/** Roster pending clanalyze (populated by in-game reader, not yet batch-fetched). */
	@Nullable
	private List<ClanMember> pendingRoster;

	/** Clan name pending clanalyze. */
	@Nullable
	private String pendingClanName;

	@Inject
	public ClanClogPanel(ClanClogConfig config, ConfigManager configManager,
		WomClient womClient, ClanHiscoreBatch batch, ClanClogBatch clogBatch,
		InGameClanReader clanReader, KillclogApiClient apiClient,
		ClanLookupSession clanLookupSession, Cells cells)
	{
		super(true);
		this.config = config;
		this.womClient = womClient;
		this.batch = batch;
		this.clogBatch = clogBatch;
		this.clanReader = clanReader;
		this.apiClient = apiClient;
		this.clanLookupSession = clanLookupSession;
		this.cells = cells;

		// Configure PluginPanel's scroll pane (Kill Clog parity)
		JScrollPane sp = getScrollPane();
		if (sp != null)
		{
			sp.setBorder(null);
			sp.setViewportBorder(null);
			sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			sp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
			sp.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
			sp.getVerticalScrollBar().setUI(new MinimalScrollBarUI());
			sp.getVerticalScrollBar().setPreferredSize(new Dimension(7, 0));
			sp.getVerticalScrollBar().setUnitIncrement(16);
		}

		// Activities tray: clue rares + clue tiers in a collapsible panel
		String stored = configManager.getConfiguration("clanclog", "activitiesExpanded");
		boolean trayExpanded = stored == null || Boolean.parseBoolean(stored);
		this.activitiesTray = new ActivitiesTray(buildActivitiesGrid(), trayExpanded,
			expanded -> configManager.setConfiguration("clanclog", "activitiesExpanded", expanded));

		setLayout(new GridBagLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(8, 8, 8, 8));

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 1;
		c.weighty = 0;

		// ── Status + action buttons at top ──────────────────────────────────
		c.insets = new Insets(0, 0, 2, 0);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(TEXT_DIM);
		statusLabel.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		statusLabel.setText("idle");
		add(statusLabel, c);

		c.gridy++;
		c.insets = new Insets(2, 0, 2, 0);
		clanalyzeButton.setFont(FontManager.getRunescapeSmallFont());
		clanalyzeButton.setForeground(KC_TEXT);
		clanalyzeButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		clanalyzeButton.setFocusPainted(false);
		clanalyzeButton.setBorderPainted(false);
		clanalyzeButton.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		clanalyzeButton.setVisible(false);
		clanalyzeButton.addActionListener(e -> onClanalyzeClicked());
		add(clanalyzeButton, c);

		c.gridy++;
		c.insets = new Insets(2, 0, 6, 0);
		syncButton.setFont(FontManager.getRunescapeSmallFont());
		syncButton.setForeground(KC_TEXT);
		syncButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		syncButton.setFocusPainted(false);
		syncButton.setBorderPainted(false);
		syncButton.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		syncButton.setVisible(false);
		syncButton.addActionListener(e -> onSyncClicked());
		add(syncButton, c);

		// ── Search bar with placeholder ─────────────────────────────────────
		c.gridy++;
		c.insets = new Insets(0, 0, 4, 0);
		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.setPreferredSize(new Dimension(0, 30));
		searchBar.addActionListener(e -> onSubmit());
		styleSearchBar(searchBar);
		installPlaceholder(searchBar);
		add(searchBar, c);

		// ── Clan header ─────────────────────────────────────────────────────
		c.gridy++;
		c.insets = new Insets(0, 0, 5, 0);
		clanHeader.setFont(FontManager.getRunescapeFont());
		clanHeader.setForeground(KC_TEXT);
		clanHeader.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		clanHeader.setText("no clan loaded");
		add(clanHeader, c);

		// ── Content ─────────────────────────────────────────────────────────
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		add(activitiesTray.getClip(), c);

		c.gridy++;
		add(activitiesTray.getSeparator(), c);

		c.gridy++;
		add(cells.buildBossGrid(), c);

		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		add(membersTray.getHeader(), c);

		c.gridy++;
		add(membersTray.getClip(), c);

		clanReader.addListener(roster ->
			SwingUtilities.invokeLater(() -> onInGameRosterRefreshed(roster)));

		// Auto-load default clan if configured (same path as manual search)
		String defaultClan = config.defaultClan().trim();
		if (!defaultClan.isEmpty())
		{
			searchBar.setText(defaultClan);
			for (Component child : searchBar.getComponents())
			{
				if (child instanceof FlatTextField)
				{
					((FlatTextField) child).getTextField().setForeground(KC_TEXT);
					break;
				}
			}
			SwingUtilities.invokeLater(this::onSubmit);
		}
	}

	/**
	 * Activities grid content: clue rares + clue tiers stacked vertically.
	 * Lives inside the {@link ActivitiesTray} clip above the boss grid.
	 * No section headers -- parity with Kill Clog's clean cell layout.
	 */
	private JPanel buildActivitiesGrid()
	{
		JPanel grid = new JPanel();
		grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Stats row: [Members] [Total Kills] [PvP Summary]
		// Kill Clog parity: [Combat] [Total Level] [PvP]
		JPanel statsRow = new JPanel(new GridLayout(1, 3));
		statsRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statsRow.setAlignmentX(0f);
		statsRow.add(cells.buildMembersCell());
		statsRow.add(cells.buildTotalKillsCell());
		statsRow.add(cells.buildPvpSummaryCell());
		grid.add(statsRow);

		// Row 1: [3rd Age] [Total Clues] [Gilded]
		JPanel row1 = new JPanel(new GridLayout(1, 3));
		row1.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row1.setAlignmentX(0f);
		row1.add(cells.buildThirdAgeCell());
		row1.add(cells.buildTotalCluesCell());
		row1.add(cells.buildGildedCell());
		grid.add(row1);

		// Row 2: [Hard Casket] [Elite Casket] [Master Casket]
		JPanel rareRow = new JPanel(new GridLayout(1, 3));
		rareRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rareRow.setAlignmentX(0f);
		rareRow.add(cells.buildHardRareCell());
		rareRow.add(cells.buildEliteRareCell());
		rareRow.add(cells.buildMasterRareCell());
		grid.add(rareRow);

		// 7px separator
		JPanel sep = new JPanel();
		sep.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sep.setPreferredSize(new Dimension(0, 7));
		sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 7));
		sep.setAlignmentX(0f);
		grid.add(sep);

		// Clue tiers: 2 rows of 3 (beginner -> master)
		JPanel clueTiers = cells.buildClueTierGrid();
		clueTiers.setAlignmentX(0f);
		grid.add(clueTiers);

		return grid;
	}

	private static void styleSearchBar(Container c)
	{
		c.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		for (Component child : c.getComponents())
		{
			if (child instanceof FlatTextField)
			{
				FlatTextField ftf = (FlatTextField) child;
				ftf.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				JTextField tf = ftf.getTextField();
				tf.setFont(FontManager.getRunescapeFont());
				tf.setForeground(KC_TEXT);
				tf.setCaretColor(KC_TEXT);
				tf.putClientProperty(
					RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			}
			else if (child instanceof Container)
			{
				styleSearchBar((Container) child);
			}
		}
	}

	/**
	 * Wire placeholder text into an {@link IconTextField}. Shows dimmed
	 * placeholder when the field is empty and unfocused, clears on focus.
	 */
	private static void installPlaceholder(IconTextField field)
	{
		for (Component child : field.getComponents())
		{
			if (child instanceof FlatTextField)
			{
				JTextField tf = ((FlatTextField) child).getTextField();
				tf.setText(SEARCH_PLACEHOLDER);
				tf.setForeground(TEXT_DIM);
				tf.addFocusListener(new FocusAdapter()
				{
					@Override
					public void focusGained(FocusEvent e)
					{
						if (tf.getText().equals(SEARCH_PLACEHOLDER))
						{
							tf.setText("");
							tf.setForeground(KC_TEXT);
						}
					}

					@Override
					public void focusLost(FocusEvent e)
					{
						if (tf.getText().trim().isEmpty())
						{
							tf.setText(SEARCH_PLACEHOLDER);
							tf.setForeground(TEXT_DIM);
						}
					}
				});
				return;
			}
		}
	}

	private void onSubmit()
	{
		String raw = searchBar.getText().trim();
		if (raw.isEmpty() || raw.equals(SEARCH_PLACEHOLDER))
		{
			setStatus("type a clan name or wom group id");
			return;
		}
		if (raw.matches("\\d+"))
		{
			try
			{
				loadGroupById(Integer.parseInt(raw));
			}
			catch (NumberFormatException e)
			{
				setStatus("group id too large");
			}
			return;
		}

		// Primary: in-game clan roster (no external dependency, works offline).
		// Populates the UI and shows the clanalyze button; does NOT auto-fire
		// the hiscore/clog batch. The user must press "clanalyze".
		String inGameName = clanReader.currentClanName();
		List<ClanMember> roster = clanReader.currentRoster();
		if (inGameName != null && !roster.isEmpty()
			&& normalize(inGameName).equals(normalize(raw)))
		{
			onInGameRosterRefreshed(new ArrayList<>(roster));
			return;
		}

		// Secondary: WOM search (for clans the user isn't in)
		searchByName(raw);
	}

	/**
	 * Load a clan directly from the in-game roster. Primary lookup path per
	 * the roster-first architecture: the plugin compiles its own data using
	 * the in-game clan roster + Jagex hiscores + external clog providers
	 * (Temple + RuneProfile), no killclog.com backend dependency.
	 *
	 * <p>Two-phase batch: hiscores first (boss KCs render immediately), then
	 * per-member clog fetch (highlight colors + clog tooltips render when done).
	 */
	private void loadFromRoster(String clanName, List<ClanMember> roster)
	{
		String slug = slugify(clanName);
		if (slug.equals(currentLoadSlug))
		{
			return;
		}
		currentLoadSlug = slug;

		clanalyzeButton.setVisible(false);
		clanHeader.setText(clanName + " · " + roster.size() + " members");
		setStatus("Clanalyzing your members: 0/" + roster.size());
		membersView.renderRoster(roster);
		if (!membersTray.isExpanded())
		{
			membersTray.toggle();
		}

		// Phase 1: per-member hiscore fan-out
		final String name = clanName;
		batch.fetchAll(roster, completed -> SwingUtilities.invokeLater(() ->
		{
			setStatus("Clanalyzing your members: " + completed + "/" + roster.size());
			membersView.renderRoster(roster);
		})).whenComplete((v, batchEx) ->
			SwingUtilities.invokeLater(() ->
			{
				// Render boss KCs immediately from hiscore data
				ClanClogResult merged = RosterClogBuilder.fromHiscores(
					name, slug, roster, lastBackendResult);
				cells.renderClanResult(merged);
				membersView.renderRoster(roster);

				int hiscoreHits = 0;
				for (ClanMember m : roster)
				{
					if (m.getHiscore() != null)
					{
						hiscoreHits++;
					}
				}

				// Phase 2: per-member clog fetch (Temple + RuneProfile)
				setStatus(hiscoreHits + "/" + roster.size()
					+ " hiscores · fetching clogs: 0/" + roster.size());
				fireClogBatch(name, slug, roster, merged, hiscoreHits);
			}));
	}

	private void searchByName(String query)
	{
		setStatus("searching for \"" + query + "\"...");
		if (!membersTray.isExpanded())
		{
			membersTray.toggle();
		}
		membersView.showPlaceholder("searching...");

		womClient.searchGroups(query, SEARCH_RESULT_LIMIT).whenComplete((results, ex) ->
			SwingUtilities.invokeLater(() ->
			{
				if (results == null || results.length == 0)
				{
					setStatus("no wom match for \"" + query + "\"");
					membersView.showPlaceholder("no roster source");
					return;
				}
				setStatus("matched " + results.length
					+ " · open your clan tab in-game to clanalyze");
				membersView.renderSearchResults(results, id ->
				{
				});
			}));
	}

	private void loadGroupById(int id)
	{
		setStatus("fetching roster for group " + id + "...");
		clanHeader.setText("loading clan " + id + "...");
		membersView.showPlaceholder("loading...");

		womClient.getGroup(id).whenComplete((group, ex) ->
		{
			if (group == null)
			{
				SwingUtilities.invokeLater(() ->
				{
					setStatus("no group returned (network, missing id, or wom timeout)");
					clanHeader.setText("no clan loaded");
				});
				return;
			}
			List<ClanMember> roster = new ArrayList<>();
			for (WomMembership ms : group.getMemberships())
			{
				ClanMember m = ClanMember.fromWom(ms);
				if (m != null)
				{
					roster.add(m);
				}
			}

			final String groupName = group.name;
			SwingUtilities.invokeLater(() ->
			{
				clanHeader.setText(groupName + " · " + roster.size() + " members");
				setStatus("view only · open your clan tab in-game to clanalyze");
				membersView.renderRoster(roster);
				if (!membersTray.isExpanded())
				{
					membersTray.toggle();
				}
			});
		});
	}

	/**
	 * Phase 2: fire the per-member clog batch (Temple + RuneProfile). Called
	 * after the hiscore batch completes. Updates the ClanClogResult with a
	 * client-side ClogUnion built from all members' clog data, then re-renders
	 * the cells surface with highlight colors.
	 */
	private void fireClogBatch(String clanName, String slug,
		List<ClanMember> roster, ClanClogResult partialResult, int hiscoreHits)
	{
		clogBatch.fetchAll(roster, completed -> SwingUtilities.invokeLater(() ->
			setStatus(hiscoreHits + "/" + roster.size()
				+ " hiscores · clogs: " + completed + "/" + roster.size())
		)).whenComplete((v, clogEx) ->
			SwingUtilities.invokeLater(() ->
			{
				// Build client-side ClogUnion from per-member clog data
				ClanClogResult.ClogUnion union = RosterClogBuilder.buildClogUnion(roster);
				if (union != null)
				{
					partialResult.setClog(union);
				}

				// Re-render with clog highlight colors + tooltips
				cells.renderClanResult(partialResult);
				membersView.renderRoster(roster);

				// Stash state for sync button
				lastRenderedResult = partialResult;
				lastLoadedRoster = roster;
				lastLoadedClanName = clanName;
				lastLoadedSlug = slug;

				int clogCount = 0;
				for (ClanMember m : roster)
				{
					if (m.getClog() != null)
					{
						clogCount++;
					}
				}
				setStatus("done · " + roster.size() + " members ("
					+ hiscoreHits + " hiscores, " + clogCount + " clogs)");

				// Allow re-clanalyze on same clan after completion
				currentLoadSlug = null;

				// Show sync button if the local player is a key-rank holder
				updateSyncButtonVisibility();
			}));
	}

	/**
	 * Called whenever {@link InGameClanReader} refreshes (user opens their clan
	 * tab in-game). Populates the header, roster view, and clanalyze button
	 * but does NOT fire the expensive hiscore/clog batch. The user must press
	 * "clanalyze" to start the fan-out. You can only clanalyze your own clan.
	 */
	private void onInGameRosterRefreshed(List<ClanMember> roster)
	{
		if (roster == null || roster.isEmpty())
		{
			return;
		}
		String name = clanReader.currentClanName();
		if (name == null)
		{
			name = "my clan";
		}
		pendingClanName = name;
		pendingRoster = new ArrayList<>(roster);
		clanHeader.setText(name + " · " + roster.size() + " members");
		membersView.renderRoster(pendingRoster);
		if (!membersTray.isExpanded())
		{
			membersTray.toggle();
		}
		setStatus("ready · press clanalyze to start");
		clanalyzeButton.setVisible(true);
		clanalyzeButton.setEnabled(true);
		revalidate();
		repaint();
	}

	/**
	 * Manual trigger: fires the hiscore + clog batch for the user's own clan.
	 * Only callable when an in-game roster has been detected.
	 */
	private void onClanalyzeClicked()
	{
		if (pendingRoster == null || pendingRoster.isEmpty() || pendingClanName == null)
		{
			setStatus("open your clan tab in-game first");
			return;
		}
		clanalyzeButton.setEnabled(false);
		clanalyzeButton.setVisible(false);
		revalidate();
		repaint();
		loadFromRoster(pendingClanName, new ArrayList<>(pendingRoster));
	}

	/**
	 * Show the sync button only when a clan is fully loaded and the local
	 * player holds OWNER or DEPUTY_OWNER rank. The rank check reads from
	 * the cached in-game data (populated by {@link InGameClanReader#refresh()}).
	 */
	private void updateSyncButtonVisibility()
	{
		boolean show = config.enableSync()
			&& lastRenderedResult != null
			&& lastLoadedRoster != null
			&& lastLoadedClanName != null
			&& clanReader.localPlayerKeyRank() != null;
		syncButton.setVisible(show);
		revalidate();
		repaint();
	}

	/**
	 * Sync the current clan to killclog.com. POSTs the roster to /sync
	 * then the pre-computed ClanClogResult to /result. Manual only, never
	 * fires automatically. Gated on OWNER / DEPUTY_OWNER rank.
	 */
	private void onSyncClicked()
	{
		if (!config.enableSync())
		{
			setStatus("enable sync in plugin config first");
			return;
		}

		String keyRank = clanReader.localPlayerKeyRank();
		String ownerRsn = clanReader.localPlayerName();
		if (keyRank == null || ownerRsn == null
			|| lastLoadedRoster == null || lastRenderedResult == null
			|| lastLoadedClanName == null)
		{
			setStatus("sync failed: missing data or rank");
			return;
		}

		syncButton.setEnabled(false);
		setStatus("syncing to killclog.com...");

		String slug = lastLoadedSlug;
		String clanName = lastLoadedClanName;
		List<ClanMember> roster = lastLoadedRoster;
		ClanClogResult result = lastRenderedResult;

		// Step 1: sync roster
		apiClient.syncRoster(slug, clanName, ownerRsn, keyRank, roster)
			.thenCompose(rosterOk ->
			{
				if (!rosterOk)
				{
					return java.util.concurrent.CompletableFuture.completedFuture(false);
				}
				// Step 2: sync pre-computed result
				return apiClient.syncResult(slug, ownerRsn, keyRank, result);
			})
			.whenComplete((ok, ex) ->
				SwingUtilities.invokeLater(() ->
				{
					syncButton.setEnabled(true);
					if (ok != null && ok)
					{
						setStatus("synced to killclog.com/c/" + slug);
					}
					else
					{
						setStatus("sync failed"
							+ (ex != null ? ": " + ex.getMessage() : ""));
					}
				}));
	}

	private void setStatus(String text)
	{
		statusLabel.setText(text);
	}

	// ── ClanLookupSession.Listener ────────────────────────────────────────────

	@Override
	public void onClanLookupStart(String slug)
	{
		setStatus("fetching clog data for " + slug + "...");
		lastBackendResult = null;
		cells.clearCells();
	}

	@Override
	public void onClanResult(String slug, ClanClogResult result)
	{
		lastBackendResult = result;
		int memberCount = result.getMemberCount();
		String name = result.getDisplayName();
		if (name != null && !name.isEmpty())
		{
			clanHeader.setText(name + " · " + memberCount + " members");
		}
		setStatus("clog loaded · " + memberCount + " members · " + slug);
		cells.renderClanResult(result);
	}

	@Override
	public void onClanNotFound(String slug)
	{
		lastBackendResult = null;
		setStatus("no clog data for " + slug);
		cells.clearCells();
	}

	@Override
	public void onClanError(String slug, @Nullable String detail)
	{
		lastBackendResult = null;
		setStatus("clog error for " + slug + (detail != null ? ": " + detail : ""));
		cells.clearCells();
	}

	/**
	 * Slugify a clan display name into the kebab-case form the backend keys
	 * its {@code /api/clan/<slug>/clog} endpoint by. "Exclusive Elite Club"
	 * -> "exclusive-elite-club".
	 */
	private static String slugify(String name)
	{
		if (name == null)
		{
			return "";
		}
		return name.toLowerCase()
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("^-+|-+$", "");
	}

	/**
	 * Strip a clan name to lowercase alphanumerics for comparison. OSRS clan
	 * names are case-insensitive and may contain spaces or punctuation that
	 * shouldn't block a match between search input and in-game data.
	 */
	private static String normalize(String name)
	{
		if (name == null)
		{
			return "";
		}
		return name.toLowerCase().replaceAll("[^a-z0-9]", "");
	}
}
