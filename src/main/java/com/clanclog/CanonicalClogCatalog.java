package com.clanclog;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Client adapter for killclog-api's canonical fixed collection-log catalogs.
 *
 * <p>The API owns the catalog contract at {@code /api/catalog/clog}. These
 * defaults are only an offline fallback so provider rebuilds can keep working
 * when the backend catalog cannot be fetched.
 */
final class CanonicalClogCatalog
{
	static final String URL = "https://killclog.com/api/catalog/clog";

	private static final Map<String, List<Integer>> FALLBACK_CATALOGS = fallbackCatalogs0();

	private CanonicalClogCatalog()
	{
	}

	static Map<String, List<Integer>> fallbackCatalogs()
	{
		return copyCatalogs(FALLBACK_CATALOGS);
	}

	static int[] fallbackArray(String category)
	{
		List<Integer> ids = FALLBACK_CATALOGS.get(category);
		if (ids == null)
		{
			return new int[0];
		}
		int[] out = new int[ids.size()];
		for (int i = 0; i < ids.size(); i++)
		{
			out[i] = ids.get(i);
		}
		return out;
	}

	static Map<String, List<Integer>> parseCatalogs(Gson gson, @Nullable String json)
	{
		if (json == null || json.isBlank())
		{
			return Collections.emptyMap();
		}

		JsonObject root = gson.fromJson(json, JsonObject.class);
		if (root == null || !root.has("catalogs") || !root.get("catalogs").isJsonObject())
		{
			return Collections.emptyMap();
		}

		Map<String, List<Integer>> parsed = new LinkedHashMap<>();
		JsonObject catalogs = root.getAsJsonObject("catalogs");
		for (Map.Entry<String, JsonElement> entry : catalogs.entrySet())
		{
			if (!entry.getValue().isJsonArray())
			{
				continue;
			}
			List<Integer> ids = numericIds(entry.getValue().getAsJsonArray());
			if (!ids.isEmpty())
			{
				parsed.put(entry.getKey(), ids);
			}
		}
		return parsed;
	}

	static Map<String, List<Integer>> mergeFixedCatalogs(
		@Nullable Map<String, List<Integer>> base,
		@Nullable Map<String, List<Integer>> fixed)
	{
		Map<String, List<Integer>> merged = copyCatalogs(base);
		Map<String, List<Integer>> catalogs = fixed != null && !fixed.isEmpty()
			? fixed : FALLBACK_CATALOGS;
		for (Map.Entry<String, List<Integer>> entry : catalogs.entrySet())
		{
			merged.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}
		return merged;
	}

	private static List<Integer> numericIds(JsonArray raw)
	{
		List<Integer> ids = new ArrayList<>();
		for (JsonElement element : raw)
		{
			if (element == null || element.isJsonNull())
			{
				continue;
			}
			try
			{
				ids.add(element.getAsInt());
			}
			catch (UnsupportedOperationException | NumberFormatException ex)
			{
				// Ignore malformed catalog rows; the fallback keeps the client safe.
			}
		}
		return ids;
	}

	private static Map<String, List<Integer>> copyCatalogs(
		@Nullable Map<String, List<Integer>> catalogs)
	{
		Map<String, List<Integer>> copy = new LinkedHashMap<>();
		if (catalogs == null)
		{
			return copy;
		}
		for (Map.Entry<String, List<Integer>> entry : catalogs.entrySet())
		{
			copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}
		return copy;
	}

	private static Map<String, List<Integer>> fallbackCatalogs0()
	{
		Map<String, List<Integer>> catalogs = new LinkedHashMap<>();
		catalogs.put("third_age", list(
			10350, 10348, 10346, 23242, 10352,
			10334, 10330, 10332, 10336,
			10342, 10338, 10340, 10344,
			12426, 12422, 12437, 12424,
			23336, 23339, 23345, 23342,
			20014, 20011));
		catalogs.put("gilded", list(
			3486, 3481, 3483, 3485, 3488,
			20146, 20149, 20152, 20155, 20158, 20161,
			12389, 12391, 23258, 23261, 23264, 23267,
			23276, 23279, 23282));
		catalogs.put("hard_rare", list(
			10350, 10348, 10346, 23242, 10352,
			10334, 10330, 10332, 10336,
			10342, 10338, 10340, 10344,
			3486, 3481, 3483, 3485, 3488,
			20146, 20149, 20152, 20155, 20158, 20161));
		catalogs.put("elite_rare", list(
			10350, 10348, 10346, 23242, 10352,
			10334, 10330, 10332, 10336,
			10342, 10338, 10340, 10344,
			12426, 12422, 12437, 12424,
			3486, 3481, 3483, 3485, 3488,
			20146, 20149, 20152, 20155, 20158, 20161,
			12389, 12391, 23258, 23261, 23264, 23267,
			23276, 23279, 23282,
			12371, 20005));
		catalogs.put("master_rare", list(
			10350, 10348, 10346, 23242, 10352,
			10334, 10330, 10332, 10336,
			10342, 10338, 10340, 10344,
			12426, 12422, 12437, 12424,
			23336, 23339, 23345, 23342,
			20014, 20011,
			3486, 3481, 3483, 3485, 3488,
			20146, 20149, 20152, 20155, 20158, 20161,
			12389, 12391, 23258, 23261, 23264, 23267,
			23276, 23279, 23282,
			20059, 20017));
		return catalogs;
	}

	private static List<Integer> list(int... ids)
	{
		List<Integer> out = new ArrayList<>(ids.length);
		for (int id : ids)
		{
			out.add(id);
		}
		return out;
	}
}
