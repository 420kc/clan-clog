package com.clanclog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Builds a {@link ClanClogResult} from in-game roster + per-member hiscore
 * data. Boss aggregates (clan_total_kc, top_3, member_coverage) come from
 * live Jagex hiscore data. Clog items are preserved from the backend/fixture
 * result when available, since hiscores alone don't carry collection log data.
 *
 * <p>Called after {@link ClanHiscoreBatch} completes so the cells surface
 * renders real clan aggregate kc instead of fixture placeholder data.
 */
final class RosterClogBuilder
{
	private RosterClogBuilder()
	{
	}

	/**
	 * Build or merge a ClanClogResult from roster hiscore data.
	 *
	 * <p>When {@code existing} is non-null (backend or fixture data arrived
	 * before the batch finished), the boss map is replaced with live hiscore
	 * aggregates while clog items, item_meta, and recently_acquired are
	 * preserved from the existing result.
	 *
	 * <p>When {@code existing} is null, a new ClanClogResult is built with
	 * only boss data (clog stays null until a clog provider is wired).
	 */
	static ClanClogResult fromHiscores(String clanName, String slug,
		List<ClanMember> roster, @Nullable ClanClogResult existing)
	{
		Map<String, ClanClogResult.BossAggregate> bossMap = buildBossAggregates(roster);

		if (existing != null)
		{
			existing.setBosses(bossMap);
			return existing;
		}

		return ClanClogResult.forRoster(slug, clanName, roster.size(), bossMap);
	}

	private static Map<String, ClanClogResult.BossAggregate> buildBossAggregates(
		List<ClanMember> roster)
	{
		Map<String, ClanClogResult.BossAggregate> map = new LinkedHashMap<>();

		for (String boss : HiscoreService.bossNames())
		{
			long totalKc = 0;
			int coverage = 0;
			List<ClanClogResult.MemberKc> contributors = new ArrayList<>();

			for (ClanMember member : roster)
			{
				HiscoreResult hs = member.getHiscore();
				if (hs == null)
				{
					continue;
				}

				int kc = hs.getKc(boss);
				if (kc <= 0)
				{
					continue;
				}

				totalKc += kc;
				coverage++;
				contributors.add(new ClanClogResult.MemberKc(
					member.getDisplayName(), kc));
			}

			contributors.sort((a, b) -> Long.compare(b.getKc(), a.getKc()));
			List<ClanClogResult.MemberKc> top3 = contributors.size() > 3
				? new ArrayList<>(contributors.subList(0, 3))
				: contributors;

			map.put(boss, new ClanClogResult.BossAggregate(totalKc, top3, coverage));
		}

		return map;
	}
}
