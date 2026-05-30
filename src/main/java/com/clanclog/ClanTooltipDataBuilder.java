package com.clanclog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Builds {@link TooltipData} objects from a {@link ClanClogResult} for the
 * clan-aware tooltip surface. The clan flavor of the per-player
 * TooltipDataBuilder (which lives byte-ported in kcpdev and is not in
 * clan-clog because the plugin only renders clan-aggregate data).
 *
 * <p>Consumes the backend {@code GET /api/clan/<slug>/clog} response, or the
 * locally built equivalent, and produces TooltipData with clan-aware fields
 * populated (holderCounts + firstSeenAt + firstSeenByRsn). Holder counts are
 * passed through the existing obtained-count overlay path so the sprite grid
 * can stay close to Kill Clog's item tooltip renderer.
 *
 * <p>Client-built and synced clan results include the full category catalog,
 * so empty categories can still render a dim sprite grid. Results without a
 * catalog intentionally do not render obtained-only as complete.
 */
@Singleton
public class ClanTooltipDataBuilder
{
	@Inject
	public ClanTooltipDataBuilder()
	{
	}

	/**
	 * Build TooltipData for a clog category from a clan-aggregate result.
	 * Returns null if the result is null or has no items in the requested
	 * category.
	 *
	 * @param displayName  the cell's display name (e.g. "Zulrah", "Beginner clues")
	 * @param category     the clan-side category key (matches backend keys,
	 *                     e.g. "Zulrah" / "clue_beginner")
	 * @param result       the parsed backend response (or null)
	 * @return TooltipData with clan-aware fields populated, or null
	 */
	public TooltipData buildForCategory(String displayName, String category, ClanClogResult result)
	{
		if (result == null || result.getClog() == null)
		{
			return null;
		}

		List<Integer> catalog = result.getClog().getCatalog(category);
		if (catalog == null || catalog.isEmpty())
		{
			return null;
		}

		Map<String, ClanClogResult.ItemMeta> itemMetaRaw = result.getClog().getItemMeta();
		List<Integer> categoryItems = result.getClog().getItemsByCategory().get(category);
		List<Integer> allItemIds = new ArrayList<>(catalog);
		Set<Integer> obtainedIds = categoryItems != null
			? new HashSet<>(categoryItems) : new HashSet<>();

		// Per-item meta enrichment from clog.item_meta (keyed by item id as string)
		Map<Integer, Integer> holderCounts = new HashMap<>();
		Map<Integer, String> firstSeenAt = new HashMap<>();
		Map<Integer, String> firstSeenByRsn = new HashMap<>();
		Map<Integer, List<ClanClogResult.ItemContributor>> contributors = new HashMap<>();
		// Map<Integer, Integer> obtainedCounts: in clan context this is the
		// summed duplicate quantity across members, while holderCounts keeps
		// the separate "how many clanmates have it" value.
		Map<Integer, Integer> obtainedCounts = new HashMap<>();

		for (int itemId : allItemIds)
		{
			ClanClogResult.ItemMeta meta = itemMetaRaw.get(String.valueOf(itemId));
			if (meta == null)
			{
				if (obtainedIds.contains(itemId))
				{
					obtainedCounts.put(itemId, 1);
				}
				continue;
			}
			obtainedIds.add(itemId);
			holderCounts.put(itemId, meta.getHolderCount());
			obtainedCounts.put(itemId, meta.getQuantityTotal());
			if (meta.getFirstSeenAt() != null)
			{
				firstSeenAt.put(itemId, meta.getFirstSeenAt());
			}
			if (meta.getFirstSeenByRsn() != null)
			{
				firstSeenByRsn.put(itemId, meta.getFirstSeenByRsn());
			}
			if (!meta.getContributors().isEmpty())
			{
				contributors.put(itemId, meta.getContributors());
			}
		}
		int obtainedCount = ClogHelper.countObtained(allItemIds, obtainedIds);

		return new TooltipData(
			displayName,
			0, // rank is not meaningful at clan scope; placeholder
			obtainedCount,
			allItemIds.size(),
			allItemIds,
			obtainedIds,
			obtainedCounts,
			holderCounts,
			firstSeenAt,
			firstSeenByRsn,
			contributorCount(displayName, category, result, contributors),
			contributors);
	}

	/**
	 * Convenience: build TooltipData where the category key matches the
	 * display name exactly (common case for boss + clue tier cells).
	 */
	public TooltipData buildFor(String name, ClanClogResult result)
	{
		return buildForCategory(name, name, result);
	}

