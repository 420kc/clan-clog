package com.clanclog;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClogFetchServiceTest
{
	@Test
	public void mergeResultsKeepsFullProviderTotalsWhenLocalCacheIsPartial()
	{
		Map<String, List<Integer>> providerCategories = new HashMap<>();
		providerCategories.put("hard_treasure_trails", Arrays.asList(1, 2, 3));
		Map<String, List<ClogResult.ClogItem>> providerObtained = new HashMap<>();
		providerObtained.put("hard_treasure_trails", Arrays.asList(
			new ClogResult.ClogItem(1, 1, null),
			new ClogResult.ClogItem(2, 1, null)));
		ClogResult provider = new ClogResult(
			"CBC",
			providerObtained,
			providerCategories,
			new HashMap<>(),
			"2026-05-28 10:24:58",
			null);
		provider.setUniqueObtained(1457);
		provider.setUniqueTotal(1701);

		Map<String, List<Integer>> localCategories = new HashMap<>();
		localCategories.put("fortis_colosseum", Arrays.asList(28947));
		Map<String, List<ClogResult.ClogItem>> localObtained = new HashMap<>();
		localObtained.put("fortis_colosseum",
			Arrays.asList(new ClogResult.ClogItem(28947, 1, null)));
		ClogResult local = new ClogResult(
			"CBC",
			localObtained,
			localCategories,
			new HashMap<>(),
			"2026-05-29T18:24:04Z",
			null);
		local.setUniqueObtained(1);
		local.setUniqueTotal(1701);

		ClogResult merged = ClogFetchService.mergeResults(provider, local, new HashMap<>());

		assertEquals(1457, merged.getUniqueObtained());
		assertEquals(1701, merged.getUniqueTotal());
		assertTrue(merged.getObtainedItems().containsKey("hard_treasure_trails"));
		assertTrue(merged.getObtainedItems().containsKey("fortis_colosseum"));
	}

	@Test
	public void normalizePageKeyMapsRuneProfileRarePagesToClanKeys()
	{
		assertEquals("third_age", ClogFetchService.normalizePageKey("3rd Age"));
		assertEquals("hard_rare",
			ClogFetchService.normalizePageKey("Hard Treasure Trails (Rare)"));
		assertEquals("elite_rare",
			ClogFetchService.normalizePageKey("Elite Treasure Trails (Rare)"));
		assertEquals("master_rare",
			ClogFetchService.normalizePageKey("Master Treasure Trails (Rare)"));
	}
}
