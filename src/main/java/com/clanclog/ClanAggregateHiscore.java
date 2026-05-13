package com.clanclog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntBiFunction;

/**
 * Pure reducer: takes a clan roster (with per-member {@link HiscoreResult}s
 * populated where available) and produces per-cell aggregate stats for the
 * collective hiscore grid. The "clan version of kill clog" view.
 *
 * <p>For each boss / activity / skill name, computes:
 * <ul>
 *   <li>{@code clanBest} -- highest KC / score / level any member has</li>
 *   <li>{@code holder} -- the member's display name who holds the best</li>
 *   <li>{@code rankedCount} -- how many members have any rank (KC &gt; 0)</li>
 *   <li>{@code total} -- summed KC across the clan</li>
 *   <li>{@code top} -- the top-N members for tooltip rendering</li>
 * </ul>
 *
 * <p>Stateless + pure: callers recompute on every panel refresh tick. Cheap
 * even for 500-member clans across all bosses (~30k cell lookups, all in-memory).
 */
public final class ClanAggregateHiscore
{
	private static final int DEFAULT_TOP_N = 5;

	public static final class CellAggregate
	{
		public final int clanBest;
		public final String holder;
		public final long total;
		public final int rankedCount;
		public final List<MemberValue> top;

		public CellAggregate(int clanBest, String holder, long total,
			int rankedCount, List<MemberValue> top)
		{
			this.clanBest = clanBest;
			this.holder = holder;
			this.total = total;
			this.rankedCount = rankedCount;
			this.top = top;
		}

		public boolean hasAny()
		{
			return rankedCount > 0;
		}
	}

	public static final class MemberValue
	{
		public final String rsn;
		public final int value;

		public MemberValue(String rsn, int value)
		{
			this.rsn = rsn;
			this.value = value;
		}
	}

	private ClanAggregateHiscore() { }

	/**
	 * Compute aggregates for a set of named cells. Boss callers pass
	 * {@link HiscoreService#bossNames()} + a {@code HiscoreResult::getKc}
	 * extractor; activity callers pass {@link HiscoreService#activityNames()} +
	 * {@code getActivityScore}; skill callers pass {@link HiscoreService#skillNames()} +
	 * {@code getSkillLevel}.
	 *
	 * <p>Returns insertion-ordered map so the panel renders cells in the same
	 * order it was given (alphabetical for bosses, CSV-order for activities).
	 */
	public static Map<String, CellAggregate> compute(
		List<ClanMember> roster, String[] names, ToIntBiFunction<HiscoreResult, String> extractor)
	{
		return compute(roster, names, extractor, DEFAULT_TOP_N);
	}

	public static Map<String, CellAggregate> compute(
		List<ClanMember> roster, String[] names,
		ToIntBiFunction<HiscoreResult, String> extractor, int topN)
	{
		Map<String, CellAggregate> out = new LinkedHashMap<>(names.length);
		if (roster == null || roster.isEmpty())
		{
			for (String name : names)
			{
				out.put(name, new CellAggregate(0, null, 0L, 0, Collections.emptyList()));
			}
			return out;
		}

		for (String name : names)
		{
			int best = 0;
			String holder = null;
			long total = 0L;
			int ranked = 0;
			List<MemberValue> values = new ArrayList<>();

			for (ClanMember m : roster)
			{
				HiscoreResult hs = m.getHiscore();
				if (hs == null)
				{
					continue;
				}
				int v = extractor.applyAsInt(hs, name);
				if (v <= 0)
				{
					continue;
				}
				ranked++;
				total += v;
				values.add(new MemberValue(m.getDisplayName(), v));
				if (v > best)
				{
					best = v;
					holder = m.getDisplayName();
				}
			}

			values.sort((a, b) -> Integer.compare(b.value, a.value));
			List<MemberValue> top = values.size() > topN ? values.subList(0, topN) : values;
			out.put(name, new CellAggregate(best, holder, total, ranked, new ArrayList<>(top)));
		}

		return out;
	}

	/** Convenience: compute boss aggregates from a roster. */
	public static Map<String, CellAggregate> bosses(List<ClanMember> roster)
	{
		return compute(roster, HiscoreService.bossNames(), HiscoreResult::getKc);
	}

	/** Convenience: compute activity aggregates from a roster. */
	public static Map<String, CellAggregate> activities(List<ClanMember> roster)
	{
		return compute(roster, HiscoreService.activityNames(), HiscoreResult::getActivityScore);
	}

	/** Convenience: compute skill-level aggregates from a roster. */
	public static Map<String, CellAggregate> skills(List<ClanMember> roster)
	{
		return compute(roster, HiscoreService.skillNames(), HiscoreResult::getSkillLevel);
	}
}
