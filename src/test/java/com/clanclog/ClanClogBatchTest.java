package com.clanclog;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class ClanClogBatchTest
{
	@Test
	public void partialCachedDataDoesNotShortCircuitProviderLookup() throws Exception
	{
		ClogResult cached = result("CBC",
			"fortis_colosseum", Arrays.asList(28947), Arrays.asList(28947));
		ClogResult provider = result("CBC",
			"third_age", Arrays.asList(10334, 10336, 10338), Arrays.asList(10334, 10338));
		FakeClogFetchService service = new FakeClogFetchService(cached, false, provider);
		ClanClogBatch batch = new ClanClogBatch(service);
		ClanMember member = member("CBC");

		try
		{
			batch.fetchAll(Collections.singletonList(member), 1, null).get(2, TimeUnit.SECONDS);
		}
		finally
		{
			batch.shutdown();
		}

		assertSame(provider, member.getClog());
		assertEquals(1, service.lookupCalls);
	}

	@Test
	public void richCachedDataShortCircuitsProviderLookup() throws Exception
	{
		ClogResult cached = result("CBC",
			"third_age", Arrays.asList(10334, 10336, 10338), Arrays.asList(10334, 10338));
		ClogResult provider = result("CBC",
			"doom_of_mokhaiotl", Arrays.asList(31127, 31130), Arrays.asList(31127));
		FakeClogFetchService service = new FakeClogFetchService(cached, true, provider);
		ClanClogBatch batch = new ClanClogBatch(service);
		ClanMember member = member("CBC");

		try
		{
			batch.fetchAll(Collections.singletonList(member), 1, null).get(2, TimeUnit.SECONDS);
		}
		finally
		{
			batch.shutdown();
		}

		assertSame(cached, member.getClog());
		assertEquals(0, service.lookupCalls);
	}

	private static ClanMember member(String rsn)
	{
		return new ClanMember(rsn, rsn, "Member", "GUEST",
			AccountType.REGULAR, null, 0L, null, null);
	}

	private static ClogResult result(String playerName, String category,
		List<Integer> allIds, List<Integer> obtainedIds)
	{
		Map<String, List<Integer>> categories = new HashMap<>();
		categories.put(category, allIds);
		Map<String, List<ClogResult.ClogItem>> obtained = new HashMap<>();
		obtained.put(category, obtainedIds.stream()
			.map(id -> new ClogResult.ClogItem(id, 1, null))
			.collect(java.util.stream.Collectors.toList()));
		ClogResult result = new ClogResult(
			playerName,
			obtained,
			categories,
			new HashMap<>(),
			null,
			null);
		result.setUniqueObtained(obtainedIds.size());
		result.setUniqueTotal(allIds.size());
		return result;
	}

	private static class FakeClogFetchService extends ClogFetchService
	{
		private final ClogResult cached;
		private final boolean richCachedData;
		private final ClogResult provider;
		private int lookupCalls;

		FakeClogFetchService(ClogResult cached, boolean richCachedData,
			ClogResult provider)
		{
			super(null, null, null);
			this.cached = cached;
			this.richCachedData = richCachedData;
			this.provider = provider;
		}

		@Override
		public ClogResult getCached(String playerName)
		{
			return cached;
		}

		@Override
		public boolean hasRichCachedData(String playerName, int minCategories,
			int minObtained)
		{
			return richCachedData;
		}

		@Override
		public CompletableFuture<ClogResult> lookup(String playerName)
		{
			lookupCalls++;
			return CompletableFuture.completedFuture(provider);
		}
	}
}
