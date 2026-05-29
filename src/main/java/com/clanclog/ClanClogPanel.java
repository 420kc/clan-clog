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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
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
 * Main clan clog surface. Layout mirrors Kill Clog: search header and
 * activities tray (clue tiers + rares) above the boss grid. The members list
 * lives in {@link ClanMembersPanel}. PluginPanel(true) wraps this surface in a
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
	private static final Color KC4 = new Color(0xFF, 0x57, 0x00);
	private static final Color HAMBURGER_COLOR = new Color(70, 70, 70);
	private static final Color HAMBURGER_HOVER_COLOR = new Color(96, 96, 96);
	private static final String SEARCH_PLACEHOLDER = "Search for a Clan...";
	private static final String CLAN_TAB_HINT = "click off/back to your clan tab";

	private final ClanClogConfig config;
	private final ConfigManager configManager;
	private final WomClient womClient;
	private final ClanHiscoreBatch batch;
	private final ClanClogBatch clogBatch;
	private final LocalHiscoreCache hiscoreCache;
	private final ClogFetchService clogFetchService;
	private final LocalClanProfileCache clanProfileCache;
	private final InGameClanReader clanReader;
	private final KillclogApiClient apiClient;
	private final ClanLookupSession clanLookupSession;
	private final Cells cells;
	private final ClanMembersPanel membersPanel;

	private final JLabel statusLabel = new JLabel(" ");
	private final IconTextField searchBar = new IconTextField();
	private final JLabel clanHeader = new JLabel(" ")
	{
		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			super.paintComponent(g);
			if (Boolean.TRUE.equals(getClientProperty("underlined")))
			{
				String text = getText();
				if (text != null && !text.isBlank())
				{
					java.awt.FontMetrics fm = g.getFontMetrics();
					int textWidth = fm.stringWidth(text.trim());
					int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2 + 1;
					g.setColor(getForeground());
					// centered alignment: text starts at midpoint minus half width
					int textStart = (getWidth() - textWidth) / 2;
					g.drawLine(textStart, y, textStart + textWidth, y);
				}
			}
		}
	};
	private final JButton clanalyzeButton = new JButton("clanalyze");
	private final JButton syncButton = new JButton("sync");
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

	/**
	 * Monotonic token bumped on every user-initiated lookup. Async batch + WOM
	 * completions capture it at launch and bail if a newer lookup superseded
	 * them, so a stale run can never overwrite the cells, lastRenderedResult,
	 * lastLoadedSlug, sync button, or status of a newer one.
	 */
	private volatile int loadVersion;

	/** loadVersion stamped when the current backend view lookup fired; gates its listener callbacks. */
	private volatile int viewVersion = -1;

	/** Original query for the in-flight backend view, replayed as a WOM search if the backend has no record. */
	@Nullable
	private String viewQuery;

	/** Roster pending clanalyze (populated by in-game reader, not yet batch-fetched). */
	@Nullable
	private List<ClanMember> pendingRoster;

	/** Clan name pending clanalyze. */
	@Nullable
	private String pendingClanName;

	/** True only when the pending roster came from the verified in-game clan slot. */
	private boolean pendingRosterSyncEligible;

	@Inject
	public ClanClogPanel(ClanClogConfig config, ConfigManager configManager,
		WomClient womClient, ClanHiscoreBatch batch, ClanClogBatch clogBatch,
		LocalHiscoreCache hiscoreCache, ClogFetchService clogFetchService,
		LocalClanProfileCache clanProfileCache,
		InGameClanReader clanReader, KillclogApiClient apiClient,
		ClanLookupSession clanLookupSession, Cells cells, ClanMembersPanel membersPanel)
	{
		super(true);
		this.config = config;
		this.configManager = configManager;
		this.womClient = womClient;
		this.batch = batch;
		this.clogBatch = clogBatch;
		this.hiscoreCache = hiscoreCache;
		this.clogFetchService = clogFetchService;
		this.clanProfileCache = clanProfileCache;
		this.clanReader = clanReader;
		this.apiClient = apiClient;
		this.clanLookupSession = clanLookupSession;
		this.cells = cells;
		this.membersPanel = membersPanel;

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

		// Search bar with placeholder.
		c.insets = new Insets(0, 0, 4, 0);
		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.setPreferredSize(new Dimension(0, 30));
		searchBar.addActionListener(e -> onSubmit());
		styleSearchBar(searchBar);
		installPlaceholder(searchBar);
		installOwnClanShortcut(searchBar);
		add(searchBar, c);

		// Compact profile row: clan name, activities toggle, contextual actions.
		clanHeader.setFont(FontManager.getRunescapeSmallFont());
		clanHeader.setForeground(KC4);
		clanHeader.setHorizontalAlignment(JLabel.LEFT);
		clanHeader.setBorder(new EmptyBorder(0, 4, 0, 0));
		clanHeader.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		setClanHeaderText(" ");
		clanHeader.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (clanHeader.getText() != null && !clanHeader.getText().isBlank()
					&& !" ".equals(clanHeader.getText()))
				{
					clanHeader.putClientProperty("underlined", true);
					clanHeader.repaint();
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				clanHeader.putClientProperty("underlined", null);
				clanHeader.repaint();
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				String url = config.clanUrl();
				String text = clanHeader.getText();
				if (SwingUtilities.isLeftMouseButton(e)
					&& text != null && !text.isBlank()
					&& url != null && !url.isBlank())
				{
					net.runelite.client.util.LinkBrowser.browse(url);
				}
			}
		});
		configureActionButton(clanalyzeButton, "build clan profile");
		clanalyzeButton.addActionListener(e -> onClanalyzeClicked());
		configureActionButton(syncButton, "sync to killclog.com");
		syncButton.addActionListener(e -> onSyncClicked());

		c.gridy++;
		c.insets = new Insets(0, 0, 2, 0);
		add(buildProfileRow(), c);

		c.gridy++;
		c.insets = new Insets(0, 4, 5, 4);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(TEXT_DIM);
		statusLabel.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		setStatus(noClanHint());
		add(statusLabel, c);

		// Content.
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		add(activitiesTray.getClip(), c);

		c.gridy++;
		add(activitiesTray.getSeparator(), c);

		c.gridy++;
		add(cells.buildBossGrid(), c);

		clanReader.addListener(roster ->
			SwingUtilities.invokeLater(() -> onInGameRosterRefreshed(roster)));

		// Auto-load on open. An explicit Default Clan wins; otherwise, when
		// "Remember Last Clan" is enabled, restore the last analyzed clan. Opening
		// the clan tab in-game later swaps in the live roster, so this never traps
		// the user on a stale clan.
		// Deferred to the EDT: the panel is constructed off-thread during plugin
		// injection, and IconTextField.setText asserts the EDT (fatal under -ea,
		// which the dev launcher enables, so the plugin silently fails to load).
		String autoLoad = config.defaultClan().trim();
		if (autoLoad.isEmpty() && config.rememberLastClan())
		{
			String last = configManager.getConfiguration("clanclog", "lastClanName");
			if (last != null && !last.trim().isEmpty())
			{
				autoLoad = last.trim();
			}
		}
		if (!autoLoad.isEmpty())
		{
			final String target = autoLoad;
			SwingUtilities.invokeLater(() ->
			{
				if (renderStoredClanProfile(target))
				{
					return;
				}
				setSearchText(target);
				onSubmit();
			});
		}
	}

	/** Persist the last analyzed/viewed clan name so "Remember Last Clan" can restore it. */
	private void rememberClan(@Nullable String name)
	{
		if (config.rememberLastClan() && name != null && !name.isBlank())
		{
			configManager.setConfiguration("clanclog", "lastClanName", name);
		}
	}

	private void setClanHeaderText(@Nullable String text)
	{
		String value = text == null || text.isBlank() ? " " : text;
		clanHeader.setText(value);
		clanHeader.setToolTipText(" ".equals(value) ? null : value);
	}

	private JPanel buildProfileRow()
	{
		JPanel row = new JPanel(new GridBagLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setPreferredSize(new Dimension(0, 22));

		JLabel trayToggle = new JLabel();
		ImageIcon hamburgerIcon = new ImageIcon(ClogHelper.makeHamburgerIcon(HAMBURGER_COLOR));
		ImageIcon hamburgerHoverIcon = new ImageIcon(ClogHelper.makeHamburgerIcon(HAMBURGER_HOVER_COLOR));
		trayToggle.setIcon(hamburgerIcon);
		trayToggle.setHorizontalAlignment(JLabel.CENTER);
		trayToggle.setVerticalAlignment(JLabel.CENTER);
		trayToggle.setPreferredSize(new Dimension(18, 18));
		trayToggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				activitiesTray.toggle();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				trayToggle.setIcon(hamburgerHoverIcon);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				trayToggle.setIcon(hamburgerIcon);
			}
		});

		JPanel actions = new JPanel(new GridBagLayout());
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		GridBagConstraints ac = new GridBagConstraints();
		ac.gridx = 0;
		ac.anchor = GridBagConstraints.EAST;
		ac.insets = new Insets(0, 0, 0, 3);
		actions.add(clanalyzeButton, ac);
		ac.gridx = 1;
		ac.insets = new Insets(0, 0, 0, 0);
		actions.add(syncButton, ac);

		GridBagConstraints rc = new GridBagConstraints();
		rc.gridy = 0;
		rc.gridx = 0;
		rc.weightx = 1.0;
		rc.fill = GridBagConstraints.HORIZONTAL;
		rc.anchor = GridBagConstraints.WEST;
		row.add(clanHeader, rc);

		rc.gridx = 1;
		rc.weightx = 0;
		rc.fill = GridBagConstraints.NONE;
		rc.anchor = GridBagConstraints.CENTER;
		row.add(trayToggle, rc);

		rc.gridx = 2;
		rc.weightx = 1.0;
		rc.fill = GridBagConstraints.HORIZONTAL;
		rc.anchor = GridBagConstraints.EAST;
		rc.insets = new Insets(0, 4, 0, 0);
		row.add(actions, rc);

		return row;
	}

	private static void configureActionButton(JButton button, String tooltip)
	{
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setForeground(KC4);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		button.setFocusPainted(false);
		button.setBorderPainted(false);
		button.setContentAreaFilled(true);
		button.setOpaque(true);
		button.setBorder(new EmptyBorder(1, 5, 1, 5));
		button.setMargin(new Insets(0, 0, 0, 0));
		Dimension preferred = button.getPreferredSize();
		button.setPreferredSize(new Dimension(Math.max(preferred.width, 30), 18));
		button.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		button.setToolTipText(tooltip);
		button.setVisible(false);
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

	private void installOwnClanShortcut(Container root)
	{
		MouseAdapter listener = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() < 2)
				{
					return;
				}
				Component source = (Component) e.getSource();
				java.awt.Point p = SwingUtilities.convertPoint(source, e.getPoint(), searchBar);
				if (p.x <= 28)
				{
					loadOwnClanFromShortcut();
				}
			}
		};
		installMouseListener(root, listener);
	}

	private static void installMouseListener(Component component, MouseAdapter listener)
	{
		component.addMouseListener(listener);
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				installMouseListener(child, listener);
			}
		}
	}

	private void loadOwnClanFromShortcut()
	{
		String name = clanReader.currentClanName();
		List<ClanMember> roster = clanReader.currentRoster();
		if (name == null || roster == null || roster.isEmpty())
		{
			setStatus(noClanHint());
			return;
		}
		onInGameRosterRefreshed(new ArrayList<>(roster));
	}

	private String noClanHint()
	{
		if (config.defaultClan().trim().isEmpty())
		{
			return "no default clan · " + CLAN_TAB_HINT;
		}
		return CLAN_TAB_HINT;
	}

	private void setSearchText(String text)
	{
		searchBar.setText(text == null ? "" : text);
		setSearchForeground(KC_TEXT);
	}

	private void clearSearchText()
	{
		setSearchText("");
	}

	private void setSearchForeground(Color color)
	{
		for (Component child : searchBar.getComponents())
		{
			if (child instanceof FlatTextField)
			{
				((FlatTextField) child).getTextField().setForeground(color);
				return;
			}
		}
	}

	private void onSubmit()
	{
		String raw = searchBar.getText().trim();
		if (raw.isEmpty() || raw.equals(SEARCH_PLACEHOLDER))
		{
			setStatus("type a clan name or group id");
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

		// A clan you're not in: read Kill Clog backend first (pre-computed
		// combined clog from a prior sync), then fall back to a WOM roster view.
		startBackendView(raw);
	}

	/**
	 * Backend-first lookup for a clan the user isn't in. Fires the version-
	 * stamped {@link ClanLookupSession} read; on a hit the listener renders the
	 * pre-computed combined clog, on a miss it falls back to a WOM roster view.
	 */
	private void startBackendView(String query)
	{
		final int version = ++loadVersion;
		viewVersion = version;
		viewQuery = query;
		clanLookupSession.start(slugify(query), this);
	}

	/**
	 * Build a clan profile from a roster. For the user's in-game clan this is a
	 * sync-eligible clanalyze run. For a public WOM roster this is view-only, but
	 * still renders the Kill Clog-style aggregate instead of stopping at a roster.
	 *
	 * <p>Two-phase batch: hiscores first (boss KCs render immediately), then
	 * per-member clog fetch (highlight colors + clog tooltips render when done).
	 */
	private void loadFromRoster(String clanName, List<ClanMember> roster, boolean syncEligible)
	{
		String slug = slugify(clanName);
		if (slug.equals(currentLoadSlug))
		{
			return;
		}
		currentLoadSlug = slug;
		final int version = ++loadVersion;

		clanalyzeButton.setVisible(false);
		if (!syncEligible)
		{
			clearSyncState();
		}
		setClanHeaderText(clanName);
		clearSearchText();
		String progressPrefix = syncEligible ? "Clanalyzing your members: "
			: "building public clan profile: ";
		setStatus(progressPrefix + "0/" + roster.size());
		membersPanel.renderRoster(clanName, roster);

		// Phase 1: per-member hiscore fan-out
		final String name = clanName;
		batch.fetchAll(roster, completed -> SwingUtilities.invokeLater(() ->
		{
			if (version != loadVersion)
			{
				return;
			}
			setStatus(progressPrefix + completed + "/" + roster.size());
			membersPanel.renderRoster(name, roster);
		})).whenComplete((v, batchEx) ->
			SwingUtilities.invokeLater(() ->
			{
				if (version != loadVersion)
				{
					return;
				}
				// Render boss KCs immediately from hiscore data
				ClanClogResult merged = RosterClogBuilder.fromHiscores(
					name, slug, roster, lastBackendResult);
				cells.renderClanResult(merged);
				membersPanel.renderRoster(name, roster);

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
				fireClogBatch(name, slug, roster, merged, hiscoreHits, version, syncEligible);
			}));
	}

	private void searchByName(String query)
	{
		final int version = ++loadVersion;
		setStatus("searching for \"" + query + "\"...");
		membersPanel.showPlaceholder("searching...");

		apiClient.searchClanProfiles(query, SEARCH_RESULT_LIMIT).whenComplete((response, apiEx) ->
			SwingUtilities.invokeLater(() ->
			{
				if (version != loadVersion)
				{
					return;
				}
				if (response != null && !response.getMatches().isEmpty())
				{
					setStatus("matched " + response.getMatches().size()
						+ " on killclog.com");
					membersPanel.renderProfileSearchResults(query, response.getMatches(), this::startBackendView);
					return;
				}
				searchWomByName(query, version);
			}));
	}

	private void searchWomByName(String query, int version)
	{
		womClient.searchGroups(query, SEARCH_RESULT_LIMIT).whenComplete((results, ex) ->
			SwingUtilities.invokeLater(() ->
			{
				if (version != loadVersion)
				{
					return;
				}
				if (results == null || results.length == 0)
				{
					setStatus("no public clan match for \"" + query + "\"");
					membersPanel.showPlaceholder("no roster source");
					return;
				}
				setStatus("matched " + results.length + " public clan"
					+ (results.length == 1 ? "" : "s"));
				membersPanel.renderSearchResults(query, results, this::loadGroupById);
			}));
	}

	private void loadGroupById(int id)
	{
		final int version = ++loadVersion;
		setStatus("fetching roster for group " + id + "...");
		setClanHeaderText(" ");
		membersPanel.showPlaceholder("loading...");

		womClient.getGroup(id).whenComplete((group, ex) ->
		{
			if (group == null)
			{
				SwingUtilities.invokeLater(() ->
				{
					if (version != loadVersion)
					{
						return;
					}
					setStatus("no public roster returned");
					setClanHeaderText(" ");
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
				if (version != loadVersion)
				{
					return;
				}
				loadFromRoster(groupName, roster, false);
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
		List<ClanMember> roster, ClanClogResult partialResult, int hiscoreHits, int version,
		boolean syncEligible)
	{
		clogBatch.fetchAll(roster, completed -> SwingUtilities.invokeLater(() ->
		{
			if (version != loadVersion)
			{
				return;
			}
			setStatus(hiscoreHits + "/" + roster.size()
				+ " hiscores · clogs: " + completed + "/" + roster.size());
		})).whenComplete((v, clogEx) ->
			SwingUtilities.invokeLater(() ->
			{
				if (version != loadVersion)
				{
					return;
				}
				// Build client-side ClogUnion from per-member clog data
				ClanClogResult.ClogUnion union = RosterClogBuilder.buildClogUnion(roster);
				if (union != null)
				{
					partialResult.setClog(union);
				}

				// Re-render with clog highlight colors + tooltips
				cells.renderClanResult(partialResult);
				membersPanel.renderRoster(clanName, roster);

				rememberClan(clanName);
				if (syncEligible)
				{
					// Stash state for sync button
					lastRenderedResult = partialResult;
					lastLoadedRoster = roster;
					lastLoadedClanName = clanName;
					lastLoadedSlug = slug;
					clanProfileCache.put(clanName, slug, roster, partialResult);
				}
				else
				{
					clearSyncState();
				}

				// Coverage buckets, mutually exclusive so they sum to the roster
				// size. Honest input for the sync + the killclog.com/c surface:
				// don't imply complete data when only a subset resolved.
				int templeOk = 0;       // clog data obtained (Temple/RuneProfile)
				int templeMissing = 0;  // on hiscores but no clog
				int notFound = 0;       // neither hiscore nor clog
				for (ClanMember m : roster)
				{
					if (m.getClog() != null)
					{
						templeOk++;
					}
					else if (m.getHiscore() != null)
					{
						templeMissing++;
					}
					else
					{
						notFound++;
					}
				}
				int clogCount = templeOk;
				partialResult.setMemberCoverage(new ClanClogResult.MemberCoverage(
					roster.size(), templeOk, templeMissing, 0, notFound, 0));
				setStatus("done · " + roster.size() + " members ("
					+ hiscoreHits + " hiscores, " + clogCount + " clogs)");

				// Allow re-clanalyze on same clan after completion
				currentLoadSlug = null;

				// Show sync button only for the user's verified in-game clan.
				if (syncEligible)
				{
					showClanalyzeButton("refresh", "refresh clan profile");
					updateSyncButtonVisibility();
				}
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
		// Showing the live in-game roster supersedes a pending backend VIEW, so
		// invalidate its listener guard (onClanResult/NotFound/Error bail when
		// viewVersion != loadVersion). Do NOT bump loadVersion here: this method
		// fires on every clan-tab open, including mid-clanalyze, and bumping the
		// shared token would cancel an in-flight clanalyze batch.
		viewVersion = -1;
		String name = clanReader.currentClanName();
		if (name == null)
		{
			name = "my clan";
		}
		pendingClanName = name;
		pendingRoster = new ArrayList<>(roster);
		pendingRosterSyncEligible = true;
		setClanHeaderText(name);
		clearSearchText();
		if (!renderCachedClanProfile(name, pendingRoster, true))
		{
			membersPanel.renderRoster(name, pendingRoster);
			setStatus("ready · press clanalyze to start");
			showClanalyzeButton("clanalyze", "build clan profile");
		}
		revalidate();
		repaint();
	}

	private boolean renderCachedClanProfile(String clanName, List<ClanMember> roster,
		boolean syncEligible)
	{
		int hiscoreHits = 0;
		int clogHits = 0;
		for (ClanMember member : roster)
		{
			HiscoreResult hiscore = hiscoreCache.get(member.getRsn());
			if (hiscore != null)
			{
				member.setHiscore(hiscore);
				hiscoreHits++;
			}

			ClogResult clog = clogFetchService.getCached(member.getRsn());
			if (clog != null)
			{
				member.setClog(clog);
				clogHits++;
			}
		}

		if (hiscoreHits == 0 && clogHits == 0)
		{
			return false;
		}

		String slug = slugify(clanName);
		ClanClogResult cached = RosterClogBuilder.fromHiscores(clanName, slug, roster, null);
		ClanClogResult.ClogUnion union = RosterClogBuilder.buildClogUnion(roster);
		if (union != null)
		{
			cached.setClog(union);
		}

		int templeMissing = 0;
		int notFound = 0;
		for (ClanMember member : roster)
		{
			if (member.getClog() == null && member.getHiscore() != null)
			{
				templeMissing++;
			}
			else if (member.getClog() == null)
			{
				notFound++;
			}
		}
		cached.setMemberCoverage(new ClanClogResult.MemberCoverage(
			roster.size(), clogHits, templeMissing, 0, notFound, 0));

		cells.renderClanResult(cached);
		membersPanel.renderRoster(clanName, roster);
		rememberClan(clanName);
		if (syncEligible)
		{
			lastRenderedResult = cached;
			lastLoadedRoster = roster;
			lastLoadedClanName = clanName;
			lastLoadedSlug = slug;
			clanProfileCache.put(clanName, slug, roster, cached);
			showClanalyzeButton("refresh", "refresh clan profile");
			updateSyncButtonVisibility();
		}
		else
		{
			clearSyncState();
		}
		setStatus("cached · " + roster.size() + " members ("
			+ hiscoreHits + " hiscores, " + clogHits + " clogs)");
		return true;
	}

	private boolean renderStoredClanProfile(String clanNameOrSlug)
	{
		LocalClanProfileCache.StoredProfile stored = clanProfileCache.get(clanNameOrSlug);
		if (stored == null)
		{
			return false;
		}

		String name = stored.getClanName();
		String slug = stored.getSlug();
		List<ClanMember> roster = new ArrayList<>(stored.getRoster());
		ClanClogResult result = stored.getResult();

		pendingClanName = name;
		pendingRoster = roster;
		pendingRosterSyncEligible = false;
		lastRenderedResult = result;
		lastLoadedRoster = roster;
		lastLoadedClanName = name;
		lastLoadedSlug = slug;
		currentLoadSlug = null;

		setClanHeaderText(name);
		clearSearchText();
		cells.renderClanResult(result);
		membersPanel.renderRoster(name, roster);
		showClanalyzeButton("refresh", "refresh clan profile");
		updateSyncButtonVisibility();
		String saved = stored.getSavedAt();
		String date = saved != null && saved.contains("T")
			? saved.substring(0, saved.indexOf('T')) : saved;
		setStatus("cached profile · " + result.getMemberCount() + " members"
			+ (date != null ? " · " + date : ""));
		return true;
	}

	private void showClanalyzeButton(String text, String tooltip)
	{
		clanalyzeButton.setText(text);
		clanalyzeButton.setToolTipText(tooltip);
		clanalyzeButton.setEnabled(true);
		clanalyzeButton.setVisible(true);
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
		loadFromRoster(pendingClanName, new ArrayList<>(pendingRoster), pendingRosterSyncEligible);
	}

	private void clearSyncState()
	{
		lastRenderedResult = null;
		lastLoadedRoster = null;
		lastLoadedClanName = null;
		lastLoadedSlug = null;
		pendingRosterSyncEligible = false;
		syncButton.setEnabled(true);
		syncButton.setVisible(false);
		revalidate();
		repaint();
	}

	/**
	 * Show the sync button only when a clan is fully loaded and the local
	 * player holds OWNER or DEPUTY_OWNER rank. The rank check reads from
	 * the cached in-game data (populated by {@link InGameClanReader#refresh()}).
	 */
	private void updateSyncButtonVisibility()
	{
		boolean show = config.enableSync()
			&& pendingRosterSyncEligible
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

		// Step 1: sync roster. On failure, surface the backend's error code
		// (rank_not_authorized, slug_mismatch, owner_not_in_roster, ...) and stop.
		apiClient.syncRoster(slug, clanName, ownerRsn, keyRank, roster)
			.thenCompose(rosterResp ->
			{
				if (!rosterResp.isOk())
				{
					return java.util.concurrent.CompletableFuture.completedFuture(rosterResp);
				}
				// Step 2: sync pre-computed result
				return apiClient.syncResult(slug, ownerRsn, keyRank, result);
			})
			.whenComplete((resp, ex) ->
				SwingUtilities.invokeLater(() ->
				{
					syncButton.setEnabled(true);
					if (ex == null && resp != null && resp.isOk())
					{
						setStatus("synced to killclog.com/c/" + slug);
					}
					else if (resp != null)
					{
						setStatus("sync failed: " + resp.describe());
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
		statusLabel.setForeground(statusColor(text));
		statusLabel.setToolTipText(text);
	}

	private static Color statusColor(String text)
	{
		String value = text == null ? "" : text.toLowerCase();
		if (value.startsWith("done") || value.startsWith("synced")
			|| value.startsWith("clan profile"))
		{
			return ClogHelper.COLOR_COMPLETED;
		}
		if (value.startsWith("ready") || value.startsWith("matched"))
		{
			return KC4;
		}
		if (value.contains("failed") || value.startsWith("no ")
			|| value.contains("unavailable") || value.contains("missing"))
		{
			return ClogHelper.COLOR_EMPTY;
		}
		return TEXT_DIM;
	}

	// ── ClanLookupSession.Listener ────────────────────────────────────────────

	@Override
	public void onClanLookupStart(String slug)
	{
		setStatus("fetching clog data for " + slug + "...");
		lastBackendResult = null;
		clearSyncState();
		cells.clearCells();
	}

	@Override
	public void onClanResult(String slug, ClanClogResult result)
	{
		if (viewVersion != loadVersion)
		{
			return;
		}
		lastBackendResult = result;
		String name = result.getDisplayName();
		if (name != null && !name.isEmpty())
		{
			setClanHeaderText(name);
			clearSearchText();
			rememberClan(name);
		}
		if (!result.hasAggregateData() && !result.getMembers().isEmpty())
		{
			loadFromRoster(name != null && !name.isEmpty() ? name : slug,
				result.getMembers(), false);
			return;
		}
		// Surface coverage honestly: show how many members actually have clog
		// data and when the clan was last synced, not just a member count.
		ClanClogResult.MemberCoverage cov = result.getMemberCoverage();
		String coverage = cov != null
			? cov.getTempleOk() + "/" + cov.getTotal() + " with clog"
			: result.getMemberCount() + " members";
		String synced = result.getLastSyncedAt();
		String when = synced != null && synced.contains("T")
			? synced.substring(0, synced.indexOf('T')) : synced;
		String prefix = result.isRosterOnlyProfile() ? "clan profile" : "synced clog";
		setStatus(prefix + " · " + coverage + (when != null ? " · " + when : ""));
		cells.renderClanResult(result);
	}

	@Override
	public void onClanNotFound(String slug)
	{
		if (viewVersion != loadVersion)
		{
			return;
		}
		lastBackendResult = null;
		cells.clearCells();
		// Backend has no record of this clan -- fall back to a WOM roster view.
		if (viewQuery != null && !viewQuery.isBlank())
		{
			setStatus("not synced yet · searching public rosters...");
			searchByName(viewQuery);
		}
		else
		{
			setStatus("no clog data for " + slug);
		}
	}

	@Override
	public void onClanError(String slug, @Nullable String detail)
	{
		if (viewVersion != loadVersion)
		{
			return;
		}
		lastBackendResult = null;
		cells.clearCells();
		// Backend unreachable/errored -- try WOM so the user still sees a roster.
		if (viewQuery != null && !viewQuery.isBlank())
		{
			setStatus("profile lookup unavailable · searching public rosters...");
			searchByName(viewQuery);
		}
		else
		{
			setStatus("clog error for " + slug + (detail != null ? ": " + detail : ""));
		}
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
