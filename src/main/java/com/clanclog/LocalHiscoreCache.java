package com.clanclog;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Disk-backed hiscore result cache. Stores per-player {@link HiscoreResult}
 * in {@code ~/.runelite/clan-clog/hiscores/} so clan re-searches are instant
 * from cache with background refresh for stale entries.
 *
 * <p>Cache TTL is 1 hour for clan batch use. Stale entries are still served
 * immediately (stale-while-revalidate) so the panel fills without waiting
 * for 100+ HTTP requests.
 *
 * <p>Follows the same disk I/O pattern as {@link LocalClogCache}: single
 * background writer thread, debounced writes, daemon thread.
 */
@Slf4j
@Singleton
public class LocalHiscoreCache
{
	private static final File CACHE_DIR = new File(RuneLite.RUNELITE_DIR, "clan-clog/hiscores");
	private static final long CACHE_TTL_MS = 60 * 60 * 1000; // 1 hour

	private final Map<String, CachedEntry> memory = new ConcurrentHashMap<>();
	private final Gson gson;
	private volatile ExecutorService diskWriter = newDiskWriter();

	@Inject
	public LocalHiscoreCache(Gson gson)
	{
		this.gson = gson;
	}

	/**
	 * Get a cached hiscore result for a player. Checks memory first, then
	 * disk. Returns null if no cached data exists anywhere.
	 */
	@Nullable
	public HiscoreResult get(String rsn)
	{
		String normalized = RsnNormalizer.normalize(rsn);
		if (normalized.isEmpty())
		{
			return null;
		}
		String key = RsnNormalizer.cacheKey(normalized);
		CachedEntry entry = memory.get(key);
		if (entry != null)
		{
			return entry.result;
		}

		// Try disk
		CachedEntry loaded = loadFromDisk(normalized);
		if (loaded != null)
		{
			memory.put(key, loaded);
			return loaded.result;
		}
		return null;
	}

	/**
	 * True if the cached entry is past TTL and should be refreshed in the
	 * background. Returns true if no cache exists (caller should fetch).
	 */
	public boolean isStale(String rsn)
	{
		String normalized = RsnNormalizer.normalize(rsn);
		if (normalized.isEmpty())
		{
			return true;
		}
		String key = RsnNormalizer.cacheKey(normalized);
		CachedEntry entry = memory.get(key);
		if (entry == null)
		{
			// Check disk before declaring stale
			CachedEntry loaded = loadFromDisk(normalized);
			if (loaded != null)
			{
				memory.put(key, loaded);
				return loaded.isStale();
			}
			return true;
		}
		return entry.isStale();
	}

	/**
	 * Store a hiscore result in both memory and disk cache.
	 */
	public void put(String rsn, HiscoreResult result)
	{
		if (rsn == null || result == null)
		{
			return;
		}

		String normalized = RsnNormalizer.normalize(rsn);
		if (normalized.isEmpty())
		{
			return;
		}
		String key = RsnNormalizer.cacheKey(normalized);
		CachedEntry entry = new CachedEntry(result, System.currentTimeMillis());
		memory.put(key, entry);

		// Async disk write
		DiskRecord record = new DiskRecord();
		record.rsn = normalized;
		record.timestamp = entry.timestamp;
		record.accountType = result.getAccountType().name();
		record.bossKills = result.getBossKills();
		record.bossRanks = result.getBossRanks();
		record.activityScores = result.getActivityScores();
		record.activityRanks = result.getActivityRanks();
		record.skillLevels = result.getSkillLevels();
		record.totalLevel = result.getTotalLevel();
		record.totalXp = result.getTotalXp();
		record.combatLevel = result.getCombatLevel();
		record.overallRank = result.getOverallRank();

		try
		{
			diskWriter.execute(() -> saveToDisk(normalized, record));
		}
		catch (RejectedExecutionException ignored)
		{
			log.debug("Disk write rejected for {}", rsn);
		}
	}

	public void shutdown()
	{
		ExecutorService old = diskWriter;
		diskWriter = newDiskWriter();
		old.shutdown();
	}

	private void saveToDisk(String rsn, DiskRecord record)
	{
		try
		{
			if (!CACHE_DIR.exists())
			{
				CACHE_DIR.mkdirs();
			}
			File file = getCacheFile(rsn);
			try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))
			{
				gson.toJson(record, writer);
			}
		}
		catch (IOException e)
		{
			log.debug("Failed to save hiscore cache for '{}': {}", rsn, e.getMessage());
		}
	}

	@Nullable
	private CachedEntry loadFromDisk(String rsn)
	{
		File file = getCacheFile(rsn);
		if (!file.exists())
		{
			return null;
		}
		try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			DiskRecord record = gson.fromJson(reader, DiskRecord.class);
			if (record == null || record.bossKills == null)
			{
				return null;
			}
			// Guard against sanitized-filename collisions (e.g. "a b" and "a_b"
			// both map to "a_b.json"): only trust a record whose stored RSN
			// matches the one we were asked for, otherwise treat as a cache miss.
			if (record.rsn == null || !record.rsn.equalsIgnoreCase(rsn))
			{
				return null;
			}

			AccountType type;
			try
			{
				type = AccountType.valueOf(record.accountType);
			}
			catch (Exception e)
			{
				type = AccountType.REGULAR;
			}

			// Rebuild HiscoreResult from the cached data. Activity + skill maps
			// are restored so cached members keep their clue/PvP/activity
			// aggregates (RosterClogBuilder reads getActivityScore). Older cache
			// files predating these fields deserialize them as null, which the
			// HiscoreResult constructor coerces to empty maps.
			HiscoreResult result = new HiscoreResult(
				type, record.bossKills, record.bossRanks,
				record.activityScores, record.activityRanks, record.skillLevels,
				record.totalLevel, record.totalXp, record.combatLevel,
				record.overallRank);

			return new CachedEntry(result, record.timestamp);
		}
		catch (Exception e)
		{
			log.debug("Failed to load hiscore cache for '{}': {}", rsn, e.getMessage());
			return null;
		}
	}

	private File getCacheFile(String rsn)
	{
		String sanitized = RsnNormalizer.cacheKey(rsn)
			.replace(' ', '_')
			.replaceAll("[^a-z0-9_-]", "");
		return new File(CACHE_DIR, sanitized + ".json");
	}

	private static ExecutorService newDiskWriter()
	{
		return Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "clan-clog-hiscore-disk");
			t.setDaemon(true);
			return t;
		});
	}

	private static class CachedEntry
	{
		final HiscoreResult result;
		final long timestamp;

		CachedEntry(HiscoreResult result, long timestamp)
		{
			this.result = result;
			this.timestamp = timestamp;
		}

		boolean isStale()
		{
			return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
		}
	}

	/** JSON shape written to disk. Only the fields needed for clan aggregate. */
	private static class DiskRecord
	{
		String rsn;
		long timestamp;
		String accountType;
		Map<String, Integer> bossKills;
		Map<String, Integer> bossRanks;
		Map<String, Integer> activityScores;
		Map<String, Integer> activityRanks;
		Map<String, Integer> skillLevels;
		int totalLevel;
		long totalXp;
		int combatLevel;
		int overallRank;
	}
}
