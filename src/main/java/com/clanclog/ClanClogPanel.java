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
	 * Primary clan-clog surface backed by {@link ClanClogResult}. Boss grid +
	 * clue tier grid + the 5 rare cells (3rd Age, Gilded, Hard/Elite/Master
	 * Treasure), all wrapped in a single scrollpane. Populated by
	 * {@link #onClanResult} via {@link Cells#renderClanResult(ClanClogResult)}.
	 */
	private JScrollPane buildCellsSurface()
	{
		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setBackground(ColorScheme.DARK_GRAY_COLOR);
		stack.setBorder(new EmptyBorder(4, 4, 4, 4));

		stack.add(buildSectionHeader("bosses"));
		stack.add(cells.buildBossGrid());
		stack.add(Box.createVerticalStrut(8));

		stack.add(buildSectionHeader("clue tiers"));
		stack.add(cells.buildClueTierGrid());
		stack.add(Box.createVerticalStrut(8));

		stack.add(buildSectionHeader("rare drops"));
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
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.setViewportBorder(null);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		return scroll;
	}

	private static JLabel buildSectionHeader(String text)
	{
		JLabel header = new JLabel(text);
		header.setFont(FontManager.getRunescapeFont());
		header.setForeground(KC_TEXT);
		header.setBorder(new EmptyBorder(2, 0, 4, 0));
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		return header;
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
		}
		else
		{
			searchByName(raw);
		}
	}

	private void searchByName(String query)
	{
		setStatus("searching wom for \"" + query + "\"...");
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
					setStatus("no clans matched \"" + query + "\"");
					membersView.showPlaceholder("no matches");
					return;
				}
				setStatus("matched " + results.length + ", click one to load");
				membersView.renderSearchResults(results, this::loadGroupById);
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

			SwingUtilities.invokeLater(() ->
			{
				clanHeader.setText(group.name + " · " + roster.size() + " members");
				setStatus("fetching hiscores · 0/" + roster.size());
				membersView.renderRoster(roster);
				clanLookupSession.start(slugify(group.name), this);
			});

			batch.fetchAll(roster, completed -> SwingUtilities.invokeLater(() ->
			{
				setStatus("fetching hiscores · " + completed + "/" + roster.size());
				membersView.renderRoster(roster);
				aggregateGrid.renderRoster(roster);
			})).whenComplete((v, batchEx) ->
				SwingUtilities.invokeLater(() ->
				{
					setStatus("done · " + roster.size() + " members");
					membersView.renderRoster(roster);
					aggregateGrid.renderRoster(roster);
				}));
		});
	}

	/**
	 * Called whenever {@link InGameClanReader} refreshes (user opens their clan
	 * tab in-game). For now: when the panel is "idle" with no manually-loaded
	 * clan, auto-populate from the in-game roster. Once the user has explicitly
	 * loaded another clan via search, leave their view alone.
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
		clanHeader.setText("my clan · " + roster.size() + " members");
		setStatus("in-game roster synced · fetching hiscores · 0/" + roster.size());
		membersView.renderRoster(roster);

		List<ClanMember> mutable = new ArrayList<>(roster);
		batch.fetchAll(mutable, completed -> SwingUtilities.invokeLater(() ->
		{
			setStatus("in-game roster · " + completed + "/" + mutable.size());
			membersView.renderRoster(mutable);
			aggregateGrid.renderRoster(mutable);
		})).whenComplete((v, batchEx) ->
			SwingUtilities.invokeLater(() ->
			{
				setStatus("done · " + mutable.size() + " members");
				membersView.renderRoster(mutable);
				aggregateGrid.renderRoster(mutable);
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
		setStatus("fetching backend clog for " + slug + "...");
		cells.clearCells();
	}

	@Override
	public void onClanResult(String slug, ClanClogResult result)
	{
		int memberCount = result.getMemberCount();
		setStatus("backend clog loaded · " + memberCount + " members · " + slug);
		cells.renderClanResult(result);
	}

	@Override
	public void onClanNotFound(String slug)
	{
		setStatus("backend clog not registered for " + slug);
		cells.clearCells();
	}

	@Override
	public void onClanError(String slug, @Nullable String detail)
	{
		setStatus("backend clog error for " + slug + (detail != null ? ": " + detail : ""));
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
}
