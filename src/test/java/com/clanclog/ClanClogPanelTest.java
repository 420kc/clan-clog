package com.clanclog;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
		assertEquals("Total clan clog: 1,701", ClanClogPanel.totalClogInfoTooltip(result));
	}

	@Test
	public void totalClogInfoUsesCatalogCompletionColor()
	{
		ClanClogResult result = result("{"
			+ "\"clog\":{\"total_obtained\":2,"
			+ "\"catalog_by_category\":{\"boss\":[1,2,3,4]}}"
			+ "}");

		assertEquals(ClogHelper.COLOR_IN_PROGRESS, ClanClogPanel.totalClogInfoColor(result));
		assertEquals("Total clan clog: 2/4", ClanClogPanel.totalClogInfoTooltip(result));
	}

	@Test
	public void totalClogInfoColorsCompleteCatalogGreen()
	{
		ClanClogResult result = result("{"
			+ "\"clog\":{\"total_obtained\":4,"
			+ "\"catalog_by_category\":{\"boss\":[1,2,3,4]}}"
			+ "}");

		assertEquals(ClogHelper.COLOR_COMPLETED, ClanClogPanel.totalClogInfoColor(result));
		assertEquals("Total clan clog: 4/4", ClanClogPanel.totalClogInfoTooltip(result));
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
		assertNull(ClanClogPanel.totalClogInfoTooltip(result));
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
}