	/**
	 * Build category-first data for synthetic rare buckets. The fixed Kill Clog
	 * item set is the canon floor; provider catalogs can add to it, but must
	 * not replace it.
	 */
	public TooltipData buildRareBucketData(String name, String category,
		int[] fallbackItemIds, ClanClogResult result)
	{
		ClanClogResult.ClogUnion clog = result != null ? result.getClog() : null;
		Map<String, ClanClogResult.ItemMeta> itemMeta = clog != null
			? clog.getItemMeta() : Collections.emptyMap();

		Set<Integer> allItems = new LinkedHashSet<>();
		for (int itemId : fallbackItemIds)
		{
			allItems.add(itemId);
		}

		if (clog != null)
		{
			List<Integer> catalog = clog.getCatalog(category);
			if (catalog != null && !catalog.isEmpty())
			{
				allItems.addAll(catalog);
			}
		}

		return buildFromItemIds(name, new ArrayList<>(allItems),
			clog != null ? clog.getItemsByCategory().get(category) : null,
			itemMeta);
	}

	private TooltipData buildFromItemIds(String name, List<Integer> allItemIds,
		List<Integer> categoryItems, Map<String, ClanClogResult.ItemMeta> itemMetaRaw)
	{
		Set<Integer> obtainedIds = categoryItems != null
			? new HashSet<>(categoryItems) : new HashSet<>();
		Map<Integer, Integer> holderCounts = new LinkedHashMap<>();
		Map<Integer, String> firstSeenAt = new LinkedHashMap<>();
		Map<Integer, String> firstSeenByRsn = new LinkedHashMap<>();
		Map<Integer, List<ClanClogResult.ItemContributor>> contributors = new LinkedHashMap<>();
		Map<Integer, Integer> obtainedCounts = new LinkedHashMap<>();

		for (int itemId : allItemIds)
		{
			ClanClogResult.ItemMeta meta = itemMetaRaw.get(String.valueOf(itemId));
			if (meta != null)
			{
				obtainedIds.add(itemId);
				holderCounts.put(itemId, meta.getHolderCount());
				obtainedCounts.put(itemId, meta.getQuantityTotal());
				if (meta.getFirstSeenAt() != null)
				{
					firstSeenAt.put(itemId, meta.getFirstSeenAt());
				}
				if (meta.getFirstSeenByRsn() != null)
				{
					firstSeenByRsn.put(itemId, meta.getFirstSeenByRsn());
				}
				if (!meta.getContributors().isEmpty())
				{
					contributors.put(itemId, meta.getContributors());
				}
			}
			else if (obtainedIds.contains(itemId))
			{
				obtainedCounts.put(itemId, 1);
			}
		}

		int obtainedCount = ClogHelper.countObtained(allItemIds, obtainedIds);
		return new TooltipData(
			name,
			0,
			obtainedCount,
			allItemIds.size(),
			allItemIds,
			obtainedIds,
			obtainedCounts,
			holderCounts,
			firstSeenAt,
			firstSeenByRsn,
			uniqueContributorCount(contributors),
			contributors);
	}

	private static int contributorCount(String displayName, String category,
		ClanClogResult result,
		Map<Integer, List<ClanClogResult.ItemContributor>> itemContributors)
	{
		int bossContributors = bossContributorCount(displayName, category, result);
		if (bossContributors > 0)
		{
			return bossContributors;
		}
		return uniqueContributorCount(itemContributors);
	}

	private static int bossContributorCount(String displayName, String category,
		ClanClogResult result)
	{
		ClanClogResult.BossAggregate aggregate = result.getBosses().get(displayName);
		if (aggregate == null)
		{
			aggregate = result.getBosses().get(category);
		}
		return aggregate != null ? aggregate.getMemberCoverage() : 0;
	}

	private static int uniqueContributorCount(
		Map<Integer, List<ClanClogResult.ItemContributor>> itemContributors)
	{
		Set<String> names = new HashSet<>();
		for (List<ClanClogResult.ItemContributor> rows : itemContributors.values())
		{
			if (rows == null)
			{
				continue;
			}
			for (ClanClogResult.ItemContributor row : rows)
			{
				if (row != null && row.getRsn() != null && !row.getRsn().isBlank())
				{
					names.add(row.getRsn().toLowerCase());
				}
			}
		}
		return names.size();
	}
}
