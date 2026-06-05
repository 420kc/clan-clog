package com.clanclog;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JPanel;
import net.runelite.client.ui.components.IconTextField;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClanClogPanelTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void syncKeyRankFallsBackToCapturedOwnerRoster()
	{
		List<ClanMember> roster = Collections.singletonList(member("420 kc", "OWNER"));

		assertEquals("OWNER", ClanClogPanel.syncKeyRank("420 KC", roster));
	}

	@Test
	public void syncKeyRankFallsBackToCapturedDeputyRoster()
	{
		List<ClanMember> roster = Collections.singletonList(member("silver key", "DEPUTY_OWNER"));

		assertEquals("DEPUTY_OWNER", ClanClogPanel.syncKeyRank("Silver Key", roster));
	}

	@Test
	public void syncKeyRankRejectsNonKeyRosterRank()
	{
		List<ClanMember> roster = Collections.singletonList(member("ranked", "ADMINISTRATOR"));

		assertNull(ClanClogPanel.syncKeyRank("ranked", roster));
	}

	@Test
	public void syncOwnerRsnUsesCapturedRosterName()
	{
		List<ClanMember> roster = Collections.singletonList(member("420 kc", "OWNER"));

		assertEquals("420 kc", ClanClogPanel.syncOwnerRsn("420 KC", roster));
	}

	@Test
	public void syncOwnerRsnRequiresLocalPlayerName()
	{
		List<ClanMember> roster = Collections.singletonList(member("420 kc", "OWNER"));

		assertNull(ClanClogPanel.syncOwnerRsn(null, roster));
	}

	@Test
	public void headerResourcesArePackaged()
	{
		assertNotNull(ClanClogPanel.class.getResource("/com/clanclog/clanclog-book-28.png"));
	}

	@Test
	public void publicRosterStatusDoesNotImplyWaiting()
	{
		assertEquals("public roster only · 344 members",
			ClanClogPanel.publicRosterStatus(344, null));
	}

	@Test
	public void publicRosterStatusNamesPendingBackendProfile()
	{
		ClanClogResult shell = result("{\"build_status\":\"pending\"}");

		assertEquals("profile pending · 344 members",
			ClanClogPanel.publicRosterStatus(344, shell));
	}

	@Test
	public void publicRosterStatusNamesBuildingBackendProfile()
	{
		ClanClogResult shell = result("{\"build_status\":\"building\"}");

		assertEquals("profile building · 1 member",
			ClanClogPanel.publicRosterStatus(1, shell));
	}

	@Test
	public void profileBuildStatusCompactsToClanIconCount()
	{
		String status = "profile building · 402 members";

		assertEquals("402", ClanClogPanel.statusMemberCountText(status));
		assertEquals("· profile building", ClanClogPanel.statusStateText(status));
		assertEquals("402 · profile building", ClanClogPanel.statusDisplayText(status));
		assertTrue(ClanClogPanel.statusUsesClanIcon(status));
		assertTrue(ClanClogPanel.statusPulses(status));
	}

	@Test
	public void profilePendingStatusCompactsWithoutPulse()
	{
		String status = "profile pending · 402 members";

		assertEquals("402", ClanClogPanel.statusMemberCountText(status));
		assertEquals("· profile pending", ClanClogPanel.statusStateText(status));
		assertEquals("402 · profile pending", ClanClogPanel.statusDisplayText(status));
		assertTrue(ClanClogPanel.statusUsesClanIcon(status));
		assertFalse(ClanClogPanel.statusPulses(status));
	}

	@Test
	public void searchBarStyleKeepsClearButtonTransparent()
	{
		IconTextField field = new IconTextField();

		ClanClogPanel.styleSearchBar(field);

		JButton clearButton = findButton(field, "\u00d7");
		assertNotNull(clearButton);
		assertFalse(clearButton.isOpaque());
		assertFalse(clearButton.isContentAreaFilled());
		assertFalse(clearButton.isBorderPainted());
		if (clearButton.getParent() instanceof JPanel)
		{
			assertFalse(((JPanel) clearButton.getParent()).isOpaque());
		}
	}

	@Test
	public void profileLoadedStatusStaysQuiet()
	{
		ClanClogResult result = result("{"
			+ "\"member_coverage\":{\"total\":106,\"clog_ok\":22,\"hiscore_only\":70}"
			+ "}");

		assertEquals(" ", ClanClogPanel.profileLoadedStatus(result));
	}

	@Test
	public void cachedProfileStatusStaysQuiet()
	{
		ClanClogResult result = result("{"
			+ "\"member_coverage\":{\"total\":106,\"clog_ok\":3,\"hiscore_only\":70}"
			+ "}");

		assertEquals(" ", ClanClogPanel.cachedProfileStatus(result));
	}

	@Test
	public void totalClogInfoTextUsesRenderedClanUnion()
	{
		ClanClogResult result = result("{"
			+ "\"clog\":{\"total_obtained\":1701}"
			+ "}");

		assertEquals("1701", ClanClogPanel.totalClogInfoText(result).trim());
	}

	@Test
	public void totalClogInfoUsesCatalogCompletionColor()
	{
		ClanClogResult result = result("{"
			+ "\"clog\":{\"total_obtained\":2,"
			+ "\"catalog_by_category\":{\"boss\":[1,2,3,4]}}"
			+ "}");

		assertEquals(ClogHelper.COLOR_IN_PROGRESS, ClanClogPanel.totalClogInfoColor(result));
	}

	@Test
	public void totalClogInfoColorsCompleteCatalogGreen()
	{
		ClanClogResult result = result("{"
			+ "\"clog\":{\"total_obtained\":4,"
			+ "\"catalog_by_category\":{\"boss\":[1,2,3,4]}}"
			+ "}");

		assertEquals(ClogHelper.COLOR_COMPLETED, ClanClogPanel.totalClogInfoColor(result));
	}

	@Test
	public void totalClogInfoTierUsesCatalogTotal()
	{
		ClanClogResult result = resultWithCatalog(500, 1000);

		assertEquals("steel", ClanClogPanel.totalClogTierName(result));
	}

	@Test
	public void totalClogInfoStaysBlankWithoutClogUnion()
	{
		ClanClogResult result = result("{"
			+ "\"member_coverage\":{\"total\":106,\"clog_ok\":22}"
			+ "}");

		assertEquals(" ", ClanClogPanel.totalClogInfoText(result));
	}

	@Test
	public void shortDateTextUsesIsoDatePart()
	{
		assertEquals("2026-05-20", ClanClogPanel.shortDateText("2026-05-20T14:30:00Z"));
		assertEquals("2026-05-20", ClanClogPanel.shortDateText("2026-05-20"));
		assertNull(ClanClogPanel.shortDateText(null));
	}

	private static ClanMember member(String rsn, String rank)
	{
		return new ClanMember(rsn, rsn, rank, rank, AccountType.REGULAR,
			null, 0L, null, null);
	}

	private static ClanClogResult result(String json)
	{
		return GSON.fromJson(json, ClanClogResult.class);
	}

	private static ClanClogResult resultWithCatalog(int obtained, int total)
	{
		StringBuilder json = new StringBuilder();
		json.append("{\"clog\":{\"total_obtained\":").append(obtained)
			.append(",\"catalog_by_category\":{\"all\":[");
		for (int i = 1; i <= total; i++)
		{
			if (i > 1)
			{
				json.append(',');
			}
			json.append(i);
		}
		json.append("]}}}");
		return result(json.toString());
	}

	@Nullable
	private static JButton findButton(Container root, String text)
	{
		for (Component child : root.getComponents())
		{
			if (child instanceof JButton && text.equals(((JButton) child).getText()))
			{
				return (JButton) child;
			}
			if (child instanceof Container)
			{
				JButton found = findButton((Container) child, text);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}
}
