package com.clanclog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ClanTooltipDataBuilderTest
{
	@Test
	public void buildsEmptyCategoryFromCatalog()
	{
		ClanClogResult result = ClanClogResult.forRoster("test-clan", "Test Clan",
			1, Collections.emptyMap());
		result.setClog(new ClanClogResult.ClogUnion(
			Collections.emptyMap(),
			0,
			0,
			Collections.emptyMap(),
			Map.of("zulrah", List.of(12921, 13200))));

		TooltipData data = new ClanTooltipDataBuilder()
			.buildForCategory("Zulrah", "zulrah", result);

		assertNotNull(data);
		assertEquals(0, data.obtainedCount);
		assertEquals(2, data.totalItems);
		assertEquals(List.of(12921, 13200), data.allItemIds);
		assertTrue(data.obtainedIds.isEmpty());
	}

	@Test
	public void buildsPartialCategoryFromCatalog()
	{
		ClanClogResult result = ClanClogResult.forRoster("test-clan", "Test Clan",
			1, Collections.emptyMap());
		result.setClog(new ClanClogResult.ClogUnion(
			Map.of("shellbane_gryphon", List.of(30000, 30001)),
			2,
			2,
			Collections.emptyMap(),
			Map.of("shellbane_gryphon", List.of(30000, 30001, 30002, 30003))));

		TooltipData data = new ClanTooltipDataBuilder()
			.buildForCategory("Shellbane Gryphon", "shellbane_gryphon", result);

		assertNotNull(data);
		assertEquals(2, data.obtainedCount);
		assertEquals(4, data.totalItems);
		assertEquals(List.of(30000, 30001, 30002, 30003), data.allItemIds);
		assertTrue(data.obtainedIds.contains(30000));
		assertTrue(data.obtainedIds.contains(30001));
	}

	@Test
	public void refusesObtainedOnlyCategoryWithoutCatalog()
	{
		ClanClogResult result = ClanClogResult.forRoster("test-clan", "Test Clan",
			1, Collections.emptyMap());
		result.setClog(new ClanClogResult.ClogUnion(
			Map.of("sol_heredit", List.of(30010, 30011)),
			2,
			2,
			Collections.emptyMap(),
			null));

		TooltipData data = new ClanTooltipDataBuilder()
			.buildForCategory("Sol Heredit", "sol_heredit", result);

		assertNull(data);
	}

	@Test
	public void buildsRareBucketFromFixedItemsPlusProviderCatalog()
	{
		ClanClogResult result = ClanClogResult.forRoster("test-clan", "Test Clan",
			1, Collections.emptyMap());
		result.setClog(new ClanClogResult.ClogUnion(
			Map.of("third_age", List.of(10334, 10350)),
			2,
			2,
			Map.of(
				"10334", new ClanClogResult.ItemMeta(2, 5),
				"10350", new ClanClogResult.ItemMeta(1, 3)),
			Map.of("third_age", List.of(10334, 10350, 10348))));

		TooltipData data = new ClanTooltipDataBuilder()
			.buildRareBucketData("3rd Age", "third_age", new int[]{999}, result);

		assertNotNull(data);
		assertEquals(2, data.obtainedCount);
		assertEquals(4, data.totalItems);
		assertEquals(List.of(999, 10334, 10350, 10348), data.allItemIds);
		assertEquals(5, data.obtainedCounts.get(10334).intValue());
		assertEquals(3, data.obtainedCounts.get(10350).intValue());
		assertEquals(2, data.holderCounts.get(10334).intValue());
		assertEquals(1, data.holderCounts.get(10350).intValue());
	}

	@Test
	public void buildsRareBucketFromFixedItemsWhenCategoryCatalogMissing()
	{
		ClanClogResult result = ClanClogResult.forRoster("test-clan", "Test Clan",
			1, Collections.emptyMap());
		result.setClog(new ClanClogResult.ClogUnion(
			Collections.emptyMap(),
			1,
			1,
			Map.of("10334", new ClanClogResult.ItemMeta(2)),
			Collections.emptyMap()));

		TooltipData data = new ClanTooltipDataBuilder()
			.buildRareBucketData("3rd Age", "third_age",
				new int[]{10334, 10350}, result);

		assertNotNull(data);
		assertEquals(1, data.obtainedCount);
		assertEquals(2, data.totalItems);
		assertTrue(data.obtainedIds.contains(10334));
		assertEquals(2, data.obtainedCounts.get(10334).intValue());
	}
}
