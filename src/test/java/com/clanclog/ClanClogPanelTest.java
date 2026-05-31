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
	public void headerBannerResourceIsPackaged()
	{
		assertNotNull(ClanClogPanel.class.getResource("/com/clanclog/clanclog-banner-28.png"));
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
	public void profileLoadedStatusShowsCoverage()
	{
		ClanClogResult result = result("{"
			+ "\"member_coverage\":{\"total\":106,\"clog_ok\":22,\"hiscore_only\":70}"
			+ "}");

		assertEquals("profile loaded · 22/106 clogs",
			ClanClogPanel.profileLoadedStatus(result));
	}

	@Test
	public void cachedProfileStatusShowsCoverage()
	{
		ClanClogResult result = result("{"
			+ "\"member_coverage\":{\"total\":106,\"clog_ok\":3,\"hiscore_only\":70}"
			+ "}");

		assertEquals("cached profile · 3/106 clogs",
			ClanClogPanel.cachedProfileStatus(result));
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
}
