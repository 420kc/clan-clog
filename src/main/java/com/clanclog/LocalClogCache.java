package com.clanclog;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Multi-account, disk-backed collection log cache.
 *
 * <p>Stores per-player clog data in {@code ~/.runelite/clan-clog/} as JSON files.
 * Populated via bulk capture when the player opens their collection log in-game.
 * Persists across client restarts , any account ever captured is available permanently.
 *
 * <p>Disk writes are dispatched to a single background thread to avoid blocking the
 * game client thread.
 *
 * <p>Note: the in-memory player map is not evicted. In practice the number of distinct
 * looked-up players per session is small, so unbounded growth is not a concern.
 */
@Slf4j
@Singleton
public class LocalClogCache
{
	private static final File CACHE_DIR = new File(RuneLite.RUNELITE_DIR, "clan-clog");

	private final Map<String, PlayerClogData> players = new ConcurrentHashMap<>();
	private final Gson gson;
	private volatile String activePlayer;

	/**
	 * Disk I/O , single-threaded executor + per-player coalesce window.
	 * Bursts of category navigation collapse to one write per player.
	 * Volatile so shutdown() can swap the reference visibly to concurrent submitters.
	 */
	private static final long DEBOUNCE_MS = 500;
	private volatile ScheduledExecutorService diskWriter = newDiskWriter();
	private final Map<String, Runnable> pendingByPlayer = new ConcurrentHashMap<>();
	private final Map<String, ScheduledFuture<?>> pendingFlushByPlayer = new ConcurrentHashMap<>();

	private static ScheduledExecutorService newDiskWriter()
	{
		return Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "clan-clog-disk");
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * Submit a disk write for a player, coalescing bursts within DEBOUNCE_MS into a single write.
	 * The latest snapshot wins. Rejections during executor swap are swallowed; the next sync re-saves.
	 */
	private void submitDiskWrite(String playerName, Runnable task)
	{
		pendingByPlayer.put(playerName, task);
		ScheduledFuture<?> previous = pendingFlushByPlayer.remove(playerName);
		if (previous != null)
		{
			previous.cancel(false);
		}

		try
		{
			ScheduledFuture<?> next = diskWriter.schedule(() -> flushPendingWrite(playerName),
				DEBOUNCE_MS, TimeUnit.MILLISECONDS);
			pendingFlushByPlayer.put(playerName, next);
		}
		catch (RejectedExecutionException ignored)
		{
			pendingByPlayer.remove(playerName);
			pendingFlushByPlayer.remove(playerName);
			log.debug("Disk write rejected (executor shutting down)");
		}
	}

	private void flushPendingWrite(String playerName)
	{
		pendingFlushByPlayer.remove(playerName);
		Runnable latest = pendingByPlayer.remove(playerName);
		if (latest != null)
		{
			latest.run();
		}
	}

	@Inject
	public LocalClogCache(Gson gson)
	{
		this.gson = gson;
	}

	/**
	 * Swap in a fresh executor before shutting down the old one , keeps a live
	 * executor available for the next startUp(). Without this, the @Singleton
	 * survives plugin reload but its executor would be permanently dead.
	 */
	public void shutdown()
	{
		ScheduledExecutorService old = diskWriter;
		ScheduledExecutorService fresh = newDiskWriter();
		diskWriter = fresh;

		for (ScheduledFuture<?> pending : new ArrayList<>(pendingFlushByPlayer.values()))
		{
			pending.cancel(false);
		}
		pendingFlushByPlayer.clear();

		// Drain pending debounced writes to fresh; remove-by-key prevents double-run with an in-flight flush.
		for (String key : new ArrayList<>(pendingByPlayer.keySet()))
		{
			Runnable t = pendingByPlayer.remove(key);
			if (t != null)
			{
				try
				{
					fresh.execute(t);
				}
				catch (RejectedExecutionException ignored)
				{
					t.run();
				}
			}
		}

		// Don't await termination. Would block RuneLite's plugin-shutdown thread.
		// Pending tasks were drained to the fresh executor above; in-flight tasks
		// on `old` complete on their own background threads.
		old.shutdown();
	}

