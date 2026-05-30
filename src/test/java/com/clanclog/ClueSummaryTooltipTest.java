package com.clanclog;

import java.lang.reflect.Field;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ClueSummaryTooltipTest
{
	@Test
	public void setClanDataUsesAggregateClueActivitiesAndMimicKc() throws Exception
	{
		ClueSummaryTooltip tooltip = new ClueSummaryTooltip();
		tooltip.setClanData(Map.of(
			"Clue Scrolls (all)", 120L,
			"Clue Scrolls (beginner)", 4L,
			"Clue Scrolls (easy)", 8L,
			"Clue Scrolls (medium)", 16L,
			"Clue Scrolls (hard)", 32L,
			"Clue Scrolls (elite)", 64L,
			"Clue Scrolls (master)", 96L), 7L);

		assertArrayEquals(new int[]{120, 4, 8, 16, 32, 64, 96},
			intArrayField(tooltip, "scores"));
		assertArrayEquals(new int[]{-1, -1, -1, -1, -1, -1, -1},
			intArrayField(tooltip, "ranks"));
		assertEquals(7, intField(tooltip, "mimicKc"));
		assertEquals(-1, intField(tooltip, "mimicRank"));
		assertNull(rankText(tooltip));
	}

	private static int[] intArrayField(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return (int[]) field.get(target);
	}

	private static int intField(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(target);
	}

	private static String rankText(ClueSummaryTooltip tooltip) throws Exception
	{
		Field field = TitleTooltip.class.getDeclaredField("rankText");
		field.setAccessible(true);
		return (String) field.get(tooltip);
	}
}
