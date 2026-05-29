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

	@Test
	public void memberCoverageParsesNeutralAndLegacyKeys()
	{
		ClanClogResult.MemberCoverage neutral = GSON.fromJson("{"
			+ "\"total\":10,"
			+ "\"clog_ok\":4,"
			+ "\"hiscore_only\":5,"
			+ "\"temple_missing\":5,"
			+ "\"not_found\":1"
			+ "}", ClanClogResult.MemberCoverage.class);

		assertEquals(4, neutral.getClogOk());
		assertEquals(5, neutral.getHiscoreOnly());
		assertEquals(5, neutral.getTempleMissing());
		assertEquals(9, neutral.getHiscoreRepresented());
		assertEquals(4, neutral.getClogRepresented());

		ClanClogResult.MemberCoverage legacy = GSON.fromJson("{"
			+ "\"total\":10,"
			+ "\"temple_ok\":3,"
			+ "\"temple_missing\":6,"
			+ "\"not_found\":1"
			+ "}", ClanClogResult.MemberCoverage.class);

		assertEquals(3, legacy.getClogOk());
		assertEquals(6, legacy.getHiscoreOnly());
	}

	@Test
	public void memberCoverageSerializesNeutralAndLegacyKeys()
	{
		ClanClogResult.MemberCoverage coverage =
			new ClanClogResult.MemberCoverage(10, 4, 5, 0, 1, 0);

		String json = GSON.toJson(coverage);

		assertTrue(json.contains("\"clog_ok\":4"));
		assertTrue(json.contains("\"temple_ok\":4"));
		assertTrue(json.contains("\"hiscore_only\":5"));
		assertTrue(json.contains("\"temple_missing\":5"));
	}
}
