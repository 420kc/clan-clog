package com.clanclog;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PvmSummaryTooltipTest
{
	@Test
	public void setClanDataUsesAggregateBossAndRaidValues() throws Exception
	{
		ClanClogResult.ClogUnion clog = new ClanClogResult.ClogUnion(
			Map.of(
				"chambers_of_xeric", List.of(1, 2),
				"theatre_of_blood", List.of(3),
				"tombs_of_amascut", List.of(4, 5, 6)),
			6,
			0,
			Map.of(),
			Map.of(
				"chambers_of_xeric", List.of(1, 2, 7),
				"theatre_of_blood", List.of(3, 8),
				"tombs_of_amascut", List.of(4, 5, 6, 9)));
		Map<String, ClanClogResult.BossAggregate> bosses = Map.of(
			"Chambers of Xeric", new ClanClogResult.BossAggregate(100, List.of(), 4),
			"Chambers of Xeric: Challenge Mode", new ClanClogResult.BossAggregate(11, List.of(), 2),
			"Theatre of Blood", new ClanClogResult.BossAggregate(50, List.of(), 3),
			"Theatre of Blood: Hard Mode", new ClanClogResult.BossAggregate(7, List.of(), 1),
			"Tombs of Amascut", new ClanClogResult.BossAggregate(80, List.of(), 5),
			"Tombs of Amascut: Expert Mode", new ClanClogResult.BossAggregate(6, List.of(), 1));

		PvmSummaryTooltip tooltip = new PvmSummaryTooltip();
		tooltip.setClanData(254L, 13, 68, "Zulrah", 420L);
		tooltip.setClanRaids(bosses, clog);

		assertEquals(-1, intField(tooltip, "combatLevel"));
		assertEquals(254, intField(tooltip, "totalKills"));
		assertEquals(13, intField(tooltip, "bossesWithKc"));
		assertEquals(68, intField(tooltip, "totalBosses"));
		assertEquals("Zulrah", stringField(tooltip, "mostKilled"));
		assertEquals(420, intField(tooltip, "mostKilledKc"));
		assertEquals(111, intField(tooltip, "coxKc"));
		assertEquals(57, intField(tooltip, "tobKc"));
		assertEquals(86, intField(tooltip, "toaKc"));
		assertEquals(2, intField(tooltip, "coxObtained"));
		assertEquals(3, intField(tooltip, "coxTotal"));
		assertEquals(1, intField(tooltip, "tobObtained"));
		assertEquals(2, intField(tooltip, "tobTotal"));
		assertEquals(3, intField(tooltip, "toaObtained"));
		assertEquals(4, intField(tooltip, "toaTotal"));
	}

	@Test
	public void clanMegararesUseQuantityTotalsNotHolderCounts()
	{
		ClanClogResult.ClogUnion clog = new ClanClogResult.ClogUnion(
			Map.of("chambers_of_xeric", List.of(20997, 22486)),
			2,
			0,
			Map.of(
				"20997", new ClanClogResult.ItemMeta(14, 20),
				"22486", new ClanClogResult.ItemMeta(13)),
			Map.of("chambers_of_xeric", List.of(20997, 22486)));

		assertEquals(20, PvmSummaryTooltip.quantityTotal(clog, 20997));
		assertEquals(13, PvmSummaryTooltip.quantityTotal(clog, 22486));
		assertEquals(0, PvmSummaryTooltip.quantityTotal(clog, 27277));
	}

	private static int intField(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(target);
	}

	private static String stringField(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return (String) field.get(target);
	}
}
