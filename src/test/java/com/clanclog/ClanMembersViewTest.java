package com.clanclog;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClanMembersViewTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void memberRowsRenderIdentityAndProgressDetails() throws Exception
	{
		ClanMember member = new ClanMember("420 kc", "420 kc", "Founder", "OWNER",
			AccountType.REGULAR, "main", 0L, "2026-05-29T09:00:00Z",
			LocalDate.of(2026, 5, 28));
		member.setHiscore(new HiscoreResult(AccountType.IRONMAN,
			Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
			Collections.emptyMap(), Collections.emptyMap(), 2277, 200_000_000L, 126, 1));
		ClogResult clog = new ClogResult("420 kc",
			Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
			"2026-05-29T10:00:00Z", AccountType.IRONMAN);
		clog.setUniqueObtained(420);
		clog.setUniqueTotal(1600);
		member.setClog(clog);

		AtomicReference<ClanMembersView> viewRef = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			ClanMembersView view = new ClanMembersView();
			view.renderRoster(Collections.singletonList(member));
			viewRef.set(view);
		});

		List<String> labels = labelTexts(viewRef.get());
		assertTrue(labels.contains("420 kc"));
		assertTrue(labels.contains("Founder · Ironman · main"));
		assertTrue(labels.contains("tl 2277  cb 126"));
		assertTrue(labels.contains("clog 420"));

		List<String> tooltips = tooltips(viewRef.get());
		assertTrue(tooltips.stream().anyMatch(t -> t.contains("Collection log: 420 / 1,600")
			&& t.contains("Temple sync: 2026-05-29T10:00:00Z")
			&& t.contains("Joined: 2026-05-28")));
	}

	@Test
	public void profileSearchRowsRenderStoredClanLabelsAndOpenSlug() throws Exception
	{
		KillclogApiClient.ClanSearchResponse response = GSON.fromJson("{"
			+ "\"matches\":[{"
			+ "\"slug\":\"clannabis\","
			+ "\"display_name\":\"Clannabis\","
			+ "\"source_tier\":\"game_verified\","
			+ "\"build_status\":\"ready\","
			+ "\"member_count\":37,"
			+ "\"last_built_at\":\"2026-05-29T12:00:00Z\""
			+ "}]"
			+ "}", KillclogApiClient.ClanSearchResponse.class);

		AtomicReference<String> pickedSlug = new AtomicReference<>();
		AtomicReference<ClanMembersView> viewRef = new AtomicReference<>();

		SwingUtilities.invokeAndWait(() ->
		{
			ClanMembersView view = new ClanMembersView();
			view.renderProfileSearchResults(response.getMatches(), pickedSlug::set);
			viewRef.set(view);
		});

		List<String> labels = labelTexts(viewRef.get());
		assertTrue(labels.contains("Clannabis"));
		assertTrue(labels.contains("37 · game verified"));
		assertTrue(tooltips(viewRef.get()).stream().anyMatch(t -> t.contains("Build: ready")
			&& t.contains("Last built: 2026-05-29T12:00:00Z")
			&& t.contains("Slug: clannabis")));

		SwingUtilities.invokeAndWait(() -> clickFirstRow(viewRef.get()));
		assertEquals("clannabis", pickedSlug.get());
	}

	@Test
	public void publicSearchRowsRenderSourceReceipts() throws Exception
	{
		WomGroup group = new WomGroup();
		group.id = 101;
		group.name = "Clannabis CC";
		group.memberCount = 101;

		AtomicReference<ClanMembersView> viewRef = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			ClanMembersView view = new ClanMembersView();
			view.renderSearchResults(new WomGroup[]{group}, id ->
			{
			});
			viewRef.set(view);
		});

		assertTrue(labelTexts(viewRef.get()).contains("Clannabis CC"));
		assertTrue(labelTexts(viewRef.get()).contains("101 · #101"));
		assertTrue(tooltips(viewRef.get()).stream().anyMatch(t ->
			t.contains("Source: Wise Old Man public roster")
				&& t.contains("Members: 101")
				&& t.contains("Group id: 101")));
	}

	@Test
	public void emptyStatesRenderUsefulReceipts() throws Exception
	{
		AtomicReference<ClanMembersView> rosterRef = new AtomicReference<>();
		AtomicReference<ClanMembersView> storedRef = new AtomicReference<>();
		AtomicReference<ClanMembersView> publicRef = new AtomicReference<>();

		SwingUtilities.invokeAndWait(() ->
		{
			ClanMembersView roster = new ClanMembersView();
			roster.renderRoster(Collections.emptyList());
			rosterRef.set(roster);

			ClanMembersView stored = new ClanMembersView();
			stored.renderProfileSearchResults(Collections.emptyList(), slug ->
			{
			});
			storedRef.set(stored);

			ClanMembersView publicView = new ClanMembersView();
			publicView.renderSearchResults(new WomGroup[0], id ->
			{
			});
			publicRef.set(publicView);
		});

		assertTrue(labelTexts(rosterRef.get()).contains("no members to show"));
		assertTrue(labelTexts(rosterRef.get()).contains("open your clan tab or pick a public roster"));
		assertTrue(labelTexts(storedRef.get()).contains("no stored profiles"));
		assertTrue(labelTexts(storedRef.get()).contains("checking public rosters next"));
		assertTrue(labelTexts(publicRef.get()).contains("no public clans found"));
		assertTrue(labelTexts(publicRef.get()).contains("try a shorter name or group id"));
	}

	private static List<String> labelTexts(Container root)
	{
		List<String> labels = new ArrayList<>();
		collectLabels(root, labels);
		return labels;
	}

	private static List<String> tooltips(Container root)
	{
		List<String> values = new ArrayList<>();
		collectTooltips(root, values);
		return values;
	}

	private static void collectLabels(Component component, List<String> labels)
	{
		if (component instanceof JLabel)
		{
			labels.add(((JLabel) component).getText());
		}
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				collectLabels(child, labels);
			}
		}
	}

	private static void collectTooltips(Component component, List<String> values)
	{
		if (component instanceof javax.swing.JComponent)
		{
			String tooltip = ((javax.swing.JComponent) component).getToolTipText();
			if (tooltip != null)
			{
				values.add(tooltip);
			}
		}
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				collectTooltips(child, values);
			}
		}
	}

	private static void clickFirstRow(Container root)
	{
		for (Component component : root.getComponents())
		{
			if (component instanceof Container)
			{
				Container child = (Container) component;
				if (child.getMouseListeners().length > 0)
				{
					MouseEvent click = new MouseEvent(child, MouseEvent.MOUSE_CLICKED,
						System.currentTimeMillis(), 0, 4, 4, 1, false);
					for (MouseListener listener : child.getMouseListeners())
					{
						listener.mouseClicked(click);
					}
					return;
				}
				clickFirstRow(child);
				if (root instanceof ClanMembersView)
				{
					return;
				}
			}
		}
	}
}
