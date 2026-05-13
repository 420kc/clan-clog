package com.clanclog;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
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
 * Vertical-slice surface. Top bar takes a WOM group id, fetches the roster,
 * fans out hiscore lookups, renders a member-per-row list with running progress.
 *
 * <p>Intentionally minimal: no kc-palette styling, no sortable columns, no
 * filter chips, no live overlay. Phase 1 proof that the full pipe is wired.
 * Polish ships in follow-up cycles once the slice is observably working.
 */
@Singleton
public class ClanClogPanel extends PluginPanel
{
	private final WomClient womClient;
	private final ClanHiscoreBatch batch;

	private final JTextField groupIdField = new JTextField(8);
	private final JButton lookupButton = new JButton("Look up");
	private final JLabel statusLabel = new JLabel("idle");
	private final JPanel memberList = new JPanel();

	@Inject
	public ClanClogPanel(ClanClogConfig config, WomClient womClient, ClanHiscoreBatch batch)
	{
		super(false);
		this.womClient = womClient;
		this.batch = batch;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		// --- top: search row ---
		JPanel search = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		search.add(new JLabel("WOM group id:"));
		search.add(groupIdField);
		search.add(lookupButton);
		add(search, BorderLayout.NORTH);

		// --- center: scrollable member list ---
		memberList.setLayout(new BoxLayout(memberList, BoxLayout.Y_AXIS));
		JScrollPane scroll = new JScrollPane(memberList,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
		add(scroll, BorderLayout.CENTER);

		// --- bottom: status ---
		statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
		add(statusLabel, BorderLayout.SOUTH);

		lookupButton.addActionListener(e -> onLookupClicked());
		groupIdField.addActionListener(e -> onLookupClicked());

		showPlaceholder();
	}

	private void onLookupClicked()
	{
		String raw = groupIdField.getText().trim();
		final int id;
		try
		{
			id = Integer.parseInt(raw);
		}
		catch (NumberFormatException nfe)
		{
			setStatus("group id must be a number");
			return;
		}

		setStatus("fetching roster for group " + id + "...");
		memberList.removeAll();
		memberList.revalidate();
		memberList.repaint();

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
		memberList.removeAll();
		for (ClanMember m : roster)
		{
			memberList.add(buildRow(m));
		}
		memberList.add(Box.createVerticalGlue());
		memberList.revalidate();
		memberList.repaint();
	}

	private static Component buildRow(ClanMember m)
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
		memberList.removeAll();
		JLabel hint = new JLabel("enter a wom group id and press look up");
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		hint.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
		memberList.add(hint);
		memberList.add(Box.createVerticalGlue());
	}

	private void setStatus(String text)
	{
		statusLabel.setText(text);
	}
}
