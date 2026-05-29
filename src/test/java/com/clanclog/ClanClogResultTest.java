package com.clanclog;

import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClanClogResultTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void profileMembersParseFromRosterOnlyShape()
	{
		ClanClogResult result = GSON.fromJson("{"
			+ "\"schema\":\"killclog.clanProfile.v1\","
			+ "\"slug\":\"smoke-test\","
			+ "\"display_name\":\"Smoke Test\","
			+ "\"build_status\":\"roster_only\","
			+ "\"member_count\":2,"
			+ "\"members\":["
			+ "{\"rsn\":\"smoke owner\",\"rank\":\"OWNER\","
			+ "\"custom_title\":\"Founder\",\"join_date\":\"2026-05-20\"},"
			+ "{\"rsn\":\"lynx titan\",\"rank\":\"GUEST\"}"
			+ "],"
			+ "\"bosses\":{},"
			+ "\"activity_totals\":{},"
			+ "\"clog\":null"
			+ "}", ClanClogResult.class);

		assertTrue(result.isRosterOnlyProfile());
		assertFalse(result.hasAggregateData());

		List<ClanMember> members = result.getMembers();
		assertEquals(2, members.size());
		assertEquals("smoke owner", members.get(0).getRsn());
		assertEquals("Founder", members.get(0).getRole());
		assertEquals("OWNER", members.get(0).getRankName());
		assertEquals(LocalDate.of(2026, 5, 20), members.get(0).getJoinDate());
		assertEquals("lynx titan", members.get(1).getDisplayName());
	}

	@Test
	public void activityTotalsParseFromProfileShape()
	{
		ClanClogResult result = GSON.fromJson("{"
			+ "\"schema\":\"killclog.clanProfile.v1\","
			+ "\"slug\":\"ready-test\","
			+ "\"display_name\":\"Ready Test\","
			+ "\"build_status\":\"ready\","
			+ "\"activity_totals\":{\"Clue Scrolls (all)\":42},"
			+ "\"bosses\":{},"
			+ "\"clog\":null"
			+ "}", ClanClogResult.class);

		assertTrue(result.isReadyProfile());
		assertTrue(result.hasAggregateData());
		assertEquals(Long.valueOf(42L),
			result.getActivityTotals().get("Clue Scrolls (all)"));
	}
}
