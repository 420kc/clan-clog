package com.clanclog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

	/**
	 * Build a {@link ClanClogResult.ClogUnion} from per-member clog data.
	 * Unions all members' obtained items per category, counts how many members
	 * hold each item (holder_count), and sums total unique obtained items
	 * across the clan.
	 *
	 * <p>Category keys are Temple-style (snake_case). The tooltip builder uses
	 * {@link ClogHelper#bossToCategory} to map boss names to category keys.
	 *
	 * @param roster  the roster with {@link ClanMember#getClog()} populated
	 * @return        a ClogUnion, or null if no member has clog data
	 */
	static ClanClogResult.ClogUnion buildClogUnion(List<ClanMember> roster)
	{
		// category -> item id -> set of RSNs who have it
		Map<String, Map<Integer, Set<String>>> categoryHolders = new LinkedHashMap<>();
		// category -> all item IDs from the catalog
		Map<String, Set<Integer>> categoryCatalogs = new LinkedHashMap<>();

		int membersWithClog = 0;

		for (ClanMember member : roster)
		{
			ClogResult clog = member.getClog();
			if (clog == null)
			{
				continue;
			}
			membersWithClog++;

			// Index all item IDs per category (catalog: all items in the category)
			for (Map.Entry<String, List<Integer>> entry : clog.getCategoryItems().entrySet())
			{
				categoryCatalogs
					.computeIfAbsent(entry.getKey(), k -> new HashSet<>())
					.addAll(entry.getValue());
			}

			// Index obtained items per category with holder tracking
			for (Map.Entry<String, List<ClogResult.ClogItem>> entry : clog.getObtainedItems().entrySet())
			{
				Map<Integer, Set<String>> holders = categoryHolders
					.computeIfAbsent(entry.getKey(), k -> new LinkedHashMap<>());
				for (ClogResult.ClogItem item : entry.getValue())
				{
					holders
						.computeIfAbsent(item.getId(), k -> new HashSet<>())
						.add(member.getRsn().toLowerCase());
				}
			}
		}

		if (membersWithClog == 0)
		{
			return null;
		}

		// Build items_by_category: for each category, list all obtained item IDs
		Map<String, List<Integer>> itemsByCategory = new LinkedHashMap<>();
		Set<Integer> allObtained = new HashSet<>();

		for (Map.Entry<String, Map<Integer, Set<String>>> entry : categoryHolders.entrySet())
		{
			List<Integer> obtainedIds = new ArrayList<>(entry.getValue().keySet());
			itemsByCategory.put(entry.getKey(), obtainedIds);
			allObtained.addAll(obtainedIds);
		}

		// Build item_meta: per-item holder count
		Map<String, ClanClogResult.ItemMeta> itemMeta = new HashMap<>();
		for (Map<Integer, Set<String>> holders : categoryHolders.values())
		{
			for (Map.Entry<Integer, Set<String>> e : holders.entrySet())
			{
				String idStr = String.valueOf(e.getKey());
				if (!itemMeta.containsKey(idStr))
				{
					itemMeta.put(idStr, new ClanClogResult.ItemMeta(e.getValue().size()));
				}
			}
		}

		// Build catalog: category -> full item list (all items in the category)
		Map<String, List<Integer>> catalogMap = new LinkedHashMap<>();
		for (Map.Entry<String, Set<Integer>> entry : categoryCatalogs.entrySet())
		{
			catalogMap.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}

		return new ClanClogResult.ClogUnion(itemsByCategory, allObtained.size(),
			itemMeta, catalogMap);
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
