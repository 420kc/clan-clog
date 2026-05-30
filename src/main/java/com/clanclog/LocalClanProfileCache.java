package com.clanclog;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	private final File cacheDir;
	private volatile ExecutorService diskWriter = newDiskWriter();

	@Inject
	public LocalClanProfileCache(Gson gson)
	{
		this(gson, CACHE_DIR);
	}

	LocalClanProfileCache(Gson gson, File cacheDir)
	{
		this.gson = gson;
		this.cacheDir = cacheDir;
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
		profile.roster = encodeRoster(roster);
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
		return readProfile(new File(cacheDir, slug + ".json"), slug);
	}

	@Nullable
	public StoredProfile latest()
	{
		if (!cacheDir.isDirectory())
		{
			return null;
		}
		File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(".json"));
		if (files == null || files.length == 0)
		{
			return null;
		}
		java.util.Arrays.sort(files, (left, right) ->
			Long.compare(right.lastModified(), left.lastModified()));
		for (File file : files)
		{
			String name = file.getName();
			String slug = name.substring(0, name.length() - ".json".length());
			StoredProfile profile = readProfile(file, slug);
			if (profile != null)
			{
				return profile;
			}
		}
		return null;
	}

	@Nullable
	private StoredProfile readProfile(File file, String slug)
	{
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
			if (!matchesSlug(slug, profile.slug)
				|| !matchesSlug(slug, profile.result.getSlug()))
			{
				log.debug("Ignored clan profile cache with mismatched slug '{}'", slug);
				return null;
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
		catch (Exception | AssertionError e)
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
		File tmp = null;
		try
		{
			if (!cacheDir.exists())
			{
				cacheDir.mkdirs();
			}
			File file = new File(cacheDir, slug + ".json");
			tmp = File.createTempFile(slug + "-", ".tmp", cacheDir);
			try (BufferedWriter writer = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8))
			{
				gson.toJson(profile, writer);
			}
			replaceCacheFile(tmp, file);
			tmp = null;
			log.debug("Saved clan profile cache: {}", file.getName());
		}
		catch (IOException | AssertionError e)
		{
			deleteQuietly(tmp);
			log.debug("Failed to save clan profile cache for '{}': {}", slug, e.getMessage());
		}
	}

	private static void replaceCacheFile(File source, File target) throws IOException
	{
		try
		{
			Files.move(source.toPath(), target.toPath(),
				StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException ignored)
		{
			Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void deleteQuietly(@Nullable File file)
	{
		if (file != null && file.exists() && !file.delete())
		{
			log.debug("Failed to delete clan profile cache temp file: {}", file.getName());
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

	private static boolean matchesSlug(String expectedSlug, String value)
	{
		return value == null || value.isBlank() || slugify(value).equals(expectedSlug);
	}

	private static List<MemberRecord> encodeRoster(List<ClanMember> roster)
	{
		if (roster == null || roster.isEmpty())
		{
			return new ArrayList<>();
		}
		List<MemberRecord> out = new ArrayList<>(roster.size());
		for (ClanMember member : roster)
		{
			MemberRecord record = MemberRecord.from(member);
			if (record != null)
			{
				out.add(record);
			}
		}
		return out;
	}

	public static class StoredProfile
	{
		private String clanName;
		private String slug;
		private String savedAt;
		private List<MemberRecord> roster;
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
			if (roster == null || roster.isEmpty())
			{
				return new ArrayList<>();
			}
			List<ClanMember> out = new ArrayList<>(roster.size());
			for (MemberRecord record : roster)
			{
				ClanMember member = record != null ? record.toClanMember() : null;
				if (member != null)
				{
					out.add(member);
				}
			}
			return out;
		}

		public ClanClogResult getResult()
		{
			return result;
		}
	}

	private static class MemberRecord
	{
		String rsn;
		String displayName;
		String role;
		String rankName;
		String accountType;
		String build;
		long totalXp;
		String lastUpdatedAt;
		String joinDate;
		HiscoreRecord hiscore;
		ClogRecord clog;

		static MemberRecord from(ClanMember member)
		{
			if (member == null || member.getRsn() == null || member.getRsn().isBlank())
			{
				return null;
			}
			MemberRecord record = new MemberRecord();
			record.rsn = member.getRsn();
			record.displayName = member.getDisplayName();
			record.role = member.getRole();
			record.rankName = member.getRankName();
			record.accountType = member.getAccountType() != null
				? member.getAccountType().name() : null;
			record.build = member.getBuild();
			record.totalXp = member.getTotalXp();
			record.lastUpdatedAt = member.getLastUpdatedAt();
			record.joinDate = member.getJoinDate() != null
				? member.getJoinDate().toString() : null;
			record.hiscore = HiscoreRecord.from(member.getHiscore());
			record.clog = ClogRecord.from(member.getClog());
			return record;
		}

		ClanMember toClanMember()
		{
			if (rsn == null || rsn.isBlank())
			{
				return null;
			}
			ClanMember member = new ClanMember(
				rsn,
				displayName != null ? displayName : rsn,
				role,
				rankName,
				parseAccountType(accountType),
				build,
				totalXp,
				lastUpdatedAt,
				parseDate(joinDate));
			if (hiscore != null)
			{
				member.setHiscore(hiscore.toHiscoreResult());
			}
			if (clog != null)
			{
				member.setClog(clog.toClogResult(rsn));
			}
			return member;
		}
	}

	private static class HiscoreRecord
	{
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

		static HiscoreRecord from(HiscoreResult result)
		{
			if (result == null)
			{
				return null;
			}
			HiscoreRecord record = new HiscoreRecord();
			record.accountType = result.getAccountType() != null
				? result.getAccountType().name() : null;
			record.bossKills = result.getBossKills();
			record.bossRanks = result.getBossRanks();
			record.activityScores = result.getActivityScores();
			record.activityRanks = result.getActivityRanks();
			record.skillLevels = result.getSkillLevels();
			record.totalLevel = result.getTotalLevel();
			record.totalXp = result.getTotalXp();
			record.combatLevel = result.getCombatLevel();
			record.overallRank = result.getOverallRank();
			return record;
		}

		HiscoreResult toHiscoreResult()
		{
			return new HiscoreResult(
				parseAccountType(accountType),
				bossKills,
				bossRanks,
				activityScores,
				activityRanks,
				skillLevels,
				totalLevel,
				totalXp,
				combatLevel,
				overallRank);
		}
	}

	private static class ClogRecord
	{
		String playerName;
		String lastChanged;
		String accountType;
		int uniqueObtained = -1;
		int uniqueTotal = -1;

		static ClogRecord from(ClogResult result)
		{
			if (result == null)
			{
				return null;
			}
			ClogRecord record = new ClogRecord();
			record.playerName = result.getPlayerName();
			record.lastChanged = result.getLastChanged();
			record.accountType = result.getTempleAccountType() != null
				? result.getTempleAccountType().name() : null;
			record.uniqueObtained = result.getUniqueObtained() >= 0
				? result.getUniqueObtained() : obtainedCount(result);
			record.uniqueTotal = result.getUniqueTotal();
			return record;
		}

		ClogResult toClogResult(String fallbackName)
		{
			ClogResult result = new ClogResult(
				playerName != null && !playerName.isBlank() ? playerName : fallbackName,
				Collections.emptyMap(),
				Collections.emptyMap(),
				null,
				lastChanged,
				parseAccountType(accountType));
			result.setUniqueObtained(uniqueObtained);
			result.setUniqueTotal(uniqueTotal);
			return result;
		}
	}

	private static AccountType parseAccountType(String value)
	{
		if (value == null || value.isBlank())
		{
			return AccountType.REGULAR;
		}
		try
		{
			return AccountType.valueOf(value);
		}
		catch (IllegalArgumentException ignored)
		{
			return AccountType.REGULAR;
		}
	}

	private static LocalDate parseDate(String value)
	{
		if (value == null || value.isBlank())
		{
			return null;
		}
		try
		{
			return LocalDate.parse(value);
		}
		catch (DateTimeParseException ignored)
		{
			return null;
		}
	}

	private static int obtainedCount(ClogResult result)
	{
		if (result.getObtainedItems() == null)
		{
			return -1;
		}
		Set<Integer> ids = new HashSet<>();
		for (List<ClogResult.ClogItem> items : result.getObtainedItems().values())
		{
			for (ClogResult.ClogItem item : items)
			{
				ids.add(item.getId());
			}
		}
		return ids.size();
	}
}
