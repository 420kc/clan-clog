package com.clanclog;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class ClanMembersPanelTest
{
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
	}

	private static List<String> labelTexts(ClanMembersPanel panel)
	{
		java.util.ArrayList<String> labels = new java.util.ArrayList<>();
		collectLabels(panel, labels);
		return labels;
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
