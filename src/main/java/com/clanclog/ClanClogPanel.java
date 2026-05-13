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
import net.runelite.client.ui.PluginPanel;

/**
 * Vertical-slice surface. Top bar takes a clan name (or wom group id),
 * fetches the roster, fans out hiscore lookups, renders a member-per-row list
 * with running progress.
 *
 * <p>Two input modes share one field:
 * <ul>
 *   <li>all-digit input -- treated as a wom group id, loads directly</li>
 *   <li>anything else -- treated as a name query, shows the top 10 matches
 *       as clickable rows; click one to load that group</li>
 * </ul>
 *
 * <p>Phase-1 minimal: no kc-palette styling, no sortable columns, no filter
 * chips, no live overlay. Phase-2 polish is its own track.
 */
@Singleton
public class ClanClogPanel extends PluginPanel
{
	private static final int SEARCH_RESULT_LIMIT = 10;

	private final WomClient womClient;
	private final ClanHiscoreBatch batch;

	private final JTextField queryField = new JTextField(12);
	private final JButton lookupButton = new JButton("Look up");
	private final JLabel statusLabel = new JLabel("idle");
	private final JPanel list = new JPanel();

	@Inject
	public ClanClogPanel(ClanClogConfig config, WomClient womClient, ClanHiscoreBatch batch)
	{
		super(false);
		this.womClient = womClient;
		this.batch = batch;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		JPanel search = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		search.add(new JLabel("clan:"));
		search.add(queryField);
		search.add(lookupButton);
		add(search, BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		JScrollPane scroll = new JScrollPane(list,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
		add(scroll, BorderLayout.CENTER);

		statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
		add(statusLabel, BorderLayout.SOUTH);

		lookupButton.addActionListener(e -> onSubmitClicked());
		queryField.addActionListener(e -> onSubmitClicked());

		showPlaceholder();
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
				setStatus("matched " + results.length + " -- click one to load");
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
		JPanel row = new JPanel(new GridLayout(1, 2, 8, 0));
		row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		row.setOpaque(true);
		final Color idleBg = row.getBackground();
		final Color hoverBg = idleBg != null
			? new Color(Math.min(255, idleBg.getRed() + 16),
				Math.min(255, idleBg.getGreen() + 16),
				Math.min(255, idleBg.getBlue() + 16))
			: idleBg;

		JLabel name = new JLabel(g.name != null ? g.name : "(unnamed)");
		JLabel meta = new JLabel(g.memberCount + " members  #" + g.id);
		meta.setFont(meta.getFont().deriveFont(Font.PLAIN, 11f));
		meta.setHorizontalAlignment(SwingConstants.RIGHT);

		row.add(name);
		row.add(meta);

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
				row.setBackground(hoverBg);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(idleBg);
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
					"no group returned (network failure, missing group, or wom timeout)"));
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
				setStatus(group.name + ": " + roster.size() + " members, fetching hiscores...");
				renderRoster(roster);
			});

			batch.fetchAll(roster, completed -> SwingUtilities.invokeLater(() ->
			{
				setStatus(group.name + ": " + completed + "/" + roster.size() + " hiscores loaded");
				renderRoster(roster);
			})).whenComplete((v, batchEx) ->
				SwingUtilities.invokeLater(() ->
				{
					setStatus(group.name + ": done -- " + roster.size() + " members");
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
		row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

		JLabel name = new JLabel(m.getDisplayName());
		name.setFont(name.getFont().deriveFont(Font.PLAIN));

		JLabel role = new JLabel(prettyRole(m.getRole()));
		role.setFont(role.getFont().deriveFont(Font.PLAIN, 11f));

		JLabel total = new JLabel();
		HiscoreResult hs = m.getHiscore();
		if (hs != null)
		{
			total.setText("tl " + hs.getTotalLevel() + "  cb " + hs.getCombatLevel());
		}
		else
		{
			total.setText("...");
		}
		total.setFont(total.getFont().deriveFont(Font.PLAIN, 11f));
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
		JLabel hint = new JLabel("type a clan name (or wom id) and press look up");
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
