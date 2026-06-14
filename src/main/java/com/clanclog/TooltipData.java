package com.clanclog;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Immutable data holder for sprite tooltip content.
 *
 * <p>Carries both per-player fields (ported byte-identical from kcpdev) and
 * clan-aware fields added 2026-05-20 for sub-phase 3b.3.3. The clan fields
 * are {@code @Nullable} so per-player code paths still work with the
 * 7-arg constructor; the clan-aware constructors populate them for clan-flavored
 * tooltips (holder counts, first-seen history). ImgTooltip's renderer can
 * branch on null-ness of the clan fields to render either per-player obtained
 * counts or clan holder count overlays.
 */
final class TooltipData
{
	final String name;
	final int rank;
	final int obtainedCount;
	final int totalItems;
	final int contributorCount;
	final List<Integer> allItemIds;
	final Set<Integer> obtainedIds;
	final Map<Integer, Integer> obtainedCounts;
	final Map<Integer, String> itemNames;

	/** Clan-aware: how many clan members hold each item id. Null in per-player context. */
	@Nullable final Map<Integer, Integer> holderCounts;
	/** Clan-aware: first-seen-by-killclog-api timestamp per item id (ISO). Null in per-player context. */
	@Nullable final Map<Integer, String> firstSeenAt;
	/** Clan-aware: rsn of the first-recorded holder per item id. Null in per-player context. */
	@Nullable final Map<Integer, String> firstSeenByRsn;

	/** Per-player constructor (existing kcpdev shape, clan fields null). */
	TooltipData(String name, int rank, int obtainedCount, int totalItems,
				List<Integer> allItemIds, Set<Integer> obtainedIds,
				Map<Integer, Integer> obtainedCounts)
	{
		this(name, rank, obtainedCount, totalItems, allItemIds, obtainedIds, obtainedCounts,
			Collections.emptyMap(), null, null, null, 0);
	}

	/** Clan-aware constructor (added 2026-05-20 sub-phase 3b.3.3). */
	TooltipData(String name, int rank, int obtainedCount, int totalItems,
				List<Integer> allItemIds, Set<Integer> obtainedIds,
				Map<Integer, Integer> obtainedCounts,
				@Nullable Map<Integer, Integer> holderCounts,
				@Nullable Map<Integer, String> firstSeenAt,
				@Nullable Map<Integer, String> firstSeenByRsn)
	{
		this(name, rank, obtainedCount, totalItems, allItemIds, obtainedIds, obtainedCounts,
			Collections.emptyMap(), holderCounts, firstSeenAt, firstSeenByRsn, 0);
	}

	/** Clan-aware constructor with aggregate contributor count. */
	TooltipData(String name, int rank, int obtainedCount, int totalItems,
				List<Integer> allItemIds, Set<Integer> obtainedIds,
				Map<Integer, Integer> obtainedCounts,
				Map<Integer, String> itemNames,
				@Nullable Map<Integer, Integer> holderCounts,
				@Nullable Map<Integer, String> firstSeenAt,
				@Nullable Map<Integer, String> firstSeenByRsn,
				int contributorCount)
	{
		this.name = name;
		this.rank = rank;
		this.obtainedCount = obtainedCount;
		this.totalItems = totalItems;
		this.contributorCount = contributorCount;
		this.allItemIds = allItemIds;
		this.obtainedIds = obtainedIds;
		this.obtainedCounts = obtainedCounts;
		this.itemNames = itemNames != null ? itemNames : Collections.emptyMap();
		this.holderCounts = holderCounts;
		this.firstSeenAt = firstSeenAt;
		this.firstSeenByRsn = firstSeenByRsn;
	}

	/** True when this tooltip data carries clan-aggregate fields (holder counts, first-seen). */
	boolean isClanFlavor()
	{
		return holderCounts != null;
	}
}
