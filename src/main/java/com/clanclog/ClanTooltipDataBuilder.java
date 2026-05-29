package com.clanclog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * <p>Catalog gap: the backend {@code clog.items_by_category} only lists
 * obtained items (the union across members), not the full OSRS clog
 * catalog per boss. So {@code allItemIds == obtainedIds == clan union}
 * for the category, and {@code obtainedCount == totalItems == union size}.
 * The "rarest missing" feature requires the catalog and is deferred to a
 * future sub-phase that adds catalog endpoints to the backend.
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

		List<Integer> categoryItems = result.getClog().getItemsByCategory().get(category);
		if (categoryItems == null || categoryItems.isEmpty())
		{
			return null;
		}

		// When the client-side catalog is available (from per-member clog
		// data), use it as the full item list so completion shows X/Y
		// correctly. Falls back to obtained-only when no catalog exists
		// (e.g. backend-only data without catalog support).
		List<Integer> catalog = result.getClog().getCatalog(category);
		List<Integer> allItemIds = catalog != null && !catalog.isEmpty()
			? new ArrayList<>(catalog)
			: new ArrayList<>(categoryItems);
		Set<Integer> obtainedIds = new HashSet<>(categoryItems);

		// Per-item meta enrichment from clog.item_meta (keyed by item id as string)
		Map<String, ClanClogResult.ItemMeta> itemMetaRaw = result.getClog().getItemMeta();
		Map<Integer, Integer> holderCounts = new HashMap<>();
		Map<Integer, String> firstSeenAt = new HashMap<>();
		Map<Integer, String> firstSeenByRsn = new HashMap<>();
		// Map<Integer, Integer> obtainedCounts: in clan context we surface
		// holder_count as the per-item count overlay (clean semantic match
		// with ImgTooltip's existing per-item count rendering).
		Map<Integer, Integer> obtainedCounts = new HashMap<>();

		for (int itemId : categoryItems)
		{
			ClanClogResult.ItemMeta meta = itemMetaRaw.get(String.valueOf(itemId));
			if (meta == null)
			{
				obtainedCounts.put(itemId, 1);
				continue;
			}
			holderCounts.put(itemId, meta.getHolderCount());
			obtainedCounts.put(itemId, meta.getHolderCount());
			if (meta.getFirstSeenAt() != null)
			{
				firstSeenAt.put(itemId, meta.getFirstSeenAt());
			}
			if (meta.getFirstSeenByRsn() != null)
			{
				firstSeenByRsn.put(itemId, meta.getFirstSeenByRsn());
			}
		}

		return new TooltipData(
			displayName,
			0, // rank is not meaningful at clan scope; placeholder
			categoryItems.size(),
			allItemIds.size(),
			allItemIds,
			obtainedIds,
			obtainedCounts,
			holderCounts,
			firstSeenAt,
			firstSeenByRsn);
	}

	/**
	 * Convenience: build TooltipData where the category key matches the
	 * display name exactly (common case for boss + clue tier cells).
	 */
	public TooltipData buildFor(String name, ClanClogResult result)
	{
		return buildForCategory(name, name, result);
	}
}
