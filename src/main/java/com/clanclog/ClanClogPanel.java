package com.clanclog;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Polygon;
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
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToolTip;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.FlatTextField;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

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
	private static final String HEADER_BOOK_RESOURCE = "/com/clanclog/clanclog-book-28.png";
	private static final String CLAN_LEADERBOARD_URL = "https://killclog.com/c/";
	private static final int SYNC_CONFIRMATION_TICKS = 2;
	private static final float SYNC_IDLE_ALPHA = 0.5f;
	private static final Dimension SYNC_BUTTON_SIZE = new Dimension(32, 32);
	private static final Dimension SYNC_READY_BUTTON_SIZE = new Dimension(34, 54);

	private final class HeaderLinkButton extends JButton
	{
		private boolean hovered;

		HeaderLinkButton(ImageIcon icon)
		{
			super(icon);
			setHorizontalAlignment(JLabel.CENTER);
			setToolTipText(currentClanLinkText());
			setMargin(new Insets(0, 0, 0, 0));
			setPreferredSize(SYNC_BUTTON_SIZE);
			setMinimumSize(SYNC_BUTTON_SIZE);
			setFocusPainted(false);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setBorder(new EmptyBorder(0, 0, 0, 0));
			setOpaque(false);
			addActionListener(e -> LinkBrowser.browse(currentClanProfileUrl()));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hovered = true;
					setToolTipText(currentClanLinkText());
					showClanLinkHoverStatus();
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hovered = false;
					restoreClanLinkHoverStatus();
					repaint();
				}
			});
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (!hovered)
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setComposite(AlphaComposite.SrcOver.derive(0.65f));
				g2.setColor(KC1);
				g2.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
			}
			finally
			{
				g2.dispose();
			}
		}
	}

	private static final class HeaderSyncButton extends JButton
	{
		private Icon bookIcon;
		private boolean arrowVisible;
		private boolean readyPrompt;
		private boolean arrowHovered;
		private Color arrowColor = Color.WHITE;
		private float arrowAlpha = 0.3f;

		void setBookIcon(@Nullable Icon icon)
		{
			bookIcon = icon;
			repaint();
		}

		void setSyncPrompt(boolean visible, boolean ready, Color color, float alpha)
		{
			arrowVisible = visible;
			readyPrompt = ready;
			arrowColor = color;
			arrowAlpha = alpha;
			if (!visible)
			{
				arrowHovered = false;
			}
			repaint();
		}

		void setArrowHovered(boolean hovered)
		{
			if (arrowHovered != hovered)
			{
				arrowHovered = hovered;
				repaint();
			}
		}

		boolean isReadyPrompt()
		{
			return arrowVisible && readyPrompt;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				int iconWidth = bookIcon != null ? bookIcon.getIconWidth() : 28;
				int iconHeight = bookIcon != null ? bookIcon.getIconHeight() : 28;
				int iconX = Math.max(0, (getWidth() - iconWidth) / 2);
				int iconY = Math.max(0, (getHeight() - iconHeight) / 2);
				if (bookIcon != null)
				{
					bookIcon.paintIcon(this, g2, iconX, iconY);
				}
				if (readyPrompt)
				{
					float idleAlpha = arrowHovered ? 1.0f : SYNC_IDLE_ALPHA;
					g2.setComposite(AlphaComposite.SrcOver.derive(idleAlpha));
					g2.setColor(KC1);
					g2.drawRect(iconX, iconY, iconWidth - 1, iconHeight - 1);
					FontMetrics fm = g2.getFontMetrics();
					String text = "sync";
					int textX = Math.max(0, (getWidth() - fm.stringWidth(text)) / 2);
					int textY = Math.min(getHeight() - fm.getDescent(),
						iconY + iconHeight + fm.getAscent());
					g2.drawString(text, textX, textY);
				}
				if (!arrowVisible)
				{
					return;
				}
				float alpha = readyPrompt && arrowHovered ? 1.0f : arrowAlpha;
				Color color = readyPrompt && arrowHovered ? KC1 : arrowColor;
				g2.setComposite(AlphaComposite.SrcOver.derive(alpha));
				g2.setColor(color);
				int cx = getWidth() / 2;
				int top = iconY + 5;
				Polygon head = new Polygon(
					new int[]{cx, cx - 5, cx + 5},
					new int[]{top, top + 7, top + 7},
					3);
				g2.fillPolygon(head);
				g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND,
					BasicStroke.JOIN_ROUND));
				g2.drawLine(cx, top + 6, cx, top + 17);
			}
			finally
			{
				g2.dispose();
			}
		}
	}

	private final ClanClogConfig config;
	private final ConfigManager configManager;
	private final WomClient womClient;
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
	private final HeaderSyncButton syncButton = new HeaderSyncButton();
	private final ActivitiesTray activitiesTray;
	private final Map<String, ImageIcon> clogTierIcons = new LinkedHashMap<>();

	/** Last backend/fixture ClanClogResult used for render continuity. */
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

	/** True while the roster sync POST is in flight. */
	private boolean rosterSyncInFlight;

	/** True after killclog.com accepts the current roster snapshot. */
	private boolean rosterSyncAccepted;

	/** True once the accepted sync prompt has faded away for the current roster. */
	private boolean syncAffordanceDismissed;

	/** Remaining game ticks before the accepted sync affordance clears. */
	private int syncConfirmationTicksRemaining;

	/** Exact transient status text owned by the accepted sync confirmation. */
	@Nullable
	private String syncConfirmationStatusText;

	/** Status text that was visible before sync-arrow hover copy temporarily replaced it. */
	@Nullable
	private String statusBeforeSyncHover;

	@Nullable
	private Color statusColorBeforeSyncHover;

	/** Status text visible before clan-link hover copy temporarily replaced it. */
	@Nullable
	private String statusBeforeClanLinkHover;

	@Nullable
	private String clanLinkHoverStatusText;

	@Nullable
	private Color statusColorBeforeClanLinkHover;

	/** Slug of the clan currently loading/loaded. Guards against duplicate lookups. */
	@Nullable
	private String currentLoadSlug;

	/** loadVersion that owns {@link #currentLoadSlug}; prevents stale completions clearing newer loads. */
	private int currentLoadVersion = -1;

	/**
	 * Monotonic token bumped on every user-initiated lookup. Async backend/WOM
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

	/** Roster pending sync/profile read. */
	@Nullable
	private List<ClanMember> pendingRoster;

	/** Clan name for pending roster sync/profile read. */
	@Nullable
	private String pendingClanName;

	/** True only when the pending roster came from the verified in-game clan slot. */
	private boolean pendingRosterSyncEligible;

	/** True only for Dylan's temporary operator import from guest ClanSettings. */
	private boolean pendingRosterOperatorEligible;

	/** Source slot for the pending roster: primary, secondary, or guest. */
	@Nullable
	private String pendingRosterSourceSlot;

	@Inject
	public ClanClogPanel(ClanClogConfig config, ConfigManager configManager,
		WomClient womClient, LocalHiscoreCache hiscoreCache, ClogFetchService clogFetchService,
		LocalClanProfileCache clanProfileCache,
		InGameClanReader clanReader, KillclogApiClient apiClient,
		ClanLookupSession clanLookupSession, Cells cells, ClanMembersPanel membersPanel,
		TooltipController tooltipController, ItemManager itemManager)
	{
		super(true);
		this.config = config;
		this.configManager = configManager;
		this.womClient = womClient;
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
			group.add(new HeaderLinkButton(bookIcon), hc);
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

				int infoX = Math.min(width, toggleX + toggleW + 4);
				int infoW = Math.max(0, width - infoX);

				trayToggle.setBounds(toggleX, toggleY, toggleW, toggleH);
				clanClogInfoLabel.setBounds(infoX, 0, infoW, height);
				clanHeader.setBounds(0, 0, Math.max(0, toggleX - 4), height);
			}
		};
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setPreferredSize(new Dimension(0, 22));
		row.add(clanHeader);
		row.add(trayToggle);
		row.add(clanClogInfoLabel);

		return row;
	}

	private void configureHeaderSyncButton(HeaderSyncButton button)
	{
		button.setBookIcon(loadHeaderBook());
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setText("");
		button.setMargin(new Insets(0, 0, 0, 0));
		button.setPreferredSize(SYNC_BUTTON_SIZE);
		button.setMinimumSize(SYNC_BUTTON_SIZE);
		button.setFocusPainted(false);
		button.setContentAreaFilled(false);
		button.setBorderPainted(false);
		button.setBorder(new EmptyBorder(0, 0, 0, 0));
		button.setOpaque(false);
		button.setToolTipText(null);
		button.setVisible(true);
		button.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (button.isReadyPrompt())
				{
					button.setArrowHovered(true);
					showSyncHoverStatus();
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (button.isReadyPrompt())
				{
					button.setArrowHovered(false);
					restoreSyncHoverStatus();
				}
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (button.isReadyPrompt() && SwingUtilities.isLeftMouseButton(e))
				{
					button.setArrowHovered(true);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (button.isReadyPrompt())
				{
					button.setArrowHovered(button.contains(e.getPoint()));
				}
			}
		});
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
		// your own clan. In-game clan data is only used for verified roster sync.
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
	 * Called whenever {@link InGameClanReader} refreshes (user opens their clan
	 * tab in-game). Populates the header, roster view, and sync affordance.
	 * Normal users work from their own clan; Dylan's pre-public operator token
	 * can temporarily prefer the guest ClanSettings roster so big clans can be
	 * queued before public release.
	 */
	private void onInGameRosterRefreshed(List<ClanMember> roster)
	{
		if (roster == null || roster.isEmpty())
		{
			return;
		}
		InGameClanReader.RosterSnapshot operatorSnapshot = operatorSyncEnabled()
			? clanReader.currentGuestSnapshot() : null;
		boolean operatorRoster = operatorSnapshot != null;
		List<ClanMember> activeRoster = operatorRoster
			? operatorSnapshot.getRoster() : roster;
		// Showing the live in-game roster supersedes a pending backend VIEW, so
		// invalidate its listener guard (onClanResult/NotFound/Error bail when
		// viewVersion != loadVersion). Do NOT bump loadVersion here: this method
		// fires on every clan-tab open, and bumping the shared token would cancel
		// an in-flight backend profile refresh.
		viewVersion = -1;
		String name = operatorRoster ? operatorSnapshot.getClanName() : clanReader.currentClanName();
		if (name == null)
		{
			name = "my clan";
		}
		pendingClanName = name;
		boolean samePendingRoster = pendingRoster != null && sameRosterMembers(pendingRoster, activeRoster);
		if (!samePendingRoster)
		{
			rosterSyncAccepted = false;
			syncAffordanceDismissed = false;
			cancelSyncConfirmationClear();
		}
		pendingRoster = new ArrayList<>(activeRoster);
		pendingRosterSyncEligible = !operatorRoster;
		pendingRosterOperatorEligible = operatorRoster;
		pendingRosterSourceSlot = operatorRoster ? operatorSnapshot.getSourceSlot() : "primary";
		setClanHeaderText(name);
		clearSearchText();
		updateSyncButtonVisibility();
		if (!renderCachedClanProfile(name, pendingRoster, true))
		{
			membersPanel.renderRoster(name, pendingRoster);
			String rosterStatus = "roster captured";
			if (hasSyncAuthority())
			{
				rosterStatus = operatorRoster
					? "guest roster captured · queue sync available"
					: "roster captured · sync available";
			}
			setStatus(rosterStatus);
		}
		renderBackendOwnClanProfile(name, new ArrayList<>(pendingRoster));
		revalidate();
		repaint();
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
				if (!pendingRosterOperatorEligible)
				{
					pendingRosterSyncEligible = true;
				}
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
		syncButton.setPreferredSize(SYNC_BUTTON_SIZE);
		syncButton.setToolTipText(null);
		syncButton.setBorderPainted(false);
		syncButton.setBorder(new EmptyBorder(0, 0, 0, 0));
		syncButton.setSyncPrompt(false, false, Color.WHITE, 0.0f);
		revalidate();
		repaint();
	}

	private void showSyncButton(String text, Color color)
	{
		boolean ready = "sync roster".equals(text) || "queue roster".equals(text);
		syncButton.setText("");
		syncButton.setPreferredSize(ready ? SYNC_READY_BUTTON_SIZE : SYNC_BUTTON_SIZE);
		syncButton.setEnabled(true);
		syncButton.setToolTipText(null);
		syncButton.setBorderPainted(false);
		syncButton.setBorder(new EmptyBorder(0, 0, 0, 0));
		syncButton.setSyncPrompt(true, ready, ready ? Color.WHITE : color,
			ready ? 0.3f : 1.0f);
		syncButton.setVisible(true);
		revalidate();
		repaint();
	}

	private String syncHoverText()
	{
		return pendingRosterOperatorEligible ? "queue guest roster" : "sync clan roster";
	}

	private void showSyncHoverStatus()
	{
		if (statusBeforeSyncHover == null)
		{
			statusBeforeSyncHover = statusLabel.getText();
			statusColorBeforeSyncHover = statusLabel.getForeground();
		}
		setStatus(syncHoverText(), Color.WHITE);
	}

	private void restoreSyncHoverStatus()
	{
		if (statusBeforeSyncHover != null && syncHoverText().equals(statusLabel.getText()))
		{
			Color color = statusColorBeforeSyncHover != null
				? statusColorBeforeSyncHover : statusColor(statusBeforeSyncHover);
			setStatus(statusBeforeSyncHover, color);
		}
		clearSyncHoverStatus();
	}

	private void clearSyncHoverStatus()
	{
		statusBeforeSyncHover = null;
		statusColorBeforeSyncHover = null;
	}

	private String currentClanLinkText()
	{
		String slug = currentClanProfileSlug();
		return slug == null ? "killclog.com/c/" : "killclog.com/c/" + slug;
	}

	private String currentClanProfileUrl()
	{
		String slug = currentClanProfileSlug();
		return slug == null ? CLAN_LEADERBOARD_URL : CLAN_LEADERBOARD_URL + slug;
	}

	@Nullable
	private String currentClanProfileSlug()
	{
		if (lastLoadedSlug != null && !lastLoadedSlug.isBlank())
		{
			return lastLoadedSlug;
		}
		if (pendingClanName != null)
		{
			String slug = slugify(pendingClanName);
			if (!slug.isEmpty())
			{
				return slug;
			}
		}
		return null;
	}

	private void showClanLinkHoverStatus()
	{
		if (statusBeforeClanLinkHover == null)
		{
			statusBeforeClanLinkHover = statusLabel.getText();
			statusColorBeforeClanLinkHover = statusLabel.getForeground();
		}
		clanLinkHoverStatusText = currentClanLinkText();
		setStatus(clanLinkHoverStatusText, Color.WHITE);
	}

	private void restoreClanLinkHoverStatus()
	{
		if (statusBeforeClanLinkHover != null
			&& clanLinkHoverStatusText != null
			&& clanLinkHoverStatusText.equals(statusLabel.getText()))
		{
			Color color = statusColorBeforeClanLinkHover != null
				? statusColorBeforeClanLinkHover : statusColor(statusBeforeClanLinkHover);
			setStatus(statusBeforeClanLinkHover, color);
		}
		clearClanLinkHoverStatus();
	}

	private void clearClanLinkHoverStatus()
	{
		statusBeforeClanLinkHover = null;
		statusColorBeforeClanLinkHover = null;
		clanLinkHoverStatusText = null;
	}

	private void cancelSyncConfirmationClear()
	{
		syncConfirmationTicksRemaining = 0;
		syncConfirmationStatusText = null;
	}

	void onGameTick()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::onGameTick);
			return;
		}
		if (syncConfirmationTicksRemaining <= 0)
		{
			return;
		}
		syncConfirmationTicksRemaining--;
		if (syncConfirmationTicksRemaining == 0)
		{
			clearAcceptedSyncAffordance();
		}
	}

	private void clearAcceptedSyncAffordance()
	{
		String confirmationText = syncConfirmationStatusText;
		syncConfirmationStatusText = null;
		syncAffordanceDismissed = true;
		rosterSyncAccepted = false;
		clearSyncHoverStatus();
		hideSyncButton();
		if (confirmationText != null && confirmationText.equals(statusLabel.getText()))
		{
			setStatus(" ");
		}
	}

	private String syncConfirmationText(String slug)
	{
		return pendingRosterOperatorEligible
			? "roster queued to killclog.com/c/" + slug
			: "roster synced to killclog.com/c/" + slug;
	}

	private void showSyncConfirmation(String slug)
	{
		String text = syncConfirmationText(slug);
		syncConfirmationStatusText = text;
		syncConfirmationTicksRemaining = SYNC_CONFIRMATION_TICKS;
		setStatus(text, KC1);
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
		pendingRosterOperatorEligible = false;
		pendingRosterSourceSlot = null;
		lastRenderedResult = result;
		lastLoadedRoster = roster;
		lastLoadedClanName = name;
		lastLoadedSlug = slug;
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

	private String operatorSyncToken()
	{
		String token = config.operatorSyncToken();
		return token != null ? token.trim() : "";
	}

	private boolean operatorSyncEnabled()
	{
		return config.enableSync() && !operatorSyncToken().isEmpty();
	}

	private void clearSyncState()
	{
		clearLoadedProfileState();
		pendingRosterSyncEligible = false;
		pendingRosterOperatorEligible = false;
		pendingRosterSourceSlot = null;
		rosterSyncInFlight = false;
		rosterSyncAccepted = false;
		syncAffordanceDismissed = false;
		cancelSyncConfirmationClear();
		clearSyncHoverStatus();
		hideSyncButton();
	}

	private void clearLoadedProfileState()
	{
		lastRenderedResult = null;
		lastLoadedRoster = null;
		lastLoadedClanName = null;
		lastLoadedSlug = null;
	}

	/**
	 * Show the sync button only when a publishable in-game roster is loaded.
	 * Owner/deputy rosters use public sync auth; the temporary operator path
	 * queues a guest ClanSettings roster with the internal rebuild token.
	 */
	private void updateSyncButtonVisibility()
	{
		if (!hasSyncRosterPayload())
		{
			hideSyncButton();
			return;
		}
		if (syncAffordanceDismissed)
		{
			hideSyncButton();
			return;
		}
		if (rosterSyncInFlight)
		{
			showSyncButton("syncing...", KC2);
			return;
		}
		if (rosterSyncAccepted)
		{
			showSyncButton(pendingRosterOperatorEligible ? "roster queued" : "roster synced", KC1);
			return;
		}
		boolean canSync = hasSyncAuthority();
		if (!canSync)
		{
			hideSyncButton();
			return;
		}
		showSyncButton(pendingRosterOperatorEligible ? "queue roster" : "sync roster", KC4);
	}

	private boolean hasSyncRosterPayload()
	{
		return (pendingRosterSyncEligible || pendingRosterOperatorEligible)
			&& pendingRoster != null
			&& !pendingRoster.isEmpty()
			&& pendingClanName != null
			&& !slugify(pendingClanName).isEmpty();
	}

	private boolean hasSyncAuthority()
	{
		if (pendingRosterOperatorEligible)
		{
			return operatorSyncEnabled();
		}
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
		if (pendingRosterOperatorEligible && operatorSyncToken().isEmpty())
		{
			return "operator token required to queue guest roster";
		}
		if (pendingRosterOperatorEligible)
		{
			return "queue guest roster for killclog.com rebuild";
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
		if (!rosterSyncInFlight && !syncButton.isReadyPrompt())
		{
			return;
		}
		clearSyncHoverStatus();
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
		if (pendingRosterOperatorEligible)
		{
			onOperatorSyncClicked();
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
		syncAffordanceDismissed = false;
		cancelSyncConfirmationClear();
		updateSyncButtonVisibility();
		setStatus("syncing roster to killclog.com...");

		apiClient.syncRoster(slug, clanName, ownerRsn, keyRank, roster)
			.whenComplete((resp, ex) ->
				SwingUtilities.invokeLater(() ->
				{
					rosterSyncInFlight = false;
					if (ex == null && resp != null && resp.isOk())
					{
						refreshBackendProfileAfterSync(slug, clanName, roster, version);
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

	private void onOperatorSyncClicked()
	{
		clearSyncHoverStatus();
		String operatorToken = operatorSyncToken();
		String observedBy = clanReader.localPlayerName();
		if (operatorToken.isEmpty() || observedBy == null || pendingRoster == null
			|| pendingClanName == null)
		{
			updateSyncButtonVisibility();
			setStatus(syncGateTooltip());
			return;
		}

		String clanName = pendingClanName;
		String slug = slugify(clanName);
		List<ClanMember> roster = new ArrayList<>(pendingRoster);
		String sourceSlot = pendingRosterSourceSlot != null ? pendingRosterSourceSlot : "guest";
		final int version = loadVersion;
		if (slug.isEmpty())
		{
			updateSyncButtonVisibility();
			setStatus("queue failed: missing clan");
			return;
		}

		lastLoadedRoster = roster;
		lastLoadedClanName = clanName;
		lastLoadedSlug = slug;
		rosterSyncInFlight = true;
		rosterSyncAccepted = false;
		syncAffordanceDismissed = false;
		cancelSyncConfirmationClear();
		updateSyncButtonVisibility();
		setStatus("queueing roster on killclog.com...");

		apiClient.operatorSyncRoster(slug, clanName, observedBy, operatorToken, sourceSlot, roster)
			.whenComplete((resp, ex) ->
				SwingUtilities.invokeLater(() ->
				{
					rosterSyncInFlight = false;
					if (ex == null && resp != null && resp.isOk())
					{
						refreshBackendProfileAfterSync(slug, clanName, roster, version);
					}
					else if (resp != null)
					{
						rosterSyncAccepted = false;
						updateSyncButtonVisibility();
						setStatus("queue failed: " + resp.describe());
					}
					else
					{
						rosterSyncAccepted = false;
						updateSyncButtonVisibility();
						setStatus("queue failed"
							+ (ex != null ? ": " + ex.getMessage() : ""));
					}
				}));
	}

	private void refreshBackendProfileAfterSync(String slug, String clanName,
		List<ClanMember> roster, int version)
	{
		rosterSyncAccepted = true;
		if (!pendingRosterOperatorEligible)
		{
			pendingRosterSyncEligible = true;
		}
		updateSyncButtonVisibility();
		showSyncConfirmation(slug);

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
				if (!pendingRosterOperatorEligible)
				{
					pendingRosterSyncEligible = true;
				}
				lastBackendResult = result;
				lastRenderedResult = result;
				lastLoadedRoster = resultRoster;
				lastLoadedClanName = displayName;
				lastLoadedSlug = slug;

				setClanHeaderText(displayName);
				renderClanResult(result);
				membersPanel.renderRoster(displayName, resultRoster);
				setCoverageFromResult(result);
				clanProfileCache.put(displayName, slug, resultRoster, result);
				updateSyncButtonVisibility();
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
		setStatus(text, statusColor(text));
	}

	private void setStatus(String text, Color color)
	{
		statusLabel.setText(text);
		statusLabel.setForeground(color);
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
		if (value.startsWith("roster synced") || value.startsWith("roster queued"))
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
		pendingRosterOperatorEligible = false;
		pendingRosterSourceSlot = null;
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
