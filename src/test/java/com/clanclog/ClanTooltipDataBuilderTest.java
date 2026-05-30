package com.clanclog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ClanTooltipDataBuilderTest
{
	@Test
	public void buildsEmptyCategoryFromCatalog()
	{
		ClanClogResult result = ClanClogResult.forRoster("test-clan", "Test Clan",
			1, Collections.emptyMap());
		result.setClog(new ClanClogResult.ClogUnion(
			Collections.emptyMap(),
			0,
			Collections.emptyMap(),
			Map.of("zulrah", List.of(12921, 13200))));

		TooltipData data = new ClanTooltipDataBuilder()
			.buildForCategory("Zulrah", "zulrah", result);

		assertNotNull(data);
		assertEquals(0, data.obtainedCount);
		assertEquals(2, data.totalItems);
		assertEquals(List.of(12921, 13200), data.allItemIds);
		assertTrue(data.obtainedIds.isEmpty());
	}
}
