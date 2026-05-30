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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import javax.swing.border.LineBorder;
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
	private static final Color KC1 = new Color(0x4E, 0xF0, 0x15);
	private static final Color KC2 = new Color(0xCA, 0xFF, 0x00);
	private static final Color KC4 = new Color(0xFF, 0x57, 0x00);
	private static final Color HAMBURGER_COLOR = new Color(70, 70, 70);
	private static final Color HAMBURGER_HOVER_COLOR = new Color(96, 96, 96);
	private static final String SEARCH_PLACEHOLDER = "Search for a Clan...";
	private static final String CLAN_TAB_HINT = "click off/back to your clan tab";
	private static final String REFRESH_ACTION_PROPERTY = "clanclog.refreshAction";

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
	private final JPanel coverageCounts = new JPanel(new GridBagLayout());
	private final JLabel hiscoreCoverageLabel = new JLabel("--");
	private final JLabel clogCoverageLabel = new JLabel("--");
	private final IconTextField searchBar = new IconTextField();
	private final JLabel clanHeader = new JLabel(" ");
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

	/** True only after a completed in-client own-clan clanalyze run. */
	private boolean syncPayloadFromClanalyze;

	/** True when the visible own-clan profile came from cache and must be refreshed before upload. */
	private boolean syncRequiresFreshClanalyze;

	/** Slug of the clan currently loading/loaded. Guards against duplicate batch fires. */
	@Nullable
	private String currentLoadSlug;

	/** loadVersion that owns {@link #currentLoadSlug}; prevents stale completions clearing newer loads. */
	private int currentLoadVersion = -1;

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

		// Status + coverage sits above search so the profile row stays clean.
		c.insets = new Insets(0, 0, 3, 0);
		add(buildStatusRow(), c);

		// Search bar with placeholder.
		c.gridy++;
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
				if (hasClanHeaderText())
				{
					clanHeader.setForeground(KC2);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				clanHeader.setForeground(KC4);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				String url = config.clanUrl();
				String text = clanHeader.getText();
				boolean hasText = text != null && !text.isBlank();
				if (SwingUtilities.isLeftMouseButton(e) && hasText)
				{
					clanHeader.setForeground(KC1);
				}
				if (SwingUtilities.isLeftMouseButton(e) && hasText && url != null && !url.isBlank())
				{
					net.runelite.client.util.LinkBrowser.browse(url);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (hasClanHeaderText())
				{
					clanHeader.setForeground(clanHeader.contains(e.getPoint()) ? KC2 : KC4);
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

		setStatus(noClanHint());

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
		// "Remember Last Clan" is enabled, restore the last analyzed clan. If the
		// config key is missing but a disk profile exists, render the most recent
		// saved profile so cached colors are visible before RuneLite exposes the
		// clan sidepanel settings. Opening the clan tab in-game later swaps in the
		// live roster, so this never traps the user on a stale clan.
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
		else if (config.rememberLastClan())
		{
			SwingUtilities.invokeLater(this::renderLatestStoredClanProfile);
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
		clanHeader.setForeground(KC4);
	}

	private boolean hasClanHeaderText()
	{
		String text = clanHeader.getText();
		return text != null && !text.isBlank();
	}

	private JPanel buildStatusRow()
	{
		JPanel row = new JPanel(new GridBagLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setPreferredSize(new Dimension(0, 18));

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(TEXT_DIM);
		statusLabel.setMinimumSize(new Dimension(0, 18));
		statusLabel.setPreferredSize(new Dimension(0, 18));
		statusLabel.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		coverageCounts.setBackground(ColorScheme.DARK_GRAY_COLOR);
		coverageCounts.setVisible(false);
		styleCoverageLabel(hiscoreCoverageLabel, "hiscores represented");
		styleCoverageLabel(clogCoverageLabel, "clogs represented");
		cells.installCoverageIcons(hiscoreCoverageLabel, clogCoverageLabel);

		GridBagConstraints cc = new GridBagConstraints();
		cc.gridx = 0;
		cc.gridy = 0;
		cc.insets = new Insets(0, 0, 0, 8);
		coverageCounts.add(hiscoreCoverageLabel, cc);
		cc.gridx = 1;
		cc.insets = new Insets(0, 0, 0, 0);
		coverageCounts.add(clogCoverageLabel, cc);

		GridBagConstraints rc = new GridBagConstraints();
		rc.gridx = 0;
		rc.gridy = 0;
		rc.weightx = 1.0;
		rc.fill = GridBagConstraints.HORIZONTAL;
		rc.anchor = GridBagConstraints.WEST;
		row.add(statusLabel, rc);

		return row;
	}

	private static void styleCoverageLabel(JLabel label, String tooltip)
	{
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(KC2);
		label.setIconTextGap(3);
		label.setToolTipText(tooltip);
		label.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
	}

	private JPanel buildProfileRow()
	{
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

		JPanel row = new JPanel(null)
		{
			@Override
			public void doLayout()
			{
				int width = getWidth();
				int height = getHeight();
				Dimension toggleSize = trayToggle.getPreferredSize();
				int toggleW = toggleSize.width;
				int toggleH = Math.min(height, toggleSize.height);
				int toggleX = Math.max(0, (width - toggleW) / 2);
				int toggleY = Math.max(0, (height - toggleH) / 2);

				Dimension actionSize = actions.getPreferredSize();
				int actionMaxW = Math.max(0, width - toggleX - toggleW - 4);
				int actionW = Math.min(actionSize.width, actionMaxW);
				int actionH = Math.min(height, Math.max(actionSize.height, 18));
				int actionY = Math.max(0, (height - actionH) / 2);

				trayToggle.setBounds(toggleX, toggleY, toggleW, toggleH);
				actions.setBounds(width - actionW, actionY, actionW, actionH);
				clanHeader.setBounds(0, 0, Math.max(0, toggleX - 4), height);
			}
		};
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setPreferredSize(new Dimension(0, 22));
		row.add(clanHeader);
		row.add(trayToggle);
		row.add(actions);

		return row;
	}

	private static void configureActionButton(JButton button, String tooltip)
	{
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		button.setFocusPainted(false);
		button.setBorderPainted(true);
		button.setContentAreaFilled(false);
		button.setOpaque(false);
		button.setMargin(new Insets(0, 5, 0, 5));
		applyActionButtonColor(button, KC4);
		Dimension preferred = button.getPreferredSize();
		button.setPreferredSize(new Dimension(Math.max(preferred.width, 30), 18));
		button.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		button.setToolTipText(tooltip);
		button.setVisible(false);
		button.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (button.isEnabled())
				{
					applyActionButtonColor(button, KC2);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (button.isEnabled())
				{
					applyActionButtonColor(button, KC4);
				}
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (button.isEnabled() && SwingUtilities.isLeftMouseButton(e))
				{
					applyActionButtonColor(button, KC1);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (button.isEnabled())
				{
					applyActionButtonColor(button,
						button.contains(e.getPoint()) ? KC2 : KC4);
				}
			}
		});
	}

	private static void applyActionButtonColor(JButton button, Color color)
	{
		button.setForeground(color);
		if (Boolean.TRUE.equals(button.getClientProperty(REFRESH_ACTION_PROPERTY)))
		{
			button.setBorder(new EmptyBorder(0, 0, 0, 0));
			button.setBorderPainted(false);
			button.setIcon(new ImageIcon(ClogHelper.makeRefreshIcon(color)));
			return;
		}
		button.setBorderPainted(true);
		button.setBorder(new LineBorder(color, 1, false));
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

		// 7px separator between the summary row and clue/reward rows.
		JPanel sep = new JPanel();
		sep.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sep.setPreferredSize(new Dimension(0, 7));
		sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 7));
		sep.setAlignmentX(0f);
		grid.add(sep);

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
			clearCoverageCounts();
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

		// Typed search reads Kill Clog's backend aggregate first, including for
		// your own clan. The in-game roster path remains available from the clan
		// tab refresh and the explicit refresh/freshen affordance.
		clearCoverageCounts();
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
		clearCurrentLoad();
		clearSyncState();
		viewVersion = version;
		viewQuery = query;
		clanLookupSession.start(slugify(query), this);
	}

	private void startCurrentLoad(String slug, int version)
	{
		currentLoadSlug = slug;
		currentLoadVersion = version;
	}

	private void clearCurrentLoad()
	{
		currentLoadSlug = null;
		currentLoadVersion = -1;
	}

	private void clearCurrentLoadIfOwner(String slug, int version)
	{
		if (version == currentLoadVersion && slug.equals(currentLoadSlug))
		{
			clearCurrentLoad();
		}
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
		final int version = ++loadVersion;
		startCurrentLoad(slug, version);

		clanalyzeButton.setVisible(false);
		clearLoadedSyncPayload();
		syncRequiresFreshClanalyze = false;
		hideSyncButton();
		if (!syncEligible)
		{
			clearSyncState();
		}
		clearCoverageCounts();
		setClanHeaderText(clanName);
		clearSearchText();
		String progressPrefix = syncEligible ? "clanalyzing: " : "building: ";
		setStatus(progressPrefix + "0/" + roster.size());
		cells.clearCells();
		membersPanel.renderRoster(clanName, roster);

		// Phase 1: per-member hiscore fan-out
		final String name = clanName;
		batch.fetchAll(roster, completed -> SwingUtilities.invokeLater(() ->
		{
			if (version != loadVersion)
			{
				clearCurrentLoadIfOwner(slug, version);
				return;
			}
			setStatus(progressPrefix + completed + "/" + roster.size());
			membersPanel.renderRoster(name, roster);
		})).whenComplete((v, batchEx) ->
			SwingUtilities.invokeLater(() ->
			{
				if (version != loadVersion)
				{
					clearCurrentLoadIfOwner(slug, version);
					return;
				}
				// Render boss KCs immediately from hiscore data
				ClanClogResult existing = syncEligible ? null : lastBackendResult;
				ClanClogResult merged = RosterClogBuilder.fromHiscores(
					name, slug, roster, existing);
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

				// Phase 2: per-member clog fetch from local/provider caches.
				setCoverageCounts(hiscoreHits, 0);
				setStatus("fetching clogs: 0/" + roster.size());
				fireClogBatch(name, slug, roster, merged, hiscoreHits, version, syncEligible);
			}));
	}

	private void searchByName(String query)
	{
		final int version = ++loadVersion;
		clearCurrentLoad();
		clearSyncState();
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
		clearCurrentLoad();
		clearSyncState();
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
	 * Phase 2: fire the per-member clog batch. Called
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
				clearCurrentLoadIfOwner(slug, version);
				return;
			}
			setStatus("fetching clogs: " + completed + "/" + roster.size());
		})).whenComplete((v, clogEx) ->
			SwingUtilities.invokeLater(() ->
			{
				if (version != loadVersion)
				{
					clearCurrentLoadIfOwner(slug, version);
					return;
				}
				// Build client-side ClogUnion from per-member clog data
				ClanClogResult.ClogUnion union = RosterClogBuilder.buildClogUnion(roster);
				if (union != null)
				{
					partialResult.setClog(union);
					String newestClogLastChanged =
						RosterClogBuilder.newestClogLastChanged(roster);
					if (newestClogLastChanged != null)
					{
						partialResult.setClogLastChanged(newestClogLastChanged);
					}
				}

				// Re-render with clog highlight colors + tooltips
				cells.renderClanResult(partialResult);
				membersPanel.renderRoster(clanName, roster);

				rememberClan(clanName);

				// Coverage buckets, mutually exclusive so they sum to the roster
				// size. Honest input for the sync + the killclog.com/c surface:
				// don't imply complete data when only a subset resolved.
				int clogOk = 0;       // clog data obtained from any provider
				int hiscoreOnly = 0;  // on hiscores but no clog
				int notFound = 0;       // neither hiscore nor clog
				for (ClanMember m : roster)
				{
					if (m.getClog() != null)
					{
						clogOk++;
					}
					else if (m.getHiscore() != null)
					{
						hiscoreOnly++;
					}
					else
					{
						notFound++;
					}
				}
				int clogCount = clogOk;
				partialResult.setMemberCoverage(new ClanClogResult.MemberCoverage(
					roster.size(), clogOk, hiscoreOnly, 0, notFound, 0));
				if (syncEligible && partialResult.hasRepresentedData())
				{
					// Stash state for sync button
					lastRenderedResult = partialResult;
					lastLoadedRoster = roster;
					lastLoadedClanName = clanName;
					lastLoadedSlug = slug;
					syncPayloadFromClanalyze = true;
					syncRequiresFreshClanalyze = false;
					clanProfileCache.put(clanName, slug, roster, partialResult);
				}
				else
				{
					clearSyncState();
				}
				setCoverageCounts(hiscoreHits, clogCount);
				setStatus(" ");

				// Allow re-clanalyze on same clan after completion
				clearCurrentLoadIfOwner(slug, version);

				// Show sync button only for the user's verified in-game clan.
				if (syncEligible)
				{
					showClanalyzeButton("refresh", "freshen clan profile");
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
		if (preserveFreshSyncPayload(name, pendingRoster))
		{
			revalidate();
			repaint();
			return;
		}
		if (!renderCachedClanProfile(name, pendingRoster, true))
		{
			membersPanel.renderRoster(name, pendingRoster);
			setStatus("ready · press clanalyze to start");
			showClanalyzeButton("clanalyze", "build clan profile");
		}
		renderBackendOwnClanProfile(name, new ArrayList<>(pendingRoster));
		revalidate();
		repaint();
	}

	private boolean preserveFreshSyncPayload(String clanName, List<ClanMember> roster)
	{
		String slug = slugify(clanName);
		if (slug.isEmpty()
			|| syncRequiresFreshClanalyze
			|| lastRenderedResult == null
			|| lastLoadedRoster == null
			|| lastLoadedSlug == null
			|| !syncPayloadFromClanalyze
			|| !slug.equals(lastLoadedSlug)
			|| !lastRenderedResult.matchesSlug(slug)
			|| !sameRosterMembers(lastLoadedRoster, roster))
		{
			return false;
		}

		lastLoadedRoster = new ArrayList<>(roster);
		lastLoadedClanName = clanName;
		pendingRosterSyncEligible = true;
		cells.renderClanResult(lastRenderedResult);
		membersPanel.renderRoster(clanName, roster);
		setCoverageFromResult(lastRenderedResult);
		showClanalyzeButton("refresh", "freshen clan profile");
		updateSyncButtonVisibility();
		setStatus(" ");
		return true;
	}

	private void renderBackendOwnClanProfile(String clanName, List<ClanMember> roster)
	{
		final String slug = slugify(clanName);
		final int version = loadVersion;
		apiClient.fetchClanClog(slug).whenComplete((result, ex) ->
			SwingUtilities.invokeLater(() ->
			{
				if (ex != null || result == null || !result.hasRepresentedData()
					|| version != loadVersion || currentLoadSlug != null)
				{
					return;
				}
				if (pendingClanName == null || !normalize(pendingClanName).equals(normalize(clanName)))
				{
					return;
				}

				cells.renderClanResult(result);
				membersPanel.renderRoster(clanName, roster);
				lastBackendResult = result;
				lastRenderedResult = result;
				lastLoadedRoster = roster;
				lastLoadedClanName = clanName;
				lastLoadedSlug = slug;
				syncPayloadFromClanalyze = false;
				pendingRosterSyncEligible = true;
				syncRequiresFreshClanalyze = true;
				setCoverageFromResult(result);
				showClanalyzeButton("refresh", "freshen clan profile");
				updateSyncButtonVisibility();
				setStatus(" ");
			}));
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
			String newestClogLastChanged = RosterClogBuilder.newestClogLastChanged(roster);
			if (newestClogLastChanged != null)
			{
				cached.setClogLastChanged(newestClogLastChanged);
			}
		}

		int hiscoreOnly = 0;
		int notFound = 0;
		for (ClanMember member : roster)
		{
			if (member.getClog() == null && member.getHiscore() != null)
			{
				hiscoreOnly++;
			}
			else if (member.getClog() == null)
			{
				notFound++;
			}
		}
		cached.setMemberCoverage(new ClanClogResult.MemberCoverage(
			roster.size(), clogHits, hiscoreOnly, 0, notFound, 0));

		cells.renderClanResult(cached);
		membersPanel.renderRoster(clanName, roster);
		rememberClan(clanName);
		if (syncEligible)
		{
			showClanalyzeButton("refresh", "freshen clan profile before syncing");
			syncPayloadFromClanalyze = false;
			syncRequiresFreshClanalyze = true;
			updateSyncButtonVisibility();
		}
		else
		{
			clearSyncState();
		}
		setCoverageCounts(hiscoreHits, clogHits);
		setStatus(" ");
		return true;
	}

	private void hideSyncButton()
	{
		syncButton.setEnabled(true);
		syncButton.setVisible(false);
		revalidate();
		repaint();
	}

	private void showSyncButton(String tooltip)
	{
		syncButton.setEnabled(true);
		syncButton.setToolTipText(tooltip);
		applyActionButtonColor(syncButton, KC4);
		syncButton.setVisible(true);
		revalidate();
		repaint();
	}

	void onLocalClogCaptured(String playerName)
	{
		if (playerName == null || pendingClanName == null || pendingRoster == null
			|| currentLoadSlug != null)
		{
			return;
		}

		String key = normalize(playerName);
		for (ClanMember member : pendingRoster)
		{
			if (normalize(member.getRsn()).equals(key))
			{
				if (renderCachedClanProfile(pendingClanName, pendingRoster, pendingRosterSyncEligible))
				{
					setStatus(localCaptureStatus(playerName));
				}
				return;
			}
		}
	}

	private String localCaptureStatus(String playerName)
	{
		int categories = clogFetchService.categoryCount(playerName);
		if (categories <= 1)
		{
			return "1 clog category captured";
		}
		return categories + " clog categories captured";
	}

	private boolean renderStoredClanProfile(String clanNameOrSlug)
	{
		LocalClanProfileCache.StoredProfile stored = clanProfileCache.get(clanNameOrSlug);
		if (stored == null)
		{
			return false;
		}
		return renderStoredClanProfile(stored);
	}

	private boolean renderLatestStoredClanProfile()
	{
		LocalClanProfileCache.StoredProfile stored = clanProfileCache.latest();
		if (stored == null)
		{
			return false;
		}
		return renderStoredClanProfile(stored);
	}

	private boolean renderStoredClanProfile(LocalClanProfileCache.StoredProfile stored)
	{
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
		syncPayloadFromClanalyze = false;
		clearCurrentLoad();

		setClanHeaderText(name);
		clearSearchText();
		rememberClan(name);
		cells.renderClanResult(result);
		membersPanel.renderRoster(name, roster);
		showClanalyzeButton("refresh", "freshen clan profile");
		updateSyncButtonVisibility();
		setCoverageFromRoster(roster);
		if (!coverageCounts.isVisible())
		{
			setCoverageFromResult(result);
		}
		setStatus(" ");
		freshenStoredClanProfile(slug);
		return true;
	}

	private void freshenStoredClanProfile(String clanNameOrSlug)
	{
		String slug = slugify(clanNameOrSlug);
		if (slug.isEmpty())
		{
			return;
		}
		final int version = loadVersion;
		apiClient.fetchClanClog(slug).whenComplete((result, ex) ->
			SwingUtilities.invokeLater(() ->
			{
				if (ex != null || result == null || !result.hasRepresentedData()
					|| version != loadVersion || currentLoadSlug != null)
				{
					return;
				}
				if (pendingClanName == null
					|| !normalize(pendingClanName).equals(normalize(clanNameOrSlug)))
				{
					return;
				}

				String displayName = result.getDisplayName();
				if (displayName == null || displayName.isBlank())
				{
					displayName = pendingClanName;
				}
				List<ClanMember> roster = pendingRoster != null && !pendingRoster.isEmpty()
					? pendingRoster : result.getMembers();

				lastBackendResult = result;
				lastRenderedResult = result;
				lastLoadedRoster = roster;
				lastLoadedClanName = displayName;
				lastLoadedSlug = slug;
				syncPayloadFromClanalyze = false;

				setClanHeaderText(displayName);
				cells.renderClanResult(result);
				if (roster != null && !roster.isEmpty())
				{
					membersPanel.renderRoster(displayName, roster);
				}
				setCoverageFromResult(result);
				showClanalyzeButton("refresh", "freshen clan profile");
				updateSyncButtonVisibility();
				setStatus(" ");
			}));
	}

	private void showClanalyzeButton(String text, String tooltip)
	{
		boolean refresh = "refresh".equals(text);
		clanalyzeButton.putClientProperty(REFRESH_ACTION_PROPERTY, refresh);
		clanalyzeButton.setText(refresh ? "" : text);
		clanalyzeButton.setIcon(refresh ? new ImageIcon(ClogHelper.makeRefreshIcon(KC4)) : null);
		clanalyzeButton.setMargin(refresh ? new Insets(0, 0, 0, 0) : new Insets(0, 5, 0, 5));
		clanalyzeButton.setPreferredSize(null);
		Dimension preferred = clanalyzeButton.getPreferredSize();
		clanalyzeButton.setPreferredSize(refresh ? new Dimension(20, 18)
			: new Dimension(Math.max(preferred.width, 30), 18));
		clanalyzeButton.setToolTipText(tooltip);
		clanalyzeButton.setEnabled(true);
		applyActionButtonColor(clanalyzeButton, KC4);
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
		clearLoadedSyncPayload();
		pendingRosterSyncEligible = false;
		syncRequiresFreshClanalyze = false;
		syncButton.setEnabled(true);
		syncButton.setVisible(false);
		revalidate();
		repaint();
	}

	private void clearLoadedSyncPayload()
	{
		lastRenderedResult = null;
		lastLoadedRoster = null;
		lastLoadedClanName = null;
		lastLoadedSlug = null;
		syncPayloadFromClanalyze = false;
	}

	/**
	 * Show the sync button only when a clan is fully loaded and the local
	 * player holds OWNER or DEPUTY_OWNER rank. The rank check reads from
	 * the cached in-game data (populated by {@link InGameClanReader#refresh()}).
	 */
	private void updateSyncButtonVisibility()
	{
		if (hasFreshSyncPayload() && hasSyncAuthority())
		{
			showSyncButton("sync to killclog.com");
			return;
		}
		hideSyncButton();
	}

	private boolean hasFreshSyncPayload()
	{
		return pendingRosterSyncEligible
			&& !syncRequiresFreshClanalyze
			&& syncPayloadFromClanalyze
			&& lastRenderedResult != null
			&& lastLoadedRoster != null
			&& lastLoadedClanName != null
			&& lastLoadedSlug != null
			&& lastRenderedResult.matchesSlug(lastLoadedSlug);
	}

	private boolean hasSyncAuthority()
	{
		return config.enableSync()
			&& clanReader.localPlayerKeyRank() != null
			&& clanReader.localPlayerName() != null;
	}

	private String syncGateTooltip()
	{
		if (!config.enableSync())
		{
			return "enable sync in plugin config";
		}
		if (clanReader.localPlayerKeyRank() == null
			|| clanReader.localPlayerName() == null)
		{
			return "open clan tab as owner/deputy to sync";
		}
		if (syncRequiresFreshClanalyze)
		{
			return "freshen clan profile before syncing";
		}
		return "finish clanalyze before syncing";
	}

	private static boolean sameRosterMembers(List<ClanMember> a, List<ClanMember> b)
	{
		if (a == null || b == null || a.size() != b.size())
		{
			return false;
		}
		Set<String> keys = new HashSet<>();
		for (ClanMember member : a)
		{
			keys.add(normalize(member.getRsn()));
		}
		for (ClanMember member : b)
		{
			if (!keys.remove(normalize(member.getRsn())))
			{
				return false;
			}
		}
		return keys.isEmpty();
	}

	/**
	 * Sync the current clan to killclog.com. POSTs the roster to /sync
	 * then the pre-computed ClanClogResult to /result. Manual only, never
	 * fires automatically. Gated on OWNER / DEPUTY_OWNER rank.
	 */
	private void onSyncClicked()
	{
		if (!hasFreshSyncPayload())
		{
			updateSyncButtonVisibility();
			setStatus(syncGateTooltip());
			return;
		}
		if (!config.enableSync())
		{
			setStatus("enable sync in plugin config first");
			return;
		}

		String keyRank = clanReader.localPlayerKeyRank();
		String ownerRsn = clanReader.localPlayerName();
		if (keyRank == null || ownerRsn == null
			|| lastLoadedRoster == null || lastRenderedResult == null
			|| lastLoadedClanName == null || lastLoadedSlug == null)
		{
			updateSyncButtonVisibility();
			setStatus(syncGateTooltip());
			return;
		}

		String slug = lastLoadedSlug;
		String clanName = lastLoadedClanName;
		List<ClanMember> roster = lastLoadedRoster;
		ClanClogResult result = lastRenderedResult;
		if (!result.matchesSlug(slug))
		{
			updateSyncButtonVisibility();
			setStatus("sync failed: stale clan profile");
			return;
		}

		syncButton.setEnabled(false);
		applyActionButtonColor(syncButton, KC4);
		setStatus("syncing to killclog.com...");

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
					applyActionButtonColor(syncButton, KC4);
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
		revalidate();
		repaint();
	}

	private void setCoverageCounts(int hiscoreHits, int clogHits)
	{
		coverageCounts.setVisible(false);
		hiscoreCoverageLabel.setText(formatCoverageCount(hiscoreHits));
		clogCoverageLabel.setText(formatCoverageCount(clogHits));
		hiscoreCoverageLabel.setToolTipText(hiscoreHits + " hiscores represented");
		clogCoverageLabel.setToolTipText(clogHits + " clogs represented");
		revalidate();
		repaint();
	}

	private void setCoverageFromRoster(List<ClanMember> roster)
	{
		int hiscoreHits = 0;
		int clogHits = 0;
		for (ClanMember member : roster)
		{
			if (member.getHiscore() != null)
			{
				hiscoreHits++;
			}
			if (member.getClog() != null)
			{
				clogHits++;
			}
		}
		setCoverageCounts(hiscoreHits, clogHits);
	}

	private void setCoverageFromResult(ClanClogResult result)
	{
		ClanClogResult.MemberCoverage cov = result.getMemberCoverage();
		if (cov == null)
		{
			clearCoverageCounts();
			return;
		}
		setCoverageCounts(cov.getHiscoreRepresented(), cov.getClogRepresented());
	}

	private void clearCoverageCounts()
	{
		setCoverageCounts(0, 0);
	}

	private static String formatCoverageCount(int count)
	{
		return count > 0 ? String.format("%,d", count) : "--";
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
		clearCoverageCounts();
		lastBackendResult = null;
		clearSyncState();
		clanalyzeButton.setVisible(false);
		cells.clearCells();
		membersPanel.showPlaceholder("fetching killclog.com profile");
	}

	@Override
	public void onClanResult(String slug, ClanClogResult result)
	{
		if (viewVersion != loadVersion)
		{
			return;
		}
		lastBackendResult = result.hasRepresentedData() ? result : null;
		String name = result.getDisplayName();
		if (name != null && !name.isEmpty())
		{
			setClanHeaderText(name);
			clearSearchText();
			rememberClan(name);
		}
		if (!result.hasRepresentedData() && !result.getMembers().isEmpty())
		{
			loadFromRoster(name != null && !name.isEmpty() ? name : slug,
				result.getMembers(), false);
			return;
		}
		if (!result.hasRepresentedData())
		{
			clearCoverageCounts();
			cells.clearCells();
			setStatus("no represented clog data for " + slug);
			return;
		}
		String displayName = name != null && !name.isEmpty() ? name : slug;
		List<ClanMember> roster = new ArrayList<>(result.getMembers());
		pendingClanName = displayName;
		pendingRoster = roster;
		pendingRosterSyncEligible = false;
		lastRenderedResult = result;
		lastLoadedRoster = roster;
		lastLoadedClanName = displayName;
		lastLoadedSlug = slug;

		setCoverageFromResult(result);
		setStatus(" ");
		cells.renderClanResult(result);
		if (!roster.isEmpty())
		{
			membersPanel.renderRoster(displayName, roster);
			clanProfileCache.put(displayName, slug, roster, result);
		}
		else
		{
			membersPanel.showPlaceholder("member roster unavailable");
		}
		updateSyncButtonVisibility();
	}

	@Override
	public void onClanNotFound(String slug)
	{
		if (viewVersion != loadVersion)
		{
			return;
		}
		lastBackendResult = null;
		clearCoverageCounts();
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
		clearCoverageCounts();
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
