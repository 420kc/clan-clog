package com.clanclog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.FlatTextField;
import net.runelite.client.ui.components.IconTextField;

/**
 * Native-feeling clan clog panel. Modeled on KillClogPanel patterns: RuneLite's
 * ColorScheme + FontManager for the chrome, IconTextField for search, white /
 * light-gray for primary text + dim gray for meta.
 *
 * <p>Input mode is single-field: digits-only -> wom group id direct load,
 * anything else -> name search shown as top-10 clickable rows. Press enter or
 * click a row to load that group; roster fills in as the hiscore fan-out
 * resolves.
 */
@Singleton
public class ClanClogPanel extends PluginPanel
{
	private static final int SEARCH_RESULT_LIMIT = 10;

	private static final Color TEXT_DIM = new Color(160, 160, 160);
	/** Light gray, kc plugin's canonical primary data color (215,215,215). */
	private static final Color KC_TEXT = new Color(215, 215, 215);

	private final WomClient womClient;
	private final ClanHiscoreBatch batch;

	private final JLabel statusLabel = new JLabel(" ");
	private final IconTextField searchBar = new IconTextField();
	private final JPanel list = new JPanel();

	@Inject
	public ClanClogPanel(ClanClogConfig config, WomClient womClient, ClanHiscoreBatch batch)
	{
		super(false);
		this.womClient = womClient;
		this.batch = batch;

		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setLayout(new BorderLayout());

		add(buildHeader(), BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JScrollPane scroll = new JScrollPane(list,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.setViewportBorder(null);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(scroll, BorderLayout.CENTER);

		showPlaceholder();
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(TEXT_DIM);
		statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		statusLabel.setBorder(new EmptyBorder(0, 4, 2, 0));
		statusLabel.putClientProperty(
			RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		header.add(statusLabel);

		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.setPreferredSize(new Dimension(0, 30));
		searchBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		searchBar.addActionListener(e -> onSubmit());
		styleSearchBar(searchBar);
		header.add(searchBar);

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
				tf.setForeground(Color.WHITE);
				tf.setCaretColor(Color.WHITE);
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
		list.removeAll();
		list.revalidate();
		list.repaint();

		womClient.searchGroups(query, SEARCH_RESULT_LIMIT).whenComplete((results, ex) ->
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (results == null || results.length == 0)
				{
					setStatus("no clans matched \"" + query + "\"");
					return;
				}
				setStatus("matched " + results.length + ", click one to load");
				renderSearchResults(results);
			}));
	}

	private void renderSearchResults(WomGroup[] results)
	{
		list.removeAll();
		for (WomGroup g : results)
		{
			list.add(buildSearchRow(g));
		}
		list.add(Box.createVerticalGlue());
		list.revalidate();
		list.repaint();
	}

	private Component buildSearchRow(WomGroup g)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(true);
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(4, 6, 4, 6));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

		JLabel name = new JLabel(g.name != null ? g.name : "(unnamed)");
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(KC_TEXT);

		JLabel meta = new JLabel(g.memberCount + " · #" + g.id);
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(TEXT_DIM);
		meta.setHorizontalAlignment(JLabel.RIGHT);

		row.add(name, BorderLayout.WEST);
		row.add(meta, BorderLayout.EAST);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				loadGroupById(g.id);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_COLOR);
			}
		});
		return row;
	}

	private void loadGroupById(int id)
	{
		setStatus("fetching roster for group " + id + "...");
		list.removeAll();
		list.revalidate();
		list.repaint();

		womClient.getGroup(id).whenComplete((group, ex) ->
		{
			if (group == null)
			{
				javax.swing.SwingUtilities.invokeLater(() -> setStatus(
					"no group returned (network, missing id, or wom timeout)"));
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

			javax.swing.SwingUtilities.invokeLater(() ->
			{
				setStatus(group.name + " · " + roster.size() + " members · loading");
				renderRoster(roster);
			});

			batch.fetchAll(roster, completed -> javax.swing.SwingUtilities.invokeLater(() ->
			{
				setStatus(group.name + " · " + completed + "/" + roster.size() + " loaded");
				renderRoster(roster);
			})).whenComplete((v, batchEx) ->
				javax.swing.SwingUtilities.invokeLater(() ->
				{
					setStatus(group.name + " · " + roster.size() + " members");
					renderRoster(roster);
				}));
		});
	}

	private void renderRoster(List<ClanMember> roster)
	{
		list.removeAll();
		for (ClanMember m : roster)
		{
			list.add(buildMemberRow(m));
		}
		list.add(Box.createVerticalGlue());
		list.revalidate();
		list.repaint();
	}

	private static Component buildMemberRow(ClanMember m)
	{
		JPanel row = new JPanel(new GridLayout(1, 3, 8, 0));
		row.setOpaque(true);
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(2, 6, 2, 6));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

		JLabel name = new JLabel(m.getDisplayName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(KC_TEXT);

		JLabel role = new JLabel(prettyRole(m.getRole()));
		role.setFont(FontManager.getRunescapeSmallFont());
		role.setForeground(TEXT_DIM);

		JLabel total = new JLabel();
		total.setFont(FontManager.getRunescapeSmallFont());
		HiscoreResult hs = m.getHiscore();
		if (hs != null)
		{
			total.setText("tl " + hs.getTotalLevel() + " · cb " + hs.getCombatLevel());
			total.setForeground(KC_TEXT);
		}
		else
		{
			total.setText("·");
			total.setForeground(TEXT_DIM);
		}
		total.setHorizontalAlignment(JLabel.RIGHT);

		row.add(name);
		row.add(role);
		row.add(total);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_COLOR);
			}
		});
		return row;
	}

	private static String prettyRole(String raw)
	{
		if (raw == null || raw.isEmpty())
		{
			return "";
		}
		return raw.replace('_', ' ');
	}

	private void showPlaceholder()
	{
		list.removeAll();
		JLabel hint = new JLabel("type a clan name or wom id");
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(TEXT_DIM);
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		hint.setBorder(new EmptyBorder(8, 4, 4, 4));
		list.add(hint);
		list.add(Box.createVerticalGlue());
	}

	private void setStatus(String text)
	{
		statusLabel.setText(text);
	}
}
