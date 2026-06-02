package com.clanclog;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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
import javax.swing.JToolTip;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.FlatTextField;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * Main clan clog surface. Layout mirrors Kill Clog: search header and
 * activities tray (clue tiers + rares) above the boss grid. The members list
 * lives in {@link ClanMembersPanel}. PluginPanel(true) wraps this surface in a
 * 7px MinimalScrollBarUI thumb, no nested scroll surfaces.
 *
 * <p>Data source priority per the backend-authoritative Clan Clog lock:
 * killclog.com aggregate profiles are the normal read path. The in-game clan
 * reader is the highest-trust source for roster/leader sync evidence, not the
 * normal aggregate compiler for public searches.
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
	private static final String ACTION_BASE_COLOR_PROPERTY = "clanclog.actionBaseColor";
	private static final String HEADER_BOOK_RESOURCE = "/com/clanclog/clanclog-book-28.png";

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
	private final TooltipController tooltipController;
	private final ItemManager itemManager;

	private final JLabel statusLabel = new JLabel(" ");
	private final JPanel coverageCounts = new JPanel(new GridBagLayout());
	private final JLabel hiscoreCoverageLabel = new JLabel("--");
	private final JLabel clogCoverageLabel = new JLabel("--");
	private final IconTextField searchBar = new IconTextField();

	private final JLabel clanHeader = new JLabel(" ")
	{
		@Override
		public JToolTip createToolTip()
		{
			return buildClanHeaderTooltip();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			paintUnderline(this, g, true);
		}
	};
	private final JLabel clanClogInfoLabel = new JLabel(" ")
	{
		@Override
		public JToolTip createToolTip()
		{
			return buildClanClogInfoTooltip();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			paintUnderline(this, g, false);
		}
	};
	private final JButton clanalyzeButton = new JButton("clanalyze");
	private final JButton syncButton = new JButton("sync");
	private final ActivitiesTray activitiesTray;
	private final Map<String, ImageIcon> clogTierIcons = new LinkedHashMap<>();

	/** Last backend/fixture ClanClogResult. Merged with hiscore data after batch completes. */
	@Nullable
	private ClanClogResult lastBackendResult;

	/** Last fully-rendered result (with boss + clog data). Used for rendering/cache continuity. */
	@Nullable
	private ClanClogResult lastRenderedResult;

	/** Roster from the last successful load. Used for cache/readback continuity. */
	@Nullable
	private List<ClanMember> lastLoadedRoster;

	/** Display name of the clan currently loaded. */
	@Nullable
	private String lastLoadedClanName;

	/** Slug of the last successfully loaded clan. Used for sync. */
	@Nullable
	private String lastLoadedSlug;

	/** True only after a completed in-client own-clan clanalyze render. */
	private boolean renderedFromClanalyze;

	/** True while the roster sync POST is in flight. */
	private boolean rosterSyncInFlight;

	/** True after killclog.com accepts the current roster snapshot. */
	private boolean rosterSyncAccepted;

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
		ClanLookupSession clanLookupSession, Cells cells, ClanMembersPanel membersPanel,
		TooltipController tooltipController, ItemManager itemManager)
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
		this.tooltipController = tooltipController;
		this.itemManager = itemManager;

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

		configureHeaderSyncButton(syncButton);
		syncButton.addActionListener(e -> onSyncClicked());

		c.insets = new Insets(0, 0, 4, 0);
		add(buildBrandHeader(), c);

		// Status + coverage sits above search so the profile row stays clean.
		c.gridy++;
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
		clanClogInfoLabel.setFont(FontManager.getRunescapeSmallFont());
		clanClogInfoLabel.setForeground(KC_TEXT);
		clanClogInfoLabel.setHorizontalAlignment(JLabel.RIGHT);
		clanClogInfoLabel.setBorder(new EmptyBorder(0, 0, 0, 4));
		clanClogInfoLabel.setMinimumSize(new Dimension(0, 0));
		clanClogInfoLabel.setIconTextGap(3);
		clanClogInfoLabel.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		setClanClogInfo(null);
		installSummaryLabelHover(clanHeader, clanClogInfoLabel);

		configureActionButton(clanalyzeButton, "build clan profile");
		clanalyzeButton.addActionListener(e -> onClanalyzeClicked());

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
		loadClogTierIcons();

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

	private JPanel buildBrandHeader()
	{
		JPanel row = new JPanel(new GridBagLayout());
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(0, 0, 4, 0));

		JPanel group = new JPanel(new GridBagLayout());
		group.setOpaque(false);

		GridBagConstraints hc = new GridBagConstraints();
		hc.gridy = 0;
		hc.gridx = 0;
		hc.weightx = 0;
		hc.insets = new Insets(0, 3, 0, 3);

		ImageIcon bookIcon = loadHeaderBook();
		if (bookIcon != null)
		{
			group.add(headerImageLabel(bookIcon, "Clan Clog"), hc);
			hc.gridx++;
		}

		JLabel title = new JLabel("Clan Clog");
		title.setFont(new Font("Courier New", Font.BOLD, 20));
		title.setForeground(KC4);
		title.setHorizontalAlignment(JLabel.CENTER);
		title.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		group.add(title, hc);
		if (bookIcon != null)
		{
			hc.gridx++;
			group.add(syncButton, hc);
		}

		row.add(group, centeredHeaderConstraints());
		return row;
	}

	@Nullable
	private static ImageIcon loadHeaderBook()
	{
		URL url = ClanClogPanel.class.getResource(HEADER_BOOK_RESOURCE);
		return url != null ? new ImageIcon(url) : null;
	}

	private static JLabel headerImageLabel(ImageIcon icon, String tooltip)
	{
		JLabel label = new JLabel(icon);
		label.setHorizontalAlignment(JLabel.CENTER);
		label.setToolTipText(tooltip);
		return label;
	}

	private static GridBagConstraints centeredHeaderConstraints()
	{
		GridBagConstraints hc = new GridBagConstraints();
		hc.gridx = 0;
		hc.gridy = 0;
		hc.weightx = 1;
		hc.anchor = GridBagConstraints.CENTER;
		return hc;
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
		clanHeader.setToolTipText(" ".equals(value) ? null : " ");
		clanHeader.setForeground(KC4);
	}

	private void installSummaryLabelHover(JLabel... labels)
	{
		for (JLabel label : labels)
		{
			label.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (config.tooltipMode() == TooltipMode.CLICK && label.getToolTipText() != null)
					{
						Container parent = label.getParent();
						if (parent instanceof JPanel)
						{
							tooltipController.showClickTooltip(label, (JPanel) parent);
						}
					}
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					if (label.getToolTipText() != null)
					{
						label.putClientProperty("underlined", true);
						label.repaint();
					}
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					label.putClientProperty("underlined", null);
					label.repaint();
				}
			});
		}
	}

	private JToolTip buildClanHeaderTooltip()
	{
		ClanClogResult result = lastRenderedResult != null ? lastRenderedResult : lastBackendResult;
		JToolTip tip = cells.buildClanSummaryTooltip(result);
		tip.setComponent(clanHeader);
		return tip;
	}

	private JToolTip buildClanClogInfoTooltip()
	{
		ClogSummaryTooltip tip = new ClogSummaryTooltip();
		tip.setComponent(clanClogInfoLabel);

		ClanClogResult result = lastRenderedResult != null ? lastRenderedResult : lastBackendResult;
		int obtained = totalClogCount(result);
		if (obtained <= 0)
		{
			tip.setNotice("No Collection Log Data");
			return tip;
		}

		int total = totalClogSlots(result);
		Map<String, BufferedImage> icons = new LinkedHashMap<>();
		for (Map.Entry<String, ImageIcon> entry : clogTierIcons.entrySet())
		{
			icons.put(entry.getKey(), ClogHelper.iconToImage(entry.getValue()));
		}
		tip.setTierData(obtained, total > 0 ? total : obtained, icons);

		String sync = shortDateText(result != null ? result.getClogLastChanged() : null);
		if (sync != null)
		{
			tip.setSyncData(sync, false);
		}
		return tip;
	}

	private static void paintUnderline(JLabel label, Graphics g, boolean leftAligned)
	{
		if (!Boolean.TRUE.equals(label.getClientProperty("underlined")))
		{
			return;
		}
		String text = label.getText();
		if (text == null || text.isBlank())
		{
			return;
		}
		FontMetrics fm = g.getFontMetrics();
		int textWidth = fm.stringWidth(text.trim());
		int y = (label.getHeight() + fm.getAscent() - fm.getDescent()) / 2 + 1;
		g.setColor(label.getForeground());
		if (leftAligned)
		{
			int iconOffset = label.getIcon() != null
				? label.getIcon().getIconWidth() + label.getIconTextGap() : 0;
			int textStart = label.getInsets().left + iconOffset;
			g.drawLine(textStart, y, textStart + textWidth, y);
		}
		else
		{
			int textEnd = label.getWidth() - label.getInsets().right;
			g.drawLine(textEnd - textWidth, y, textEnd, y);
		}
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
				int actionX = width - actionW;
				int infoX = Math.min(width, toggleX + toggleW + 4);
				int infoW = Math.max(0, actionX - infoX - 4);

				trayToggle.setBounds(toggleX, toggleY, toggleW, toggleH);
				clanClogInfoLabel.setBounds(infoX, 0, infoW, height);
				actions.setBounds(actionX, actionY, actionW, actionH);
				clanHeader.setBounds(0, 0, Math.max(0, toggleX - 4), height);
			}
		};
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setPreferredSize(new Dimension(0, 22));
		row.add(clanHeader);
		row.add(trayToggle);
		row.add(clanClogInfoLabel);
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
		setActionButtonBaseColor(button, KC4);
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
					applyActionButtonColor(button, actionButtonBaseColor(button));
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
						button.contains(e.getPoint()) ? KC2 : actionButtonBaseColor(button));
				}
			}
		});
	}

	private static void configureHeaderSyncButton(JButton button)
	{
		configureActionButton(button, "open clan tab to sync roster");
		ImageIcon icon = loadHeaderBook();
		if (icon != null)
		{
			button.setIcon(icon);
		}
		button.setText("");
		button.setMargin(new Insets(0, 0, 0, 0));
		button.setPreferredSize(new Dimension(32, 32));
		button.setMinimumSize(new Dimension(32, 32));
		button.setContentAreaFilled(false);
		button.setBorderPainted(true);
		button.setVisible(true);
		setActionButtonBaseColor(button, TEXT_DIM);
	}

	private static void setActionButtonBaseColor(JButton button, Color color)
	{
		button.putClientProperty(ACTION_BASE_COLOR_PROPERTY, color);
		applyActionButtonColor(button, color);
	}

	private static Color actionButtonBaseColor(JButton button)
	{
		Object value = button.getClientProperty(ACTION_BASE_COLOR_PROPERTY);
		return value instanceof Color ? (Color) value : KC4;
	}

	private static void applyActionButtonColor(JButton button, Color color)
	{
		button.setForeground(color);
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
		return "search a clan or open your clan tab";
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
		// your own clan. In-game clan data is only used for verified roster sync
		// and explicit bootstrap clanalyze when no backend profile exists.
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
	 * sync-eligible clanalyze run. Public clan search results should read an
	 * existing killclog.com aggregate profile instead of compiling in-client.
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
		clearLoadedProfileState();
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
		clearRenderedClanResult();
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
				renderClanResult(merged);
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
					setStatus("no public roster for \"" + query + "\"");
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
					setStatus("public roster unavailable");
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
				renderPublicRosterOnly(groupName, roster, null);
			});
		});
	}

	private void renderPublicRosterOnly(String clanName, List<ClanMember> roster,
		@Nullable ClanClogResult shell)
	{
		clearCurrentLoad();
		clearLoadedProfileState();
		clearCoverageCounts();
		hideSyncButton();
		hideClanalyzeButton();
		setClanHeaderText(clanName);
		clearSearchText();
		clearRenderedClanResult();
		membersPanel.renderRoster(clanName, roster);
		rememberClan(clanName);
		setStatus(publicRosterStatus(roster.size(), shell));
	}

	static String publicRosterStatus(int rosterSize, @Nullable ClanClogResult shell)
	{
		String memberCount = formatMemberCount(rosterSize);
		String buildStatus = shell != null ? shell.getBuildStatus() : null;
		if (buildStatus != null && buildStatus.equalsIgnoreCase("pending"))
		{
			return "profile pending · " + memberCount;
		}
		if (buildStatus != null && buildStatus.equalsIgnoreCase("building"))
		{
			return "profile building · " + memberCount;
		}
		if (buildStatus != null && buildStatus.equalsIgnoreCase("ready"))
		{
			return "no clog profile · " + memberCount;
		}
		return "public roster only · " + memberCount;
	}

	static String profileLoadedStatus(ClanClogResult result)
	{
		return " ";
	}

	static String cachedProfileStatus(ClanClogResult result)
	{
		return " ";
	}

	private static String formatMemberCount(int count)
	{
		return count == 1 ? "1 member" : String.format("%,d members", Math.max(0, count));
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
				renderClanResult(partialResult);
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
					// Stash state for cache/render continuity.
					lastRenderedResult = partialResult;
					lastLoadedRoster = roster;
					lastLoadedClanName = clanName;
					lastLoadedSlug = slug;
					renderedFromClanalyze = true;
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

				// Show sync only for the user's verified in-game roster. The
				// visible profile itself remains backend-authoritative.
				if (syncEligible)
				{
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
		boolean samePendingRoster = pendingRoster != null && sameRosterMembers(pendingRoster, roster);
		if (!samePendingRoster)
		{
			rosterSyncAccepted = false;
		}
		pendingRoster = new ArrayList<>(roster);
		pendingRosterSyncEligible = true;
		setClanHeaderText(name);
		clearSearchText();
		updateSyncButtonVisibility();
		if (preserveFreshClanalyzeRender(name, pendingRoster))
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

	private boolean preserveFreshClanalyzeRender(String clanName, List<ClanMember> roster)
	{
		String slug = slugify(clanName);
		if (slug.isEmpty()
			|| lastRenderedResult == null
			|| lastLoadedRoster == null
			|| lastLoadedSlug == null
			|| !renderedFromClanalyze
			|| !slug.equals(lastLoadedSlug)
			|| !lastRenderedResult.matchesSlug(slug)
			|| !sameRosterMembers(lastLoadedRoster, roster))
		{
			return false;
		}

		lastLoadedRoster = new ArrayList<>(roster);
		lastLoadedClanName = clanName;
		pendingRosterSyncEligible = true;
		renderClanResult(lastRenderedResult);
		membersPanel.renderRoster(clanName, roster);
		setCoverageFromResult(lastRenderedResult);
		updateSyncButtonVisibility();
		setStatus(profileLoadedStatus(lastRenderedResult));
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

				renderClanResult(result);
				membersPanel.renderRoster(clanName, roster);
				lastBackendResult = result;
				lastRenderedResult = result;
				lastLoadedRoster = roster;
				lastLoadedClanName = clanName;
				lastLoadedSlug = slug;
				renderedFromClanalyze = false;
				pendingRosterSyncEligible = true;
				setCoverageFromResult(result);
				updateSyncButtonVisibility();
				setStatus(profileLoadedStatus(result));
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

		renderClanResult(cached);
		membersPanel.renderRoster(clanName, roster);
		rememberClan(clanName);
		if (syncEligible)
		{
			renderedFromClanalyze = false;
			updateSyncButtonVisibility();
		}
		else
		{
			clearSyncState();
		}
		setCoverageCounts(hiscoreHits, clogHits);
		setStatus(cachedProfileStatus(cached));
		return true;
	}

	private void hideSyncButton()
	{
		syncButton.setEnabled(true);
		syncButton.setVisible(true);
		syncButton.setText("");
		syncButton.setPreferredSize(new Dimension(32, 32));
		syncButton.setToolTipText("open clan tab to sync roster");
		setActionButtonBaseColor(syncButton, TEXT_DIM);
		revalidate();
		repaint();
	}

	private void showSyncButton(String text, String tooltip, Color color)
	{
		syncButton.setText("");
		syncButton.setPreferredSize(new Dimension(32, 32));
		syncButton.setEnabled(true);
		syncButton.setToolTipText(tooltip);
		setActionButtonBaseColor(syncButton, color);
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
		renderedFromClanalyze = false;
		clearCurrentLoad();

		setClanHeaderText(name);
		clearSearchText();
		rememberClan(name);
		renderClanResult(result);
		membersPanel.renderRoster(name, roster);
		updateSyncButtonVisibility();
		setCoverageFromRoster(roster);
		if (!coverageCounts.isVisible())
		{
			setCoverageFromResult(result);
		}
		setStatus(" ");
		refreshStoredClanProfileFromBackend(slug);
		return true;
	}

	private void refreshStoredClanProfileFromBackend(String clanNameOrSlug)
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
				renderedFromClanalyze = false;

				setClanHeaderText(displayName);
				renderClanResult(result);
				if (roster != null && !roster.isEmpty())
				{
					membersPanel.renderRoster(displayName, roster);
				}
				setCoverageFromResult(result);
				updateSyncButtonVisibility();
				setStatus(" ");
			}));
	}

	private void showClanalyzeButton(String text, String tooltip)
	{
		clanalyzeButton.setText(text);
		clanalyzeButton.setIcon(null);
		clanalyzeButton.setMargin(new Insets(0, 5, 0, 5));
		clanalyzeButton.setPreferredSize(null);
		Dimension preferred = clanalyzeButton.getPreferredSize();
		clanalyzeButton.setPreferredSize(new Dimension(Math.max(preferred.width, 30), 18));
		clanalyzeButton.setToolTipText(tooltip);
		clanalyzeButton.setEnabled(true);
		setActionButtonBaseColor(clanalyzeButton, KC4);
		clanalyzeButton.setVisible(true);
	}

	private void hideClanalyzeButton()
	{
		clanalyzeButton.setEnabled(true);
		clanalyzeButton.setVisible(false);
		clanalyzeButton.setIcon(null);
		clanalyzeButton.setText("clanalyze");
		clanalyzeButton.setMargin(new Insets(0, 5, 0, 5));
		clanalyzeButton.setToolTipText("build clan profile");
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
		clearLoadedProfileState();
		pendingRosterSyncEligible = false;
		rosterSyncInFlight = false;
		rosterSyncAccepted = false;
		hideSyncButton();
	}

	private void clearLoadedProfileState()
	{
		lastRenderedResult = null;
		lastLoadedRoster = null;
		lastLoadedClanName = null;
		lastLoadedSlug = null;
		renderedFromClanalyze = false;
	}

	/**
	 * Show the sync button only when a verified in-game roster is loaded. The
	 * color and click gate reflect whether the captured roster proves an
	 * OWNER / DEPUTY_OWNER local player can publish it.
	 */
	private void updateSyncButtonVisibility()
	{
		if (!hasSyncRosterPayload())
		{
			hideSyncButton();
			return;
		}
		if (rosterSyncInFlight)
		{
			showSyncButton("syncing...", "syncing roster to killclog.com", KC2);
			return;
		}
		if (rosterSyncAccepted)
		{
			showSyncButton("roster synced", "roster accepted by killclog.com", KC1);
			return;
		}
		boolean canSync = hasSyncAuthority();
		showSyncButton("sync roster", canSync ? "sync roster to killclog.com" : syncGateTooltip(),
			canSync ? KC4 : TEXT_DIM);
	}

	private boolean hasSyncRosterPayload()
	{
		return pendingRosterSyncEligible
			&& pendingRoster != null
			&& !pendingRoster.isEmpty()
			&& pendingClanName != null
			&& !slugify(pendingClanName).isEmpty();
	}

	private boolean hasSyncAuthority()
	{
		return config.enableSync()
			&& syncKeyRank() != null
			&& syncOwnerRsn() != null;
	}

	private String syncGateTooltip()
	{
		if (!config.enableSync())
		{
			return "enable sync in plugin config";
		}
		if (!hasSyncRosterPayload())
		{
			return "open clan tab to sync roster";
		}
		if (syncOwnerRsn() == null)
		{
			return "log in and open clan tab to sync";
		}
		if (syncKeyRank() == null)
		{
			return "owner or deputy rank required to sync";
		}
		return "sync roster to killclog.com";
	}

	@Nullable
	private String syncOwnerRsn()
	{
		return syncOwnerRsn(clanReader.localPlayerName(), pendingRoster);
	}

	@Nullable
	static String syncOwnerRsn(@Nullable String localName, @Nullable List<ClanMember> roster)
	{
		String localKey = normalize(localName);
		if (localKey.isEmpty())
		{
			return null;
		}
		if (roster != null)
		{
			for (ClanMember member : roster)
			{
				if (member != null && normalize(member.getRsn()).equals(localKey))
				{
					return member.getRsn();
				}
			}
		}
		return RsnNormalizer.normalize(localName);
	}

	@Nullable
	private String syncKeyRank()
	{
		String readerRank = clanReader.localPlayerKeyRank();
		if (isSyncKeyRank(readerRank))
		{
			return readerRank;
		}
		return syncKeyRank(clanReader.localPlayerName(), pendingRoster);
	}

	@Nullable
	static String syncKeyRank(@Nullable String localName, @Nullable List<ClanMember> roster)
	{
		String localKey = normalize(localName);
		if (localKey.isEmpty() || roster == null)
		{
			return null;
		}
		for (ClanMember member : roster)
		{
			if (member != null && normalize(member.getRsn()).equals(localKey)
				&& isSyncKeyRank(member.getRankName()))
			{
				return member.getRankName();
			}
		}
		return null;
	}

	static boolean isSyncKeyRank(@Nullable String rank)
	{
		return "OWNER".equals(rank) || "DEPUTY_OWNER".equals(rank);
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
	 * Sync the current clan roster to killclog.com. Manual only, never fires
	 * automatically. Gated on OWNER / DEPUTY_OWNER rank. Aggregate truth is
	 * re-read from the backend after the roster snapshot lands.
	 */
	private void onSyncClicked()
	{
		if (rosterSyncInFlight)
		{
			setStatus("syncing roster to killclog.com...");
			return;
		}
		if (!hasSyncRosterPayload())
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

		String keyRank = syncKeyRank();
		String ownerRsn = syncOwnerRsn();
		if (keyRank == null || ownerRsn == null || pendingRoster == null
			|| pendingClanName == null)
		{
			updateSyncButtonVisibility();
			setStatus(syncGateTooltip());
			return;
		}

		String clanName = pendingClanName;
		String slug = slugify(clanName);
		List<ClanMember> roster = new ArrayList<>(pendingRoster);
		final int version = loadVersion;
		if (slug.isEmpty())
		{
			updateSyncButtonVisibility();
			setStatus("sync failed: missing clan");
			return;
		}

		lastLoadedRoster = roster;
		lastLoadedClanName = clanName;
		lastLoadedSlug = slug;
		rosterSyncInFlight = true;
		rosterSyncAccepted = false;
		updateSyncButtonVisibility();
		setStatus("syncing roster to killclog.com...");

		apiClient.syncRoster(slug, clanName, ownerRsn, keyRank, roster)
			.whenComplete((resp, ex) ->
				SwingUtilities.invokeLater(() ->
				{
					rosterSyncInFlight = false;
					if (ex == null && resp != null && resp.isOk())
					{
						refreshBackendProfileAfterSync(slug, clanName, roster, version, resp);
					}
					else if (resp != null)
					{
						rosterSyncAccepted = false;
						updateSyncButtonVisibility();
						setStatus("sync failed: " + resp.describe());
					}
					else
					{
						rosterSyncAccepted = false;
						updateSyncButtonVisibility();
						setStatus("sync failed"
							+ (ex != null ? ": " + ex.getMessage() : ""));
					}
				}));
	}

	private void refreshBackendProfileAfterSync(String slug, String clanName,
		List<ClanMember> roster, int version, KillclogApiClient.SyncResponse syncResponse)
	{
		renderedFromClanalyze = false;
		rosterSyncAccepted = true;
		pendingRosterSyncEligible = true;
		updateSyncButtonVisibility();
		String syncSummary = syncResponse.describeSuccess();
		setStatus("roster synced" + syncSummary + " · refreshing profile...");

		apiClient.fetchClanClog(slug).whenComplete((result, ex) ->
			SwingUtilities.invokeLater(() ->
			{
				if (version != loadVersion || currentLoadSlug != null
					|| lastLoadedSlug == null || !slug.equals(lastLoadedSlug))
				{
					return;
				}
				if (ex != null || result == null || !result.hasRepresentedData())
				{
					updateSyncButtonVisibility();
					setStatus("roster synced to killclog.com/c/" + slug + syncSummary
						+ " · profile refresh unavailable");
					return;
				}

				String displayName = result.getDisplayName();
				if (displayName == null || displayName.isBlank())
				{
					displayName = clanName;
				}
				List<ClanMember> resultRoster = !result.getMembers().isEmpty()
					? new ArrayList<>(result.getMembers()) : roster;

				pendingClanName = displayName;
				pendingRoster = resultRoster;
				pendingRosterSyncEligible = true;
				lastBackendResult = result;
				lastRenderedResult = result;
				lastLoadedRoster = resultRoster;
				lastLoadedClanName = displayName;
				lastLoadedSlug = slug;
				renderedFromClanalyze = false;

				setClanHeaderText(displayName);
				renderClanResult(result);
				membersPanel.renderRoster(displayName, resultRoster);
				setCoverageFromResult(result);
				clanProfileCache.put(displayName, slug, resultRoster, result);
				updateSyncButtonVisibility();
				setStatus("roster synced to killclog.com/c/" + slug + syncSummary);
			}));
	}

	private void renderClanResult(ClanClogResult result)
	{
		cells.renderClanResult(result);
		setClanClogInfo(result);
	}

	private void clearRenderedClanResult()
	{
		cells.clearCells();
		setClanClogInfo(null);
	}

	private void setClanClogInfo(@Nullable ClanClogResult result)
	{
		clanClogInfoLabel.setText(totalClogInfoText(result));
		clanClogInfoLabel.setToolTipText(totalClogCount(result) > 0 ? " " : null);
		clanClogInfoLabel.setForeground(totalClogInfoColor(result));
		clanClogInfoLabel.setIcon(totalClogInfoIcon(result));
		revalidate();
		repaint();
	}

	@Override
	public void onActivate()
	{
		tooltipController.captureDefaults(this);
	}

	@Override
	public void onDeactivate()
	{
		tooltipController.restoreDefaults();
	}

	@Override
	public void removeNotify()
	{
		super.removeNotify();
		tooltipController.hideClickTooltip();
	}

	@Nullable
	private ImageIcon totalClogInfoIcon(@Nullable ClanClogResult result)
	{
		String tierName = totalClogTierName(result);
		return tierName != null ? clogTierIcons.get(tierName) : null;
	}

	static String totalClogInfoText(@Nullable ClanClogResult result)
	{
		int total = totalClogCount(result);
		return total > 0 ? ClogHelper.pad(ClogHelper.formatKc(total)) : " ";
	}

	@Nullable
	static String shortDateText(@Nullable String raw)
	{
		if (raw == null || raw.isBlank())
		{
			return null;
		}
		int dateEnd = raw.indexOf('T');
		if (dateEnd > 0)
		{
			return raw.substring(0, dateEnd);
		}
		return raw.length() > 10 ? raw.substring(0, 10) : raw;
	}

	static Color totalClogInfoColor(@Nullable ClanClogResult result)
	{
		int obtained = totalClogCount(result);
		if (obtained <= 0)
		{
			return KC_TEXT;
		}
		int total = totalClogSlots(result);
		return total > 0 ? ClogHelper.clogColor(obtained, total)
			: ClogHelper.COLOR_IN_PROGRESS;
	}

	@Nullable
	static String totalClogTierName(@Nullable ClanClogResult result)
	{
		int obtained = totalClogCount(result);
		if (obtained <= 0)
		{
			return null;
		}
		int total = totalClogSlots(result);
		return ClogHelper.getClogTierName(obtained, total > 0 ? total : obtained);
	}

	private static int totalClogCount(@Nullable ClanClogResult result)
	{
		ClanClogResult.ClogUnion clog = result != null ? result.getClog() : null;
		return clog != null ? clog.getTotalObtained() : 0;
	}

	private static int totalClogSlots(@Nullable ClanClogResult result)
	{
		ClanClogResult.ClogUnion clog = result != null ? result.getClog() : null;
		if (clog == null)
		{
			return 0;
		}
		Set<Integer> ids = new HashSet<>();
		for (List<Integer> categoryIds : clog.getCatalogByCategory().values())
		{
			if (categoryIds != null)
			{
				ids.addAll(categoryIds);
			}
		}
		if (!ids.isEmpty())
		{
			return ids.size();
		}
		for (List<Integer> categoryIds : clog.getItemsByCategory().values())
		{
			if (categoryIds != null)
			{
				ids.addAll(categoryIds);
			}
		}
		return ids.size();
	}

	private void loadClogTierIcons()
	{
		for (int i = 0; i < ClogHelper.CLOG_TIERS.length; i++)
		{
			final String tier = ClogHelper.CLOG_TIERS[i];
			final int itemId = ClogHelper.CLOG_TIER_ITEM_IDS[i];
			loadItemIcon(itemId, 13, 13, icon ->
			{
				clogTierIcons.put(tier, icon);
				setClanClogInfo(lastRenderedResult);
			});
		}
	}

	private void loadItemIcon(int itemId, int width, int height, Consumer<ImageIcon> setter)
	{
		BufferedImage img = itemManager.getImage(itemId, 1, false);
		if (img == null)
		{
			return;
		}
		setter.accept(new ImageIcon(ImageUtil.resizeImage(img, width, height)));
		if (img instanceof AsyncBufferedImage)
		{
			((AsyncBufferedImage) img).onLoaded(() ->
				SwingUtilities.invokeLater(() ->
					setter.accept(new ImageIcon(ImageUtil.resizeImage(img, width, height)))));
		}
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
		if (value.startsWith("roster synced"))
		{
			return KC1;
		}
		if (value.startsWith("syncing"))
		{
			return KC2;
		}
		if (value.startsWith("done") || value.startsWith("synced")
			|| value.startsWith("clan profile") || value.startsWith("profile loaded"))
		{
			return ClogHelper.COLOR_COMPLETED;
		}
		if (value.startsWith("profile building"))
		{
			return KC2;
		}
		if (value.startsWith("ready") || value.startsWith("matched")
			|| value.startsWith("public roster") || value.startsWith("profile pending")
			|| value.startsWith("cached profile"))
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
		setStatus("checking killclog.com...");
		clearCoverageCounts();
		lastBackendResult = null;
		clearSyncState();
		clanalyzeButton.setVisible(false);
		clearRenderedClanResult();
		membersPanel.showPlaceholder("checking killclog.com");
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
			renderPublicRosterOnly(name != null && !name.isEmpty() ? name : slug,
				new ArrayList<>(result.getMembers()), result);
			return;
		}
		if (!result.hasRepresentedData())
		{
			clearCoverageCounts();
			clearRenderedClanResult();
			setStatus("no clog profile for " + slug);
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
		setStatus(profileLoadedStatus(result));
		renderClanResult(result);
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
		clearRenderedClanResult();
		// Backend has no record of this clan -- fall back to a WOM roster view.
		if (viewQuery != null && !viewQuery.isBlank())
		{
			setStatus("not synced · checking public rosters...");
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
		clearRenderedClanResult();
		// Backend unreachable/errored -- try WOM so the user still sees a roster.
		if (viewQuery != null && !viewQuery.isBlank())
		{
			setStatus("killclog unavailable · checking rosters...");
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
