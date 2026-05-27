package com.clanclog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.FlatTextField;
import net.runelite.client.ui.components.IconTextField;

/**
 * Main clan clog surface. Mirrors kill clog's layout shape: header search bar,
 * a clan-summary strip, then the collective hiscore aggregate area where the
 * boss grid lives. The full member roster is hidden behind a slide-out so the
 * primary view stays focused on the clan-wide kc-style snapshot.
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

	private final WomClient womClient;
	private final ClanHiscoreBatch batch;
	private final InGameClanReader clanReader;
	private final ClanLookupSession clanLookupSession;
	private final Cells cells;

	private final JLabel statusLabel = new JLabel(" ");
	private final IconTextField searchBar = new IconTextField();
	private final JLabel clanHeader = new JLabel(" ");
	private final ClanAggregateGrid aggregateGrid = new ClanAggregateGrid();
	private final ClanMembersView membersView = new ClanMembersView();
	private final SlidePanel membersTray = new SlidePanel("members", membersView, false);

	/** Last backend/fixture ClanClogResult. Merged with hiscore data after batch completes. */
	@Nullable
	private ClanClogResult lastBackendResult;

	/** Slug of the clan currently loading/loaded. Guards against duplicate batch fires. */
	@Nullable
	private String currentLoadSlug;

	@Inject
	public ClanClogPanel(ClanClogConfig config, WomClient womClient,
		ClanHiscoreBatch batch, InGameClanReader clanReader,
		ClanLookupSession clanLookupSession, Cells cells)
	{
		super(false);
		this.womClient = womClient;
		this.batch = batch;
		this.clanReader = clanReader;
		this.clanLookupSession = clanLookupSession;
		this.cells = cells;

		setLayout(new GridBagLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(8, 8, 8, 8));

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 1;
		c.weighty = 0;
		c.insets = new Insets(0, 0, 6, 0);

		add(buildHeader(), c);

		c.gridy++;
		add(buildClanSummary(), c);

		c.gridy++;
		c.weighty = 2;
		c.fill = GridBagConstraints.BOTH;
		c.insets = new Insets(0, 0, 6, 0);
		add(buildCellsSurface(), c);

		c.gridy++;
		c.weighty = 1;
		c.insets = new Insets(0, 0, 6, 0);
		add(aggregateGrid, c);

		c.gridy++;
		c.weighty = 0;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(0, 0, 0, 0);
		add(membersTray.getHeader(), c);

		c.gridy++;
		add(membersTray.getClip(), c);

		c.gridy++;
		c.insets = new Insets(4, 0, 0, 0);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(TEXT_DIM);
		statusLabel.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		statusLabel.setText("idle");
		add(statusLabel, c);

		clanReader.addListener(roster ->
			SwingUtilities.invokeLater(() -> onInGameRosterRefreshed(roster)));
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.setPreferredSize(new Dimension(0, 30));
		searchBar.addActionListener(e -> onSubmit());
		styleSearchBar(searchBar);

		header.add(searchBar, BorderLayout.CENTER);
		return header;
	}

	private JPanel buildClanSummary()
	{
		JPanel summary = new JPanel(new BorderLayout());
		summary.setBackground(ColorScheme.DARK_GRAY_COLOR);
		summary.setBorder(new EmptyBorder(4, 4, 4, 4));

		clanHeader.setFont(FontManager.getRunescapeFont());
		clanHeader.setForeground(KC_TEXT);
		clanHeader.setText("no clan loaded");
		summary.add(clanHeader, BorderLayout.WEST);

		return summary;
	}

	/**
	 * Primary clan-clog surface: boss grid + clue tier grid + rare cells.
	 * No section headers -- parity with Kill Clog's clean cell layout.
	 * Scrollbar always visible with reserved 7px width so content doesn't
	 * shift when the scrollbar appears.
	 */
	private JScrollPane buildCellsSurface()
	{
		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setBackground(ColorScheme.DARK_GRAY_COLOR);

		stack.add(cells.buildBossGrid());

		JPanel sep1 = new JPanel();
		sep1.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sep1.setPreferredSize(new Dimension(0, 7));
		sep1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 7));
		stack.add(sep1);

		stack.add(cells.buildClueTierGrid());

		JPanel sep2 = new JPanel();
		sep2.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sep2.setPreferredSize(new Dimension(0, 7));
		sep2.setMaximumSize(new Dimension(Integer.MAX_VALUE, 7));
		stack.add(sep2);

		JPanel rareGrid = new JPanel(new GridLayout(0, 3));
		rareGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rareGrid.add(cells.buildThirdAgeCell());
		rareGrid.add(cells.buildGildedCell());
		rareGrid.add(cells.buildHardRareCell());
		rareGrid.add(cells.buildEliteRareCell());
		rareGrid.add(cells.buildMasterRareCell());
		stack.add(rareGrid);
		stack.add(Box.createVerticalGlue());

		JScrollPane scroll = new JScrollPane(stack,
			JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.setViewportBorder(null);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getVerticalScrollBar().setPreferredSize(new Dimension(7, 0));
		return scroll;
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

	private void onSubmit()
	{
		String raw = searchBar.getText().trim();
		if (raw.isEmpty())
		{
			setStatus("type a clan name or wom group id");
			return;
		}
		if (raw.matches("\\d+"))
		{
			loadGroupById(Integer.parseInt(raw));
			return;
		}

		// Primary: in-game clan roster (no external dependency, works offline)
		String inGameName = clanReader.currentClanName();
		List<ClanMember> roster = clanReader.currentRoster();
		if (inGameName != null && !roster.isEmpty()
			&& normalize(inGameName).equals(normalize(raw)))
		{
			loadFromRoster(inGameName, new ArrayList<>(roster));
			return;
		}

		// Secondary: WOM search (for clans the user isn't in)
		searchByName(raw);
	}

	/**
	 * Load a clan directly from the in-game roster. Primary lookup path per
	 * the roster-first architecture: the plugin compiles its own data using
	 * the in-game clan roster + Jagex hiscores, no WOM dependency. Backend
	 * clog data fires in parallel and falls back to the dev fixture when the
	 * backend is unreachable.
	 */
	private void loadFromRoster(String clanName, List<ClanMember> roster)
	{
		String slug = slugify(clanName);
		if (slug.equals(currentLoadSlug))
		{
			return;
		}
		currentLoadSlug = slug;

		clanHeader.setText(clanName + " · " + roster.size() + " members");
		setStatus("roster synced · fetching hiscores · 0/" + roster.size());
		membersView.renderRoster(roster);
		if (!membersTray.isExpanded())
		{
			membersTray.toggle();
		}

		// Backend clog data in parallel (cells surface; falls back to fixture)
		clanLookupSession.start(slug, this);

		// Per-member hiscore fan-out (aggregate grid + member enrichment)
		final String name = clanName;
		batch.fetchAll(roster, completed -> SwingUtilities.invokeLater(() ->
		{
			setStatus("fetching hiscores · " + completed + "/" + roster.size());
			membersView.renderRoster(roster);
			aggregateGrid.renderRoster(roster);
		})).whenComplete((v, batchEx) ->
			SwingUtilities.invokeLater(() ->
			{
				// Merge real hiscore boss aggregates into the cells surface.
				// Preserves clog items from backend/fixture, replaces boss
				// data with live Jagex hiscore aggregates.
				ClanClogResult merged = RosterClogBuilder.fromHiscores(
					name, slug, roster, lastBackendResult);
				cells.renderClanResult(merged);
				setStatus("done · " + roster.size() + " members");
				membersView.renderRoster(roster);
				aggregateGrid.renderRoster(roster);
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

		// Always fire backend clog lookup so the cells surface renders
		// even if WOM has no match (fixture fallback in dev)
		clanLookupSession.start(slugify(query), this);

		womClient.searchGroups(query, SEARCH_RESULT_LIMIT).whenComplete((results, ex) ->
			SwingUtilities.invokeLater(() ->
			{
				if (results == null || results.length == 0)
				{
					setStatus("no wom match for \"" + query
						+ "\" · clog data loading");
					membersView.showPlaceholder("no roster source");
					return;
				}
				setStatus("matched " + results.length + ", click one to load");
				membersView.renderSearchResults(results, this::loadGroupById);
			}));
	}

	private void loadGroupById(int id)
	{
		currentLoadSlug = null;
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
			final String groupSlug = slugify(groupName);
			SwingUtilities.invokeLater(() ->
			{
				clanHeader.setText(groupName + " · " + roster.size() + " members");
				setStatus("fetching hiscores · 0/" + roster.size());
				membersView.renderRoster(roster);
				clanLookupSession.start(groupSlug, this);
			});

			batch.fetchAll(roster, completed -> SwingUtilities.invokeLater(() ->
			{
				setStatus("fetching hiscores · " + completed + "/" + roster.size());
				membersView.renderRoster(roster);
				aggregateGrid.renderRoster(roster);
			})).whenComplete((v, batchEx) ->
				SwingUtilities.invokeLater(() ->
				{
					ClanClogResult merged = RosterClogBuilder.fromHiscores(
						groupName, groupSlug, roster, lastBackendResult);
					cells.renderClanResult(merged);
					setStatus("done · " + roster.size() + " members");
					membersView.renderRoster(roster);
					aggregateGrid.renderRoster(roster);
				}));
		});
	}

	/**
	 * Called whenever {@link InGameClanReader} refreshes (user opens their clan
	 * tab in-game). When the panel is "idle" with no manually-loaded clan,
	 * auto-populate from the in-game roster + fire backend clog lookup so both
	 * surfaces render. Once the user has explicitly loaded another clan via
	 * search, leave their view alone.
	 */
	private void onInGameRosterRefreshed(List<ClanMember> roster)
	{
		if (roster == null || roster.isEmpty())
		{
			return;
		}
		String currentHeader = clanHeader.getText();
		if (currentHeader != null && !currentHeader.equals("no clan loaded")
			&& !currentHeader.startsWith("loading"))
		{
			return;
		}
		String name = clanReader.currentClanName();
		loadFromRoster(name != null ? name : "my clan", new ArrayList<>(roster));
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
