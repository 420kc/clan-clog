package com.clanclog;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PvpSummaryTooltipTest
{
	@Test
	public void setClanDataUsesAggregateActivitiesAndUnionCounts() throws Exception
	{
		ClanClogResult.ClogUnion clog = new ClanClogResult.ClogUnion(
			Map.of(
				"last_man_standing", List.of(1, 3),
				"soul_wars", List.of(4, 5)),
			4,
			0,
			Map.of(),
			Map.of(
				"last_man_standing", List.of(1, 2, 3),
				"soul_wars", List.of(4, 5, 6, 7)));

		PvpSummaryTooltip tooltip = new PvpSummaryTooltip();
		tooltip.setClanData(Map.of(
			"LMS - Rank", 1200L,
			"Soul Wars Zeal", 340L,
			"PvP Arena - Rank", 88L,
			"Bounty Hunter - Hunter", 42L,
			"Bounty Hunter - Rogue", 9L), clog);

		assertEquals(1200L, longField(tooltip, "lmsScore"));
		assertEquals(340L, longField(tooltip, "soulWarsScore"));
		assertEquals(88L, longField(tooltip, "pvpArenaScore"));
		assertEquals(42L, longField(tooltip, "bhHunterScore"));
		assertEquals(9L, longField(tooltip, "bhRogueScore"));
		assertEquals(2, intField(tooltip, "lmsObtained"));
		assertEquals(3, intField(tooltip, "lmsTotal"));
		assertEquals(2, intField(tooltip, "swObtained"));
		assertEquals(4, intField(tooltip, "swTotal"));
	}

	private static long longField(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.getLong(target);
	}

	private static int intField(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(target);
	}
}
