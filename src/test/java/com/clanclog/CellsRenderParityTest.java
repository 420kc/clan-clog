package com.clanclog;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import org.junit.Test;

public class CellsRenderParityTest
{
	@Test
	public void clueTierTextUsesActivityScoreAndEmptyColorForZeroClogCatalog() throws Exception
	{
		Cells cells = new Cells(null, null, null, new ClanTooltipDataBuilder());
		JLabel beginner = new JLabel();
		clueLabels(cells).put("Clue Scrolls (beginner)", beginner);

		ClanClogResult result = ClanClogResult.forRoster("clan", "Clan",
			1, Collections.emptyMap());
		result.setActivityTotals(Map.of("Clue Scrolls (beginner)", 42L));
		result.setClog(new ClanClogResult.ClogUnion(
			Collections.emptyMap(),
			0,
			0,
			Collections.emptyMap(),
			Map.of("beginner_treasure_trails", List.of(1, 2))));

		cells.renderClanResult(result);

		assertEquals(ClogHelper.pad("42"), beginner.getText());
		assertEquals(ClogHelper.COLOR_EMPTY, beginner.getForeground());
	}

	@Test
	public void totalKillsSummaryStaysKOneGreenWhenClanHasBossKc() throws Exception
	{
		Cells cells = new Cells(null, null, null, new ClanTooltipDataBuilder());
		JLabel totalKills = new JLabel();
		setField(cells, "totalKillsCell", totalKills);

		ClanClogResult result = ClanClogResult.forRoster("clan", "Clan",
			1, Map.of("Nex", new ClanClogResult.BossAggregate(
				42L, Collections.emptyList(), 1)));

		cells.renderClanResult(result);

		assertEquals(ClogHelper.COLOR_COMPLETED, totalKills.getForeground());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, JLabel> clueLabels(Cells cells) throws Exception
	{
		Field field = Cells.class.getDeclaredField("clueTierLabels");
		field.setAccessible(true);
		return (Map<String, JLabel>) field.get(cells);
	}

	private static void setField(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
