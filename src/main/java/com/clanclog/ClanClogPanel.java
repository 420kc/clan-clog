package com.clanclog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Vertical-slice surface, kc-palette polished.
 *
 * <p>Input mode is single-field: digits-only -> wom group id direct load,
 * anything else -> name search shown as top-10 clickable matches. Click a
 * result to load that group; roster fills in as the hiscore fan-out resolves.
 */
@Singleton
public class ClanClogPanel extends PluginPanel
{
	private static final int SEARCH_RESULT_LIMIT = 10;

	// kc palette per the 2026-05-12 typography canon. kc4 brand orange = primary,
	// kc2 lime = secondary / hover / data points, kc3 amber = explanatory text,
	// kc1 emerald = sparing spike. all four sit on top of RuneLite's dark panel bg.
	private static final Color KC4 = new Color(0xFF5700);
	private static final Color KC3 = new Color(0xFFAD00);
	private static final Color KC2 = new Color(0xCAFF00);
	private static final Color KC1 = new Color(0x4EF015);

	private static final Font BODY = new Font("Courier New", Font.PLAIN, 12);
	private static final Font META = new Font("Courier New", Font.PLAIN, 11);

	private final WomClient womClient;
	private final ClanHiscoreBatch batch;

	private final JTextField queryField = new JTextField(12);
	private final JButton lookupButton = new JButton("look up");
	private final JLabel statusLabel = new JLabel("idle");
	private final JPanel list = new JPanel();

	@Inject
	public ClanClogPanel(ClanClogConfig config, WomClient womClient, ClanHiscoreBatch batch)
	{
		super(false);
		this.womClient = womClient;
		this.batch = batch;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		add(buildSearchBar(), BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JScrollPane scroll = new JScrollPane(list,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(scroll, BorderLayout.CENTER);

		statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
		statusLabel.setFont(META);
		statusLabel.setForeground(KC3);
		add(statusLabel, BorderLayout.SOUTH);

		showPlaceholder();
	}

	private JPanel buildSearchBar()
	{
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel label = new JLabel("clan");
		label.setFont(BODY);
		label.setForeground(KC4);

		queryField.setFont(BODY);
		queryField.setForeground(KC4);
		queryField.setCaretColor(KC4);
		queryField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		queryField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(KC4, 1),
			BorderFactory.createEmptyBorder(2, 4, 2, 4)));

		styleButton(lookupButton);
		lookupButton.addActionListener(e -> onSubmitClicked());
		queryField.addActionListener(e -> onSubmitClicked());

		bar.add(label);
		bar.add(queryField);
		bar.add(lookupButton);
		return bar;
	}

	private static void styleButton(JButton b)
	{
		b.setFont(BODY);
		b.setForeground(KC4);
		b.setBackground(ColorScheme.DARK_GRAY_COLOR);
		b.setFocusPainted(false);
		b.setContentAreaFilled(false);
		b.setOpaque(true);
		b.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(KC4, 1),
			BorderFactory.createEmptyBorder(2, 8, 2, 8)));
		b.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				b.setForeground(KC2);
				b.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(KC2, 1),
					BorderFactory.createEmptyBorder(2, 8, 2, 8)));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				b.setForeground(KC4);
				b.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(KC4, 1),
					BorderFactory.createEmptyBorder(2, 8, 2, 8)));
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				b.setForeground(KC1);
				b.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(KC1, 1),
					BorderFactory.createEmptyBorder(2, 8, 2, 8)));
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				b.setForeground(KC2);
				b.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(KC2, 1),
					BorderFactory.createEmptyBorder(2, 8, 2, 8)));
			}
		});
	}

	private void onSubmitClicked()
	{
		String raw = queryField.getText().trim();
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
			SwingUtilities.invokeLater(() ->
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
		row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

		JLabel name = new JLabel(g.name != null ? g.name : "(unnamed)");
		name.setFont(BODY);
		name.setForeground(KC4);

		JLabel meta = new JLabel(g.memberCount + " members  #" + g.id);
		meta.setFont(META);
		meta.setForeground(KC3);
		meta.setHorizontalAlignment(SwingConstants.RIGHT);

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
				name.setForeground(KC2);
				meta.setForeground(KC2);
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				name.setForeground(KC4);
				meta.setForeground(KC3);
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
				SwingUtilities.invokeLater(() -> setStatus(
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

			SwingUtilities.invokeLater(() ->
			{
				setStatus(group.name + ": " + roster.size() + " members, fetching hiscores");
				renderRoster(roster);
			});

			batch.fetchAll(roster, completed -> SwingUtilities.invokeLater(() ->
			{
				setStatus(group.name + ": " + completed + "/" + roster.size() + " hiscores loaded");
				renderRoster(roster);
			})).whenComplete((v, batchEx) ->
				SwingUtilities.invokeLater(() ->
				{
					statusLabel.setForeground(KC1);
					setStatus(group.name + ": done, " + roster.size() + " members");
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
		row.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

		JLabel name = new JLabel(m.getDisplayName());
		name.setFont(BODY);
		name.setForeground(KC4);

		JLabel role = new JLabel(prettyRole(m.getRole()));
		role.setFont(META);
		role.setForeground(KC3);

		JLabel total = new JLabel();
		total.setFont(META);
		HiscoreResult hs = m.getHiscore();
		if (hs != null)
		{
			total.setText("tl " + hs.getTotalLevel() + "  cb " + hs.getCombatLevel());
			total.setForeground(KC2);
		}
		else
		{
			total.setText("...");
			total.setForeground(KC3);
		}
		total.setHorizontalAlignment(SwingConstants.RIGHT);

		row.add(name);
		row.add(role);
		row.add(total);
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
		JLabel hint = new JLabel("type a clan name or wom id, then look up");
		hint.setFont(META);
		hint.setForeground(KC3);
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		hint.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
		list.add(hint);
		list.add(Box.createVerticalGlue());
	}

	private void setStatus(String text)
	{
		statusLabel.setText(text);
	}
}
