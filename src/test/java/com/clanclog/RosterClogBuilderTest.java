package com.clanclog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RosterClogBuilderTest
{
	@Test
	public void newestClogLastChangedReturnsNewestMemberTimestamp()
	{
		ClanMember older = member("Older", "2026-05-21T12:00:00Z");
		ClanMember newer = member("Newer", "2026-05-22T12:00:00Z");
		ClanMember missing = new ClanMember("Missing", "Missing", null,
			null, AccountType.REGULAR, null, 0L, null, null);

		assertEquals("2026-05-22T12:00:00Z",
			RosterClogBuilder.newestClogLastChanged(
				Arrays.asList(older, newer, missing)));
	}

	@Test
	public void newestClogLastChangedReturnsNullWithoutMemberTimestamps()
	{
		ClanMember missing = new ClanMember("Missing", "Missing", null,
			null, AccountType.REGULAR, null, 0L, null, null);

		assertNull(RosterClogBuilder.newestClogLastChanged(
			Collections.singletonList(missing)));
	}

	@Test
	public void buildClogUnionKeepsGridUnionAndMemberUniqueTotalSeparate()
	{
		ClanMember alice = memberWithClog("Alice", 1419,
			Map.of("zulrah", List.of(item(1), item(2))));
		ClanMember bob = memberWithClog("Bob", 7,
			Map.of("zulrah", List.of(item(2), item(3))));

		ClanClogResult.ClogUnion union = RosterClogBuilder.buildClogUnion(
			Arrays.asList(alice, bob));

		assertEquals(3, union.getTotalObtained());
		assertEquals(1426, union.getTotalMemberUniqueObtained());
		assertEquals(1, union.getItemMeta().get("1").getHolderCount());
		assertEquals(2, union.getItemMeta().get("2").getHolderCount());
		assertEquals(1, union.getItemMeta().get("3").getHolderCount());
	}

	private static ClanMember member(String rsn, String lastChanged)
	{
		ClanMember member = new ClanMember(rsn, rsn, null, null,
			AccountType.REGULAR, null, 0L, null, null);
		member.setClog(new ClogResult(rsn, Collections.emptyMap(),
			Collections.emptyMap(), Collections.emptyMap(), lastChanged,
			AccountType.REGULAR));
		return member;
	}

	private static ClanMember memberWithClog(String rsn, int uniqueObtained,
		Map<String, List<ClogResult.ClogItem>> obtainedItems)
	{
		ClanMember member = new ClanMember(rsn, rsn, null, null,
			AccountType.REGULAR, null, 0L, null, null);
		Map<String, List<Integer>> categories = new HashMap<>();
		for (Map.Entry<String, List<ClogResult.ClogItem>> entry : obtainedItems.entrySet())
		{
			List<Integer> ids = new ArrayList<>();
			for (ClogResult.ClogItem item : entry.getValue())
			{
				ids.add(item.getId());
			}
			categories.put(entry.getKey(), ids);
		}

		ClogResult clog = new ClogResult(rsn, obtainedItems, categories,
			Collections.emptyMap(), null, AccountType.REGULAR);
		clog.setUniqueObtained(uniqueObtained);
		member.setClog(clog);
		return member;
	}

	private static ClogResult.ClogItem item(int id)
	{
		return new ClogResult.ClogItem(id, 1, null);
	}
}
