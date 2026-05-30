package com.clanclog;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
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

	@Test
	public void providerCacheLoadsDiskBeforeReplacingLocalGains() throws Exception
	{
		File dir = tempDir();
		writeCachedPlayer(dir);

		LocalClogCache cache = new LocalClogCache(new Gson(), dir);
		Map<String, List<Integer>> categories = new HashMap<>();
		categories.put("phantom_muspah", Arrays.asList(1, 2, 3));
		Map<String, List<ClogResult.ClogItem>> obtained = new HashMap<>();
		obtained.put("phantom_muspah",
			Arrays.asList(new ClogResult.ClogItem(1, 1, null)));
		ClogResult provider = new ClogResult(
			"420 kc", obtained, categories, new HashMap<>(), null, null);
		provider.setUniqueObtained(1);
		provider.setUniqueTotal(1701);

		cache.cacheResult(provider);

		ClogResult result = cache.toClogResult("420 kc", new HashMap<>());
		Map<Integer, Integer> muspahCounts =
			countsById(result.getObtainedItems().get("phantom_muspah"));

		assertEquals(2, muspahCounts.size());
		assertEquals(Integer.valueOf(2), muspahCounts.get(1));
		assertEquals(Integer.valueOf(1), muspahCounts.get(2));
		assertTrue(result.getCategoryItems().containsKey("fortis_colosseum"));
		assertEquals(2, result.getUniqueObtained());
	}

	@Test
	public void mergeCategoryLoadsDiskBeforeReplacingLocalGains() throws Exception
	{
		File dir = tempDir();
		writeCachedPlayer(dir);

		LocalClogCache cache = new LocalClogCache(new Gson(), dir);
		cache.mergeCategory("420 kc", "phantom_muspah",
			Arrays.asList(1, 2, 3),
			Arrays.asList(new ClogResult.ClogItem(1, 1, null)));

		ClogResult result = cache.toClogResult("420 kc", new HashMap<>());
		Map<Integer, Integer> muspahCounts =
			countsById(result.getObtainedItems().get("phantom_muspah"));

		assertEquals(2, muspahCounts.size());
		assertEquals(Integer.valueOf(2), muspahCounts.get(1));
		assertEquals(Integer.valueOf(1), muspahCounts.get(2));
		assertTrue(result.getCategoryItems().containsKey("fortis_colosseum"));
	}

	@Test
	public void updateTotalsLoadsDiskBeforeSavingTotals() throws Exception
	{
		File dir = tempDir();
		writeCachedPlayer(dir);

		LocalClogCache cache = new LocalClogCache(new Gson(), dir);
		cache.updateTotals("420 kc", 1457, 1701);

		ClogResult result = cache.toClogResult("420 kc", new HashMap<>());

		assertEquals(1457, result.getUniqueObtained());
		assertEquals(1701, result.getUniqueTotal());
		assertTrue(result.getCategoryItems().containsKey("fortis_colosseum"));
	}

	@Test
	public void toClogResultLoadsDiskCache() throws Exception
	{
		File dir = tempDir();
		writeCachedPlayer(dir);

		LocalClogCache cache = new LocalClogCache(new Gson(), dir);
		ClogResult result = cache.toClogResult("420 kc", new HashMap<>());

		assertNotNull(result);
		assertTrue(result.getCategoryItems().containsKey("fortis_colosseum"));
		assertEquals(2, result.getUniqueObtained());
	}

	private static File tempDir() throws Exception
	{
		return Files.createTempDirectory("clan-clog-cache-test").toFile();
	}

	private static void writeCachedPlayer(File dir) throws Exception
	{
		Files.writeString(new File(dir, "420_kc.json").toPath(), "{"
			+ "\"playerName\":\"420 kc\","
			+ "\"lastUpdated\":\"2026-05-30T00:00:00Z\","
			+ "\"uniqueObtained\":2,"
			+ "\"uniqueTotal\":1701,"
			+ "\"categories\":{"
			+ "\"phantom_muspah\":[1,2,3],"
			+ "\"fortis_colosseum\":[4,5]"
			+ "},"
			+ "\"obtained\":{"
			+ "\"phantom_muspah\":[{\"id\":1,\"count\":2},{\"id\":2,\"count\":1}],"
			+ "\"fortis_colosseum\":[{\"id\":4,\"count\":1}]"
			+ "}"
			+ "}", StandardCharsets.UTF_8);
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
