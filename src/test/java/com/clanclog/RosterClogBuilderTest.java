package com.clanclog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class RosterClogBuilderTest
{
	@Test
	public void newestClogLastChangedReturnsNewestMemberTimestamp()
	{
		ClanMember older = member("Older", "2026-05-21T12:00:00Z");
		ClanMember newer = member("Newer", "2026-05-22T12:00:00Z");
		ClanMember missing = new ClanMember("Missing", "Missing", null,
			null, AccountType.REGULAR, null, 0L, null, null);

		assertEquals("2026-05-22T12:00:00Z",
			RosterClogBuilder.newestClogLastChanged(
				Arrays.asList(older, newer, missing)));
	}

	@Test
	public void newestClogLastChangedReturnsNullWithoutMemberTimestamps()
	{
		ClanMember missing = new ClanMember("Missing", "Missing", null,
			null, AccountType.REGULAR, null, 0L, null, null);

		assertNull(RosterClogBuilder.newestClogLastChanged(
			Collections.singletonList(missing)));
	}

	private static ClanMember member(String rsn, String lastChanged)
	{
		ClanMember member = new ClanMember(rsn, rsn, null, null,
			AccountType.REGULAR, null, 0L, null, null);
		member.setClog(new ClogResult(rsn, Collections.emptyMap(),
			Collections.emptyMap(), Collections.emptyMap(), lastChanged,
			AccountType.REGULAR));
		return member;
	}
}
