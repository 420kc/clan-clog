package com.clanclog;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class LocalClanProfileCacheTest
{
	@Test
	public void latestReturnsNewestReadableProfile() throws Exception
	{
		File dir = Files.createTempDirectory("clan-profile-cache-test").toFile();
		File older = writeProfile(dir, "old-clan", "Old Clan");
		File newer = writeProfile(dir, "new-clan", "New Clan");
		Files.setLastModifiedTime(older.toPath(), FileTime.fromMillis(1_000L));
		Files.setLastModifiedTime(newer.toPath(), FileTime.fromMillis(2_000L));

		LocalClanProfileCache cache = new LocalClanProfileCache(new Gson(), dir);
		LocalClanProfileCache.StoredProfile latest = cache.latest();

		assertNotNull(latest);
		assertEquals("New Clan", latest.getClanName());
		assertEquals("new-clan", latest.getSlug());
	}

	@Test
	public void latestSkipsUnreadableProfile() throws Exception
	{
		File dir = Files.createTempDirectory("clan-profile-cache-test").toFile();
		File older = writeProfile(dir, "good-clan", "Good Clan");
		File newer = new File(dir, "broken-clan.json");
		Files.writeString(newer.toPath(), "{", StandardCharsets.UTF_8);
		Files.setLastModifiedTime(older.toPath(), FileTime.fromMillis(1_000L));
		Files.setLastModifiedTime(newer.toPath(), FileTime.fromMillis(2_000L));

		LocalClanProfileCache cache = new LocalClanProfileCache(new Gson(), dir);
		LocalClanProfileCache.StoredProfile latest = cache.latest();

		assertNotNull(latest);
		assertEquals("Good Clan", latest.getClanName());
		assertEquals("good-clan", latest.getSlug());
	}

	@Test
	public void latestSkipsSlugMismatchedProfile() throws Exception
	{
		File dir = Files.createTempDirectory("clan-profile-cache-test").toFile();
		File older = writeProfile(dir, "good-clan", "Good Clan");
		File newer = writeProfile(dir, "wrong-clan", "Wrong Clan", "other-clan");
		Files.setLastModifiedTime(older.toPath(), FileTime.fromMillis(1_000L));
		Files.setLastModifiedTime(newer.toPath(), FileTime.fromMillis(2_000L));

		LocalClanProfileCache cache = new LocalClanProfileCache(new Gson(), dir);
		LocalClanProfileCache.StoredProfile latest = cache.latest();

		assertNotNull(latest);
		assertEquals("Good Clan", latest.getClanName());
		assertEquals("good-clan", latest.getSlug());
	}

	@Test
	public void latestSkipsEmptyProfileShell() throws Exception
	{
		File dir = Files.createTempDirectory("clan-profile-cache-test").toFile();
		File older = writeProfile(dir, "good-clan", "Good Clan");
		File newer = writeEmptyProfile(dir, "empty-clan", "Empty Clan");
		Files.setLastModifiedTime(older.toPath(), FileTime.fromMillis(1_000L));
		Files.setLastModifiedTime(newer.toPath(), FileTime.fromMillis(2_000L));

		LocalClanProfileCache cache = new LocalClanProfileCache(new Gson(), dir);
		LocalClanProfileCache.StoredProfile latest = cache.latest();

		assertNotNull(latest);
		assertEquals("Good Clan", latest.getClanName());
		assertEquals("good-clan", latest.getSlug());
	}

	@Test
	public void getSkipsEmptyProfileShell() throws Exception
	{
		File dir = Files.createTempDirectory("clan-profile-cache-test").toFile();
		writeEmptyProfile(dir, "empty-clan", "Empty Clan");

		LocalClanProfileCache cache = new LocalClanProfileCache(new Gson(), dir);

		assertEquals(null, cache.get("empty clan"));
	}

	@Test
	public void getSkipsSlugMismatchedProfile() throws Exception
	{
		File dir = Files.createTempDirectory("clan-profile-cache-test").toFile();
		writeProfile(dir, "wrong-clan", "Wrong Clan", "other-clan");

		LocalClanProfileCache cache = new LocalClanProfileCache(new Gson(), dir);

		assertEquals(null, cache.get("wrong clan"));
	}

	private static File writeProfile(File dir, String slug, String clanName) throws Exception
	{
		return writeProfile(dir, slug, clanName, slug);
	}

	private static File writeProfile(File dir, String slug, String clanName,
		String resultSlug) throws Exception
	{
		File file = new File(dir, slug + ".json");
		String json = "{"
			+ "\"clanName\":\"" + clanName + "\","
			+ "\"slug\":\"" + slug + "\","
			+ "\"savedAt\":\"2026-05-30T00:00:00Z\","
			+ "\"roster\":[],"
			+ "\"result\":{"
			+ "\"slug\":\"" + resultSlug + "\","
			+ "\"display_name\":\"" + clanName + "\","
			+ "\"member_count\":0,"
			+ "\"bosses\":{\"Zulrah\":{\"clan_total_kc\":1}}"
			+ "}"
			+ "}";
		Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
		return file;
	}

	private static File writeEmptyProfile(File dir, String slug, String clanName) throws Exception
	{
		File file = new File(dir, slug + ".json");
		String json = "{"
			+ "\"clanName\":\"" + clanName + "\","
			+ "\"slug\":\"" + slug + "\","
			+ "\"savedAt\":\"2026-05-30T00:00:00Z\","
			+ "\"roster\":[],"
			+ "\"result\":{"
			+ "\"slug\":\"" + slug + "\","
			+ "\"display_name\":\"" + clanName + "\","
			+ "\"member_count\":0,"
			+ "\"bosses\":{}"
			+ "}"
			+ "}";
		Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
		return file;
	}
}
