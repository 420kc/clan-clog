package com.clanclog;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
		assertEquals(Arrays.asList(1, 2, 3),
			merged.getCategoryItems().get("hard_treasure_trails"));
	}

	@Test
	public void mergeResultsUnionsProviderAndLocalRareItems()
	{
		Map<String, List<Integer>> providerCategories = new HashMap<>();
		providerCategories.put("third_age", Arrays.asList(10334, 10336, 10338));
		Map<String, List<ClogResult.ClogItem>> providerObtained = new HashMap<>();
		providerObtained.put("third_age",
			Arrays.asList(new ClogResult.ClogItem(10334, 2, null)));
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
		localCategories.put("third_age", Arrays.asList(10334, 10336, 10338, 10340));
		Map<String, List<ClogResult.ClogItem>> localObtained = new HashMap<>();
		localObtained.put("third_age",
			Arrays.asList(new ClogResult.ClogItem(10340, 1, null)));
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
		assertEquals(2, merged.getObtainedItems().get("third_age").size());
		assertTrue(merged.getObtainedItems().get("third_age").stream()
			.anyMatch(item -> item.getId() == 10334 && item.getCount() == 2));
		assertTrue(merged.getObtainedItems().get("third_age").stream()
			.anyMatch(item -> item.getId() == 10340 && item.getCount() == 1));
		assertEquals(4, merged.getCategoryItems().get("third_age").size());
	}

	@Test
	public void pickFreshestKeepsTempleAccountTypeWhenRuneProfileWins()
	{
		ClogResult temple = resultWithCount("CBC", 100,
			"2026-05-28 10:24:58", AccountType.GROUP_IRONMAN);
		ClogResult runeProfile = resultWithCount("CBC", 108, null, null);

		ClogResult picked = ClogFetchService.pickFreshest(temple, runeProfile);

		assertEquals(108, picked.getUniqueObtained());
		assertEquals(AccountType.GROUP_IRONMAN, picked.getTempleAccountType());
		assertNull(picked.getLastChanged());
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

	private static ClogResult resultWithCount(String playerName, int uniqueObtained,
		String lastChanged, AccountType accountType)
	{
		ClogResult result = new ClogResult(
			playerName,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			lastChanged,
			accountType);
		result.setUniqueObtained(uniqueObtained);
		result.setUniqueTotal(1701);
		return result;
	}
}
