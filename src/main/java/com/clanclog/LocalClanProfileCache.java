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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Disk-backed cache of the last rendered clan profile. Per-member caches let a
 * fresh roster rebuild quickly, but this gives the panel something useful to
 * show before RuneLite exposes the clan sidepanel settings.
 */
@Slf4j
@Singleton
public class LocalClanProfileCache
{
	private static final File CACHE_DIR = new File(RuneLite.RUNELITE_DIR, "clan-clog/clans");

	private final Gson gson;
	private volatile ExecutorService diskWriter = newDiskWriter();

	@Inject
	public LocalClanProfileCache(Gson gson)
	{
		this.gson = gson;
	}

	public void put(String clanName, String slug, List<ClanMember> roster,
		ClanClogResult result)
	{
		if (clanName == null || clanName.isBlank()
			|| slug == null || slug.isBlank()
			|| result == null)
		{
			return;
		}

		StoredProfile profile = new StoredProfile();
		profile.clanName = clanName;
		profile.slug = slug;
		profile.savedAt = Instant.now().toString();
		profile.roster = roster != null ? new ArrayList<>(roster) : new ArrayList<>();
		profile.result = result;

		try
		{
			diskWriter.execute(() -> saveToDisk(slug, profile));
		}
		catch (RejectedExecutionException ignored)
		{
			log.debug("Clan profile cache write rejected for {}", slug);
		}
	}

	@Nullable
	public StoredProfile get(String clanNameOrSlug)
	{
		String slug = slugify(clanNameOrSlug);
		if (slug.isEmpty())
		{
			return null;
		}
		File file = new File(CACHE_DIR, slug + ".json");
		if (!file.exists())
		{
			return null;
		}
		try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			StoredProfile profile = gson.fromJson(reader, StoredProfile.class);
			if (profile == null || profile.result == null)
			{
				return null;
			}
			if (profile.slug == null || profile.slug.isBlank())
			{
				profile.slug = slug;
			}
			if (profile.clanName == null || profile.clanName.isBlank())
			{
				profile.clanName = profile.slug;
			}
			if (profile.roster == null)
			{
				profile.roster = new ArrayList<>();
			}
			return profile;
		}
		catch (Exception e)
		{
			log.debug("Failed to load clan profile cache for '{}': {}", slug, e.getMessage());
			return null;
		}
	}

	public void shutdown()
	{
		ExecutorService old = diskWriter;
		diskWriter = newDiskWriter();
		old.shutdown();
	}

	private void saveToDisk(String slug, StoredProfile profile)
	{
		try
		{
			if (!CACHE_DIR.exists())
			{
				CACHE_DIR.mkdirs();
			}
			File file = new File(CACHE_DIR, slug + ".json");
			try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))
			{
				gson.toJson(profile, writer);
			}
			log.debug("Saved clan profile cache: {}", file.getName());
		}
		catch (IOException e)
		{
			log.debug("Failed to save clan profile cache for '{}': {}", slug, e.getMessage());
		}
	}

	private static ExecutorService newDiskWriter()
	{
		return Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "clan-clog-profile-disk");
			t.setDaemon(true);
			return t;
		});
	}

	private static String slugify(String value)
	{
		if (value == null)
		{
			return "";
		}
		return value.toLowerCase()
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("^-+|-+$", "");
	}

	public static class StoredProfile
	{
		private String clanName;
		private String slug;
		private String savedAt;
		private List<ClanMember> roster;
		private ClanClogResult result;

		public String getClanName()
		{
			return clanName;
		}

		public String getSlug()
		{
			return slug;
		}

		public String getSavedAt()
		{
			return savedAt;
		}

		public List<ClanMember> getRoster()
		{
			return roster != null ? roster : new ArrayList<>();
		}

		public ClanClogResult getResult()
		{
			return result;
		}
	}
}