	public void setActivePlayer(String name)
	{
		if (name == null)
		{
			activePlayer = null;
			return;
		}

		activePlayer = name;
		String key = name.toLowerCase();

		if (!players.containsKey(key))
		{
			PlayerClogData loaded = loadFromDisk(name);
			if (loaded != null)
			{
				players.put(key, loaded);
				log.debug("Loaded persistent clog cache for '{}' ({} categories)",
					name, loaded.categories.size());
			}
		}

		log.debug("Active clog player set to: {}", name);
	}

	public boolean isActivePlayer(String name)
	{
		return activePlayer != null && name != null
			&& activePlayer.equalsIgnoreCase(name);
	}

	public void cacheResult(ClogResult result)
	{
		if (result == null || result.getPlayerName() == null)
		{
			return;
		}

		String name = result.getPlayerName();
		String key = name.toLowerCase();

		// Preserve varp-sourced totals if they're higher than what Temple reports
		PlayerClogData existing = players.get(key);

		PlayerClogData data = new PlayerClogData();
		data.playerName = name;
		data.lastUpdated = Instant.now().toString();
		data.uniqueObtained = result.getUniqueObtained();
		data.uniqueTotal = result.getUniqueTotal();
		if (existing != null)
		{
			if (existing.uniqueObtained > data.uniqueObtained)
			{
				data.uniqueObtained = existing.uniqueObtained;
			}
			if (existing.uniqueTotal > data.uniqueTotal)
			{
				data.uniqueTotal = existing.uniqueTotal;
			}
		}
		data.lastChanged = result.getLastChanged();
		data.obtained = new ConcurrentHashMap<>();
		data.categories = new ConcurrentHashMap<>();

		if (result.getCategoryItems() != null)
		{
			for (Map.Entry<String, List<Integer>> entry
				: result.getCategoryItems().entrySet())
			{
				data.categories.put(entry.getKey(), new ArrayList<>(entry.getValue()));
			}
		}
		if (data.categories.isEmpty() && hasUsableData(existing))
		{
			for (Map.Entry<String, List<Integer>> entry : existing.categories.entrySet())
			{
				data.categories.put(entry.getKey(), new ArrayList<>(entry.getValue()));
			}
		}

		for (Map.Entry<String, List<ClogResult.ClogItem>> entry
			: result.getObtainedItems().entrySet())
		{
			String cat = entry.getKey();
			data.obtained.put(cat, new ArrayList<>(entry.getValue()));
		}

		if (data.categories.isEmpty())
		{
			log.debug("Skipping clog cache for '{}' with no category catalog", name);
			return;
		}

		players.put(key, data);
		final PlayerClogData snapshot = shallowCopy(data);
		submitDiskWrite(name, () -> saveToDisk(name, snapshot));
		log.debug("Cached clog data for '{}' ({} categories, {} obtained categories)",
			name, data.categories.size(), data.obtained.size());
	}

	public void mergeCategory(String playerName, String categoryKey,
		List<Integer> allItems, List<ClogResult.ClogItem> obtained)
	{
		if (playerName == null)
		{
			return;
		}

		String key = playerName.toLowerCase();
		PlayerClogData data = players.get(key);
		if (data == null)
		{
			return;
		}

		data.categories.put(categoryKey, new ArrayList<>(allItems));
		data.obtained.put(categoryKey, new ArrayList<>(obtained));

		final PlayerClogData snapshot = shallowCopy(data);
		submitDiskWrite(playerName, () -> saveToDisk(playerName, snapshot));
		log.debug("Merged category '{}' for '{}': {}/{} obtained",
			categoryKey, playerName, obtained.size(), allItems.size());
	}

	public void updateTotals(String playerName, int obtained, int total)
	{
		if (playerName == null)
		{
			return;
		}

		String key = playerName.toLowerCase();
		PlayerClogData data = players.get(key);
		if (data == null)
		{
			return;
		}

		boolean changed = false;
		if (obtained > 0 && obtained != data.uniqueObtained)
		{
			data.uniqueObtained = obtained;
			changed = true;
		}
		if (total > 0 && total != data.uniqueTotal)
		{
			data.uniqueTotal = total;
			changed = true;
		}

		if (changed)
		{
			final PlayerClogData snapshot = shallowCopy(data);
			submitDiskWrite(playerName, () -> saveToDisk(playerName, snapshot));
			log.debug("Updated clog totals for '{}': {}/{}", playerName, obtained, total);
		}
	}

