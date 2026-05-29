package com.clanclog;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LocalClogCacheTest
{
	@Test
	public void mergeCategoryPreservesRicherObtainedItems() throws Exception
	{
		LocalClogCache cache = new LocalClogCache(new Gson(), tempDir());

		cache.mergeCategory("420 kc", "phantom_muspah",
			Arrays.asList(1, 2, 3),
			Arrays.asList(
				new ClogResult.ClogItem(1, 1, null),
				new ClogResult.ClogItem(2, 1, null)));

		cache.mergeCategory("420 kc", "phantom_muspah",
			Arrays.asList(1, 2, 3),
			Arrays.asList(new ClogResult.ClogItem(1, 3, null)));

		ClogResult result = cache.toClogResult("420 kc", new HashMap<>());
		List<ClogResult.ClogItem> obtained = result.getObtainedItems().get("phantom_muspah");
		Map<Integer, Integer> counts = countsById(obtained);

		assertNotNull(result.getLastChanged());
		assertEquals(2, obtained.size());
		assertEquals(Integer.valueOf(3), counts.get(1));
		assertEquals(Integer.valueOf(1), counts.get(2));
	}

	@Test
	public void mergeCategoryReplacesWhenCatalogChanges() throws Exception
	{
		LocalClogCache cache = new LocalClogCache(new Gson(), tempDir());

		cache.mergeCategory("420 kc", "phantom_muspah",
			Arrays.asList(1, 2, 3),
			Arrays.asList(
				new ClogResult.ClogItem(1, 1, null),
				new ClogResult.ClogItem(2, 1, null)));

		cache.mergeCategory("420 kc", "phantom_muspah",
			Arrays.asList(1, 3, 4),
			Arrays.asList(new ClogResult.ClogItem(1, 1, null)));

		ClogResult result = cache.toClogResult("420 kc", new HashMap<>());
		List<ClogResult.ClogItem> obtained = result.getObtainedItems().get("phantom_muspah");
		Map<Integer, Integer> counts = countsById(obtained);

		assertEquals(1, obtained.size());
		assertTrue(counts.containsKey(1));
	}

	@Test
	public void categoryCountReflectsCapturedFootprint() throws Exception
	{
		LocalClogCache cache = new LocalClogCache(new Gson(), tempDir());

		cache.mergeCategory("420 kc", "phantom_muspah",
			Arrays.asList(1, 2, 3),
			Arrays.asList(new ClogResult.ClogItem(1, 1, null)));
		cache.mergeCategory("420 kc", "zulrah",
			Arrays.asList(4, 5, 6),
			Arrays.asList(new ClogResult.ClogItem(4, 1, null)));

		assertEquals(2, cache.categoryCount("420 kc"));
		assertEquals(0, cache.categoryCount("missing kc"));
	}

	@Test
	public void providerCachePreservesLocalCategoryGains() throws Exception
	{
		LocalClogCache cache = new LocalClogCache(new Gson(), tempDir());
		cache.mergeCategory("420 kc", "phantom_muspah",
			Arrays.asList(1, 2, 3),
			Arrays.asList(
				new ClogResult.ClogItem(1, 2, null),
				new ClogResult.ClogItem(2, 1, null)));

		Map<String, List<Integer>> categories = new HashMap<>();
		categories.put("phantom_muspah", Arrays.asList(1, 2, 3));
		Map<String, List<ClogResult.ClogItem>> obtained = new HashMap<>();
		obtained.put("phantom_muspah",
			Arrays.asList(new ClogResult.ClogItem(1, 1, null)));
		cache.cacheResult(new ClogResult("420 kc", obtained, categories, new HashMap<>(), null, null));

		ClogResult result = cache.toClogResult("420 kc", new HashMap<>());
		Map<Integer, Integer> counts = countsById(result.getObtainedItems().get("phantom_muspah"));

		assertEquals(2, counts.size());
		assertEquals(Integer.valueOf(2), counts.get(1));
		assertEquals(Integer.valueOf(1), counts.get(2));
	}

	private static File tempDir() throws Exception
	{
		return Files.createTempDirectory("clan-clog-cache-test").toFile();
	}

	private static Map<Integer, Integer> countsById(List<ClogResult.ClogItem> items)
	{
		Map<Integer, Integer> counts = new HashMap<>();
		for (ClogResult.ClogItem item : items)
		{
			counts.put(item.getId(), item.getCount());
		}
		return counts;
	}
}
