package com.clanclog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ClanEventReporterTest
{
	private static final Instant NOW = Instant.parse("2026-05-31T12:00:00Z");
	private static final List<ClanMember> ROSTER =
		Collections.singletonList(member("silver key", "DEPUTY_OWNER"));

	@Test
	public void skipsWhenSyncDisabled()
	{
		assertNull(ClanEventReporter.buildRequest(false, "Clannabis",
			"silver key", "DEPUTY_OWNER", ROSTER, "left", "old member", NOW));
	}

	@Test
	public void skipsWithoutKeyRank()
	{
		List<ClanMember> roster = Collections.singletonList(member("helper", "ADMINISTRATOR"));

		assertNull(ClanEventReporter.buildRequest(true, "Clannabis",
			"helper", null, roster, "left", "old member", NOW));
	}

	@Test
	public void skipsWhenLocalPlayerNotInRoster()
	{
		assertNull(ClanEventReporter.buildRequest(true, "Clannabis",
			"silver key", "DEPUTY_OWNER", Collections.singletonList(member("owner", "OWNER")),
			"left", "old member", NOW));
	}

	@Test
	public void skipsUnknownEventType()
	{
		assertNull(ClanEventReporter.buildRequest(true, "Clannabis",
			"silver key", "DEPUTY_OWNER", ROSTER, "renamed", "old member", NOW));
	}

	@Test
	public void buildsVerifiedOwnerClanEvent()
	{
		ClanEventReporter.EventRequest request = ClanEventReporter.buildRequest(true,
			"Clan Nabis", "Silver Key", null, ROSTER, "left", "Old  Member", NOW);

		assertEquals("clan-nabis", request.slug);
		assertEquals("left", request.eventType);
		assertEquals("Old Member", request.targetRsn);
		assertEquals("silver key", request.reporterRsn);
		assertEquals("2026-05-31T12:00:00Z", request.observedAt);
	}

	private static ClanMember member(String rsn, String rank)
	{
		return new ClanMember(rsn, rsn, rank, rank, AccountType.REGULAR,
			null, 0L, null, null);
	}
}