	public boolean hasDataFor(String playerName)
	{
		if (playerName == null)
		{
			return false;
		}

		String key = playerName.toLowerCase();
		PlayerClogData cached = players.get(key);
		if (hasUsableData(cached))
		{
			return true;
		}
		if (cached != null)
		{
			players.remove(key);
		}

		PlayerClogData loaded = loadFromDisk(playerName);
		if (loaded != null)
		{
			players.put(key, loaded);
			log.debug("Lazy-loaded persistent clog cache for '{}' ({} categories)",
				playerName, loaded.categories.size());
			return true;
		}

		return false;
	}

	public ClogResult toClogResult(String playerName, Map<Integer, String> itemNames)
	{
		if (playerName == null)
		{
			return null;
		}

		PlayerClogData data = players.get(playerName.toLowerCase());
		if (data == null)
		{
			return null;
		}

		// Defensive copies , callers may mutate their maps
		Map<String, List<ClogResult.ClogItem>> obtainedCopy = new HashMap<>();
		for (Map.Entry<String, List<ClogResult.ClogItem>> entry : data.obtained.entrySet())
		{
			obtainedCopy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}

		Map<String, List<Integer>> categoriesCopy = new HashMap<>();
		for (Map.Entry<String, List<Integer>> entry : data.categories.entrySet())
		{
			categoriesCopy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}

		ClogResult result = new ClogResult(
			data.playerName,
			obtainedCopy,
			categoriesCopy,
			itemNames != null ? itemNames : new HashMap<>(),
			data.lastChanged,
			null  // no Temple account type for local data
		);
		if (data.uniqueObtained > 0)
		{
			result.setUniqueObtained(data.uniqueObtained);
		}
		if (data.uniqueTotal > 0)
		{
			result.setUniqueTotal(data.uniqueTotal);
		}
		return result;
	}

	// --- Disk I/O (runs on diskWriter thread only) ---

	private void saveToDisk(String playerName, PlayerClogData data)
	{
		try
		{
			if (!CACHE_DIR.exists())
			{
				CACHE_DIR.mkdirs();
			}
			File file = getCacheFile(playerName);
			try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))
			{
				gson.toJson(data, writer);
			}
			log.debug("Saved clog cache to disk: {}", file.getName());
		}
		catch (IOException e)
		{
			log.warn("Failed to save clog cache for '{}': {}", playerName, e.getMessage());
		}
	}

	private PlayerClogData loadFromDisk(String playerName)
	{
		File file = getCacheFile(playerName);
		if (!file.exists())
		{
			return null;
		}
		try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			PlayerClogData data = gson.fromJson(reader, PlayerClogData.class);
			if (data != null && data.categories != null && !data.categories.isEmpty())
			{
				// Gson deserializes to plain maps; wrap in ConcurrentHashMap
				// so mergeCategory() and EDT reads can't collide.
				data.categories = new ConcurrentHashMap<>(data.categories);
				data.obtained = data.obtained != null
					? new ConcurrentHashMap<>(data.obtained)
					: new ConcurrentHashMap<>();
				return data;
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to load clog cache for '{}': {}", playerName, e.getMessage());
		}
		return null;
	}

	private File getCacheFile(String playerName)
	{
		String sanitized = playerName.toLowerCase()
			.replace(' ', '_')
			.replaceAll("[^a-z0-9_-]", "");
		return new File(CACHE_DIR, sanitized + ".json");
	}

	private static boolean hasUsableData(PlayerClogData data)
	{
		return data != null
			&& data.categories != null
			&& !data.categories.isEmpty();
	}

	/** Shallow copy sufficient for async disk write , lists are already copied in callers. */
	private static PlayerClogData shallowCopy(PlayerClogData src)
	{
		PlayerClogData copy = new PlayerClogData();
		copy.playerName = src.playerName;
		copy.lastUpdated = src.lastUpdated;
		copy.lastChanged = src.lastChanged;
		copy.uniqueObtained = src.uniqueObtained;
		copy.uniqueTotal = src.uniqueTotal;
		copy.categories = new HashMap<>(src.categories);
		copy.obtained = new HashMap<>(src.obtained);
		return copy;
	}

	private static class PlayerClogData
	{
		String playerName;
		String lastUpdated;
		String lastChanged;
		int uniqueObtained = -1;
		int uniqueTotal = -1;
		Map<String, List<Integer>> categories;
		Map<String, List<ClogResult.ClogItem>> obtained;
	}
}
