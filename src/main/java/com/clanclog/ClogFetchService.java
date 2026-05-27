package com.clanclog;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

/**
 * Fetches collection log data for individual players from TempleOSRS (primary)
 * and RuneProfile (fallback). Ported from kcpdev's {@code ClogService} (Temple
 * lane) and {@code RuneProfileService} (clog lane) for the clan-aggregate use
 * case.
 *
 * <p>Per the runtime-first architecture: the plugin compiles its own clan clog
 * by fetching each member's clog from external providers and unioning them
 * client-side. No killclog.com backend dependency.
 *
 * <p>Global caches (categories + item names) are loaded once per session and
 * shared across all lookups. Per-player results are disk-cached via
 * {@link LocalClogCache}. Failure cooldowns prevent hammering providers that
 * are down.
 */
@Slf4j
@Singleton
public class ClogFetchService
{
	private static final String TEMPLE_CATEGORIES_URL =
		"https://templeosrs.com/api/collection-log/categories.php";
	private static final String TEMPLE_PLAYER_URL =
		"https://templeosrs.com/api/collection-log/player_collection_log.php";
	private static final String RUNEPROFILE_BASE_URL =
		"https://api.runeprofile.com/v1/accounts/";
	private static final String WIKI_MAPPING_URL =
		"https://prices.runescape.wiki/api/v1/osrs/mapping";

	/** Skip a provider for this player if it failed within this window. */
	private static final long FAILURE_TTL_MS = 3 * 60 * 1000;

	/** RuneProfile page names that don't normalize cleanly to Temple category keys. */
	private static final Map<String, String> PAGE_KEY_OVERRIDES = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	static
	{
		PAGE_KEY_OVERRIDES.put("Kree'arra", "kree_arra");
		PAGE_KEY_OVERRIDES.put("The Hueycoatl", "hueycoatl");
		PAGE_KEY_OVERRIDES.put("The Royal Titans", "royal_titans");
	}

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final LocalClogCache localClogCache;

	// Global caches (loaded once, reused across all lookups)
	private volatile Map<String, List<Integer>> cachedCategories;
	private volatile Map<Integer, String> cachedItemNames;

	// In-flight futures for global caches (prevents duplicate HTTP requests)
	private volatile CompletableFuture<Map<String, List<Integer>>> categoriesFlight;
	private volatile CompletableFuture<Map<Integer, String>> namesFlight;

	// Per-player failure cooldowns
	private final Map<String, Long> templeFailures = new ConcurrentHashMap<>();
	private final Map<String, Long> runeProfileFailures = new ConcurrentHashMap<>();

