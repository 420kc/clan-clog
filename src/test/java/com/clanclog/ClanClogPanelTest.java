package com.clanclog;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ClanClogPanelTest
{
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

	private static ClanMember member(String rsn, String rank)
	{
		return new ClanMember(rsn, rsn, rank, rank, AccountType.REGULAR,
			null, 0L, null, null);
	}
}
