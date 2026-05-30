package com.clanclog;

import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
	public void matchesSlugNormalizesExpectedSlug()
	{
		ClanClogResult result = GSON.fromJson("{"
			+ "\"slug\":\"clannabis\","
			+ "\"display_name\":\"Clannabis\""
			+ "}", ClanClogResult.class);

		assertTrue(result.matchesSlug("Clannabis"));
		assertFalse(result.matchesSlug("Clannabis CC"));
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

	@Test
	public void representedDataIgnoresCoverageOnlyPayload()
	{
		ClanClogResult empty = ClanClogResult.forRoster(
			"empty-clan", "Empty Clan", 2, Map.of());
		empty.setMemberCoverage(new ClanClogResult.MemberCoverage(
			2, 0, 0, 0, 2, 0));

		ClanClogResult covered = ClanClogResult.forRoster(
			"covered-clan", "Covered Clan", 2, Map.of());
		covered.setMemberCoverage(new ClanClogResult.MemberCoverage(
			2, 0, 1, 0, 1, 0));

		assertFalse(empty.hasRepresentedData());
		assertFalse(covered.hasRepresentedData());
	}

	@Test
	public void representedDataIgnoresOptOutOnlyCoverage()
	{
		ClanClogResult result = ClanClogResult.forRoster(
			"opted-clan", "Opted Clan", 2, Map.of());
		result.setMemberCoverage(new ClanClogResult.MemberCoverage(
			2, 0, 0, 2, 0, 0));

		assertFalse(result.hasRepresentedData());
	}

	@Test
	public void representedDataIgnoresCatalogOnlyClog()
	{
		ClanClogResult result = ClanClogResult.forRoster(
			"catalog-clan", "Catalog Clan", 1, Map.of());
		result.setClog(new ClanClogResult.ClogUnion(
			Map.of("shellbane_gryphon", List.of()),
			0,
			0,
			Map.of(),
			Map.of("shellbane_gryphon", List.of(30000, 30001))));

		assertFalse(result.hasRepresentedData());
	}

	@Test
	public void representedDataIgnoresZeroBossAggregates()
	{
		ClanClogResult result = ClanClogResult.forRoster(
			"zero-boss-clan", "Zero Boss Clan", 1,
			Map.of("Zulrah", new ClanClogResult.BossAggregate(0, List.of(), 0)));

		assertFalse(result.hasRepresentedData());
	}

	@Test
	public void representedDataAcceptsBossCoverage()
	{
		ClanClogResult result = ClanClogResult.forRoster(
			"boss-clan", "Boss Clan", 1,
			Map.of("Zulrah", new ClanClogResult.BossAggregate(0, List.of(), 1)));

		assertTrue(result.hasRepresentedData());
	}

	@Test
	public void clogUnionSerializesCatalogByCategory()
	{
		ClanClogResult.ClogUnion union = new ClanClogResult.ClogUnion(
			Map.of("shellbane_gryphon", List.of(30000, 30001)),
			2,
			2,
			Map.of("30000", new ClanClogResult.ItemMeta(2, 5,
				List.of(new ClanClogResult.ItemContributor("alice", 3),
					new ClanClogResult.ItemContributor("bob", 2)))),
			Map.of("shellbane_gryphon",
				List.of(30000, 30001, 30002, 30003)));

		String json = GSON.toJson(union);
		ClanClogResult.ClogUnion parsed =
			GSON.fromJson(json, ClanClogResult.ClogUnion.class);

		assertTrue(json.contains("\"catalog_by_category\""));
		assertTrue(json.contains("\"total_member_unique_obtained\":2"));
		assertTrue(json.contains("\"quantity_total\":5"));
		assertTrue(json.contains("\"contributors\""));
		assertEquals(List.of(30000, 30001, 30002, 30003),
			parsed.getCatalog("shellbane_gryphon"));
		assertEquals(2, parsed.getTotalObtained());
		assertEquals(2, parsed.getTotalMemberUniqueObtained());
		assertEquals(5, parsed.getItemMeta().get("30000").getQuantityTotal());
		assertEquals(2, parsed.getItemMeta().get("30000").getHolderCount());
		assertEquals("alice", parsed.getItemMeta().get("30000").getContributors().get(0).getRsn());
		assertEquals(3, parsed.getItemMeta().get("30000").getContributors().get(0).getQuantity());
	}
}
