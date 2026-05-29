package com.clanclog;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClanMembersPanelTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void rosterHeaderShowsHiscoreAndClogCoverage() throws Exception
	{
		ClanMember ready = new ClanMember("420 kc", "420 kc", "Founder", "OWNER",
			AccountType.REGULAR, null, 0L, null, null);
		ready.setHiscore(new HiscoreResult(AccountType.IRONMAN,
			Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
			Collections.emptyMap(), Collections.emptyMap(), 2277, 200_000_000L, 126, 1));
		ClogResult clog = new ClogResult("420 kc",
			Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
			"2026-05-29T10:00:00Z", AccountType.IRONMAN);
		clog.setUniqueObtained(420);
		ready.setClog(clog);

		ClanMember pending = new ClanMember("pending", "pending", "Member", "GUEST",
			AccountType.REGULAR, null, 0L, null, null);

		AtomicReference<ClanMembersPanel> panelRef = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			ClanMembersPanel panel = new ClanMembersPanel();
			panel.renderRoster("Clannabis", Arrays.asList(ready, pending));
			panelRef.set(panel);
		});

		assertTrue(labelTexts(panelRef.get()).contains("2 members · 1 hiscore · 1 clog"));
		assertEquals(new java.awt.Color(0xFF, 0x57, 0x00),
			labelColor(panelRef.get(), "2 members · 1 hiscore · 1 clog"));
	}

	@Test
	public void searchHeadersShowMatchCounts() throws Exception
	{
		WomGroup publicMatch = new WomGroup();
		publicMatch.id = 101;
		publicMatch.name = "Clannabis CC";
		publicMatch.memberCount = 101;

		KillclogApiClient.ClanSearchResponse response = GSON.fromJson("{"
			+ "\"matches\":[{"
			+ "\"slug\":\"clannabis\","
			+ "\"display_name\":\"Clannabis\","
			+ "\"source_tier\":\"game_verified\","
			+ "\"member_count\":101"
			+ "}]"
			+ "}", KillclogApiClient.ClanSearchResponse.class);

		AtomicReference<ClanMembersPanel> publicPanelRef = new AtomicReference<>();
		AtomicReference<ClanMembersPanel> profilePanelRef = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			ClanMembersPanel publicPanel = new ClanMembersPanel();
			publicPanel.renderSearchResults("Clannabis", new WomGroup[]{publicMatch}, id ->
			{
			});
			publicPanelRef.set(publicPanel);

			ClanMembersPanel profilePanel = new ClanMembersPanel();
			profilePanel.renderProfileSearchResults("Clannabis", response.getMatches(), slug ->
			{
			});
			profilePanelRef.set(profilePanel);
		});

		List<String> publicLabels = labelTexts(publicPanelRef.get());
		assertTrue(publicLabels.contains("clan matches"));
		assertTrue(publicLabels.contains("Clannabis · 1 public clan"));
		assertEquals(new java.awt.Color(0xFF, 0x57, 0x00),
			labelColor(publicPanelRef.get(), "Clannabis · 1 public clan"));

		List<String> profileLabels = labelTexts(profilePanelRef.get());
		assertTrue(profileLabels.contains("killclog.com matches"));
		assertTrue(profileLabels.contains("Clannabis · 1 profile"));
	}

	private static List<String> labelTexts(ClanMembersPanel panel)
	{
		java.util.ArrayList<String> labels = new java.util.ArrayList<>();
		collectLabels(panel, labels);
		return labels;
	}

	private static java.awt.Color labelColor(ClanMembersPanel panel, String text)
	{
		java.awt.Color color = findLabelColor(panel, text);
		if (color == null)
		{
			throw new AssertionError("missing label: " + text);
		}
		return color;
	}

	private static java.awt.Color findLabelColor(java.awt.Component component, String text)
	{
		if (component instanceof JLabel && text.equals(((JLabel) component).getText()))
		{
			return ((JLabel) component).getForeground();
		}
		if (component instanceof java.awt.Container)
		{
			for (java.awt.Component child : ((java.awt.Container) component).getComponents())
			{
				java.awt.Color color = findLabelColor(child, text);
				if (color != null)
				{
					return color;
				}
			}
		}
		return null;
	}

	private static void collectLabels(java.awt.Component component, List<String> labels)
	{
		if (component instanceof JLabel)
		{
			labels.add(((JLabel) component).getText());
		}
		if (component instanceof java.awt.Container)
		{
			for (java.awt.Component child : ((java.awt.Container) component).getComponents())
			{
				collectLabels(child, labels);
			}
		}
	}
}