	@Inject
	ClogFetchService(OkHttpClient httpClient, Gson gson, LocalClogCache localClogCache)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.localClogCache = localClogCache;
	}

	/**
	 * Fetch clog data for a single player. Priority: Temple > RuneProfile.
	 * Disk cache via {@link LocalClogCache} provides stale-while-revalidate:
	 * if both providers fail, the most recent cached result is returned.
	 *
	 * <p>Categories and item names are fetched once (global) and shared across
	 * all lookups in the session. In-flight dedup prevents redundant HTTP
	 * requests for the global data.
	 */
	public CompletableFuture<ClogResult> lookup(String playerName)
	{
		String key = playerName.toLowerCase();

		// Kick off global data in parallel (no-ops if already cached)
		CompletableFuture<Map<String, List<Integer>>> catFuture = fetchCategories();
		CompletableFuture<Map<Integer, String>> namesFuture = fetchItemNames();

		return catFuture.thenCombine(namesFuture, (cats, names) -> new Object[] {cats, names})
			.thenCompose(globals ->
			{
				@SuppressWarnings("unchecked")
				Map<String, List<Integer>> cats = (Map<String, List<Integer>>) globals[0];
				@SuppressWarnings("unchecked")
				Map<Integer, String> names = (Map<Integer, String>) globals[1];

				// Fire both providers in parallel, keep whichever has the
				// freshest data.  Clog items only accumulate so the result
				// with more obtained items is the most recent sync.
				CompletableFuture<ClogResult> templeFuture =
					tryTemple(playerName, key, cats, names);
				CompletableFuture<ClogResult> rpFuture =
					tryRuneProfile(playerName, key, names);

				return templeFuture.thenCombine(rpFuture,
					(temple, rp) -> pickFreshest(temple, rp))
					.thenApply(result ->
					{
						if (result != null)
						{
							return result;
						}
						// Both providers failed -- fall back to local disk cache
						if (localClogCache.hasDataFor(playerName))
						{
							log.debug("Both providers failed for '{}', using disk cache", playerName);
							return localClogCache.toClogResult(playerName,
								names != null ? names : new HashMap<>());
						}
						return null;
					});
			});
	}

	/**
	 * Return a previously cached clog result immediately (no network),
	 * or null if no local data exists for this player.
	 */
	public ClogResult getCached(String playerName)
	{
		if (!localClogCache.hasDataFor(playerName))
		{
			return null;
		}
		Map<Integer, String> names = cachedItemNames;
		return localClogCache.toClogResult(playerName, names != null ? names : new HashMap<>());
	}

	/** Clear failure cooldowns (e.g. on login). TTLs auto-expire otherwise. */
	public void clearFailures()
	{
		templeFailures.clear();
		runeProfileFailures.clear();
	}

	/**
	 * Compare two clog results and return whichever represents the most
	 * recent sync. Collection log items only accumulate, so the result
	 * with more obtained items is fresher. When counts are close (within 5),
	 * prefer Temple for its richer metadata (lastChanged, accountType).
	 */
	static ClogResult pickFreshest(ClogResult temple, ClogResult rp)
	{
		if (temple == null)
		{
			return rp;
		}
		if (rp == null)
		{
			return temple;
		}

		int templeCount = obtainedCount(temple);
		int rpCount = obtainedCount(rp);

		if (rpCount > templeCount + 5)
		{
			log.debug("RuneProfile fresher for '{}': {} vs {} obtained",
				rp.getPlayerName(), rpCount, templeCount);
			return rp;
		}
		// Temple wins when tied or ahead -- has lastChanged + accountType
		return temple;
	}

	/**
	 * Best-effort obtained count. Uses the explicit uniqueObtained field
	 * when set (RuneProfile populates this from the response root), falls
	 * back to counting items across all categories (Temple path).
	 */
	private static int obtainedCount(ClogResult result)
	{
		if (result.getUniqueObtained() >= 0)
		{
			return result.getUniqueObtained();
		}
		int count = 0;
		for (List<ClogResult.ClogItem> items : result.getObtainedItems().values())
		{
			count += items.size();
		}
		return count;
	}

	// ── Temple fetch ─────────────────────────────────────────────────────────

	private CompletableFuture<ClogResult> tryTemple(String playerName, String key,
		Map<String, List<Integer>> categories, Map<Integer, String> names)
	{
		// Skip if Temple failed recently for this player
		Long failedAt = templeFailures.get(key);
		if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_TTL_MS)
		{
			return CompletableFuture.completedFuture(null);
		}

		String encoded = URLEncoder.encode(playerName, StandardCharsets.UTF_8);
		String url = TEMPLE_PLAYER_URL + "?player=" + encoded + "&categories=all";

		return httpGet(url).thenApply(resp ->
		{
			if (resp.code != 200 || resp.body == null)
			{
				if (resp.code != 404)
				{
					templeFailures.put(key, System.currentTimeMillis());
				}
				return null;
			}

			try
			{
				ClogResult result = parseTempleClog(playerName, resp.body, categories, names);
				if (result != null)
				{
					localClogCache.cacheResult(result);
					log.debug("Temple clog fetched for '{}'", playerName);
				}
				return result;
			}
			catch (Exception e)
			{
				log.debug("Temple parse failed for '{}': {}", playerName, e.getMessage());
				templeFailures.put(key, System.currentTimeMillis());
				return null;
			}
		});
	}

	private ClogResult parseTempleClog(String playerName, String json,
		Map<String, List<Integer>> categories, Map<Integer, String> names)
	{
		JsonObject root = gson.fromJson(json, JsonObject.class);
		JsonObject data = root != null ? root.getAsJsonObject("data") : null;
		if (data == null || !data.has("items"))
		{
			return null;
		}

		String canonicalName = playerName;
		if (data.has("player_name_with_capitalization"))
		{
			canonicalName = data.get("player_name_with_capitalization").getAsString();
		}

		String lastChanged = null;
		if (data.has("last_changed"))
		{
			lastChanged = data.get("last_changed").getAsString();
		}

		AccountType accountType = null;
		if (data.has("game_mode") && !data.get("game_mode").isJsonNull())
		{
			accountType = parseTempleGameMode(data.get("game_mode").getAsString());
		}

		JsonObject itemsObj = data.getAsJsonObject("items");
		Map<String, List<ClogResult.ClogItem>> obtainedItems = new HashMap<>();

		for (Map.Entry<String, JsonElement> entry : itemsObj.entrySet())
		{
			String category = entry.getKey();
			JsonArray items = entry.getValue().getAsJsonArray();
			List<ClogResult.ClogItem> itemList = new ArrayList<>();

			for (JsonElement item : items)
			{
				JsonObject obj = item.getAsJsonObject();
				int id = obj.get("id").getAsInt();
				int count = obj.has("count") ? obj.get("count").getAsInt() : 1;
				String date = obj.has("date") ? obj.get("date").getAsString() : null;
				itemList.add(new ClogResult.ClogItem(id, count, date));
			}

			obtainedItems.put(category, itemList);
		}

		return new ClogResult(
			canonicalName,
			obtainedItems,
			categories != null ? categories : new HashMap<>(),
			names != null ? names : new HashMap<>(),
			lastChanged,
			accountType);
	}

	// ── RuneProfile fetch ────────────────────────────────────────────────────

	private CompletableFuture<ClogResult> tryRuneProfile(String playerName, String key,
		Map<Integer, String> globalNames)
	{
		Long failedAt = runeProfileFailures.get(key);
		if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_TTL_MS)
		{
			return CompletableFuture.completedFuture(null);
		}

		// RSN in path: spaces must be %20 (URLEncoder yields '+', valid only in query)
		String encoded = URLEncoder.encode(playerName, StandardCharsets.UTF_8).replace("+", "%20");
		String url = RUNEPROFILE_BASE_URL + encoded + "/collection-log";

		return httpGet(url).thenApply(resp ->
		{
			if (resp.code == 404)
			{
				// Not synced to RuneProfile -- hold longer than transient failure
				runeProfileFailures.put(key, System.currentTimeMillis());
				return null;
			}
			if (resp.code != 200 || resp.body == null)
			{
				runeProfileFailures.put(key, System.currentTimeMillis());
				return null;
			}

			try
			{
				ClogResult result = parseRuneProfileClog(playerName, resp.body);
				if (result != null)
				{
					localClogCache.cacheResult(result);
					log.debug("RuneProfile clog fetched for '{}'", playerName);
				}
				return result;
			}
			catch (Exception e)
			{
				log.debug("RuneProfile parse failed for '{}': {}", playerName, e.getMessage());
				runeProfileFailures.put(key, System.currentTimeMillis());
				return null;
			}
		});
	}

	/**
	 * Parse the RuneProfile collection-log response into a {@link ClogResult}.
	 * Response shape: {@code { obtained, total, tabs: [{ name, pages: [{ name, items: [{ id, name, quantity }] }] }] }}
	 * Items with {@code quantity > 0} are obtained. Page names are normalized
	 * to Temple-compatible category keys.
	 */
	private ClogResult parseRuneProfileClog(String playerName, String json)
	{
		JsonObject root = gson.fromJson(json, JsonObject.class);
		if (root == null || !root.has("tabs"))
		{
			return null;
		}

		int rootObtained = intField(root, "obtained");
		int rootTotal = intField(root, "total");

		if (!root.get("tabs").isJsonArray())
		{
			return null;
		}
		JsonArray tabs = root.getAsJsonArray("tabs");
		Map<String, List<ClogResult.ClogItem>> obtainedItems = new HashMap<>();
		Map<String, List<Integer>> categoryItems = new HashMap<>();
		Map<Integer, String> itemNames = new HashMap<>();

		for (JsonElement tabEl : tabs)
		{
			if (!tabEl.isJsonObject())
			{
				continue;
			}
			JsonObject tab = tabEl.getAsJsonObject();
			if (!tab.has("pages") || !tab.get("pages").isJsonArray())
			{
				continue;
			}

			JsonArray pages = tab.getAsJsonArray("pages");
			for (JsonElement pageEl : pages)
			{
				if (!pageEl.isJsonObject())
				{
					continue;
				}
				JsonObject page = pageEl.getAsJsonObject();
				if (!page.has("name") || page.get("name").isJsonNull())
				{
					continue;
				}
				String pageName = page.get("name").getAsString();
				if (!page.has("items") || !page.get("items").isJsonArray())
				{
					continue;
				}

				String categoryKey = normalizePageKey(pageName);
				JsonArray items = page.getAsJsonArray("items");

				List<ClogResult.ClogItem> obtained = new ArrayList<>();
				List<Integer> allIds = new ArrayList<>();

				for (JsonElement itemEl : items)
				{
					if (!itemEl.isJsonObject())
					{
						continue;
					}
					JsonObject item = itemEl.getAsJsonObject();
					int id = intField(item, "id");
					int qty = intField(item, "quantity");
					String name = item.has("name") && !item.get("name").isJsonNull()
						? item.get("name").getAsString() : null;

					allIds.add(id);
					if (name != null)
					{
						itemNames.put(id, name);
					}
					if (qty > 0)
					{
						obtained.add(new ClogResult.ClogItem(id, qty, null));
					}
				}

				categoryItems.put(categoryKey, allIds);
				if (!obtained.isEmpty())
				{
					obtainedItems.put(categoryKey, obtained);
				}
			}
		}

		if (categoryItems.isEmpty())
		{
			return null;
		}

		ClogResult result = new ClogResult(
			playerName,
			obtainedItems,
			categoryItems,
			itemNames,
			null,
			null);
		result.setUniqueObtained(rootObtained);
		result.setUniqueTotal(rootTotal);
		return result;
	}

	// ── Global data fetchers (loaded once per session) ────────────────────────

	private CompletableFuture<Map<String, List<Integer>>> fetchCategories()
	{
		if (cachedCategories != null)
		{
			return CompletableFuture.completedFuture(cachedCategories);
		}

		synchronized (this)
		{
			if (cachedCategories != null)
			{
				return CompletableFuture.completedFuture(cachedCategories);
			}
			if (categoriesFlight != null)
			{
				return categoriesFlight;
			}

			categoriesFlight = httpGet(TEMPLE_CATEGORIES_URL).thenApply(resp ->
			{
				try
				{
					if (resp.code != 200 || resp.body == null)
					{
						return null;
					}
					JsonObject root = gson.fromJson(resp.body, JsonObject.class);
					Type type = new TypeToken<Map<String, List<Integer>>>()
					{
					}.getType();
					Map<String, List<Integer>> categories = new HashMap<>();
					for (Map.Entry<String, JsonElement> entry : root.entrySet())
					{
						if (!entry.getValue().isJsonObject())
						{
							continue;
						}
						Map<String, List<Integer>> sectionMap = gson.fromJson(entry.getValue(), type);
						if (sectionMap != null)
						{
							categories.putAll(sectionMap);
						}
					}
					if (categories.isEmpty())
					{
						return null;
					}
					cachedCategories = categories;
					log.debug("Temple categories loaded: {} categories", categories.size());
					return categories;
				}
				catch (Exception e)
				{
					log.debug("Failed to parse clog categories: {}", e.getMessage());
					return null;
				}
				finally
				{
					categoriesFlight = null;
				}
			});

			return categoriesFlight;
		}
	}

	private CompletableFuture<Map<Integer, String>> fetchItemNames()
	{
		if (cachedItemNames != null)
		{
			return CompletableFuture.completedFuture(cachedItemNames);
		}

		synchronized (this)
		{
			if (cachedItemNames != null)
			{
				return CompletableFuture.completedFuture(cachedItemNames);
			}
			if (namesFlight != null)
			{
				return namesFlight;
			}

			namesFlight = httpGet(WIKI_MAPPING_URL).thenApply(resp ->
			{
				try
				{
					if (resp.code != 200 || resp.body == null)
					{
						return null;
					}
					JsonArray arr = gson.fromJson(resp.body, JsonArray.class);
					Map<Integer, String> names = new HashMap<>();

					for (JsonElement elem : arr)
					{
						JsonObject obj = elem.getAsJsonObject();
						if (obj.has("id") && obj.has("name"))
						{
							names.put(obj.get("id").getAsInt(), obj.get("name").getAsString());
						}
					}

					cachedItemNames = names;
					log.debug("Item names loaded: {} items", names.size());
					return names;
				}
				catch (Exception e)
				{
					log.debug("Failed to parse item names: {}", e.getMessage());
					return null;
				}
				finally
				{
					namesFlight = null;
				}
			});

			return namesFlight;
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	/**
	 * Normalize a RuneProfile collection-log page name to Temple-compatible
	 * category key format. Handles apostrophe edge cases via override map.
	 */
	static String normalizePageKey(String pageName)
	{
		String override = PAGE_KEY_OVERRIDES.get(pageName);
		if (override != null)
		{
			return override;
		}
		return pageName.toLowerCase().replace("'", "")
			.replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
	}

	private static AccountType parseTempleGameMode(String gameMode)
	{
		if (gameMode == null)
		{
			return null;
		}
		switch (gameMode.toLowerCase())
		{
			case "1":
			case "ironman":
				return AccountType.IRONMAN;
			case "2":
			case "hardcore ironman":
				return AccountType.HARDCORE_IRONMAN;
			case "3":
			case "ultimate ironman":
				return AccountType.ULTIMATE_IRONMAN;
			case "4":
			case "group ironman":
				return AccountType.GROUP_IRONMAN;
			case "5":
			case "hardcore group ironman":
				return AccountType.HARDCORE_GROUP_IRONMAN;
			case "6":
			case "unranked group ironman":
				return AccountType.UNRANKED_GROUP_IRONMAN;
			default:
				return null;
		}
	}

	private static int intField(JsonObject obj, String field)
	{
		return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsInt() : 0;
	}

	private CompletableFuture<HttpUtil.HttpResult> httpGet(String url)
	{
		return HttpUtil.httpGet(httpClient, url);
	}
}
