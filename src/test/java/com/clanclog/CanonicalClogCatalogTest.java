package com.clanclog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CanonicalClogCatalogTest
{
	@Test
	public void fallbackMatchesKillClogRareCanon()
	{
		Map<String, List<Integer>> catalogs = CanonicalClogCatalog.fallbackCatalogs();

		assertEquals(23, catalogs.get("third_age").size());
		assertTrue(catalogs.get("third_age").contains(10344));
		assertFalse(catalogs.get("third_age").contains(23185));
		assertEquals(20, catalogs.get("gilded").size());
		assertEquals(45, catalogs.get("master_rare").size());
	}

	@Test
	public void parsesApiCatalogPayload()
	{
		Map<String, List<Integer>> catalogs = CanonicalClogCatalog.parseCatalogs(
			new Gson(),
			"{\"catalogs\":{\"third_age\":[10334,10344],\"gilded\":[3481]}}");

		assertEquals(List.of(10334, 10344), catalogs.get("third_age"));
		assertEquals(List.of(3481), catalogs.get("gilded"));
	}

	@Test
	public void mergeOverwritesFixedCatalogsOnly()
	{
		Map<String, List<Integer>> merged = CanonicalClogCatalog.mergeFixedCatalogs(
			Map.of(
				"third_age", List.of(23185),
				"zulrah", List.of(12921)),
			Map.of("third_age", List.of(10334, 10344)));

		assertEquals(List.of(10334, 10344), merged.get("third_age"));
		assertEquals(List.of(12921), merged.get("zulrah"));
	}
}
