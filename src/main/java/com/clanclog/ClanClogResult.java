package com.clanclog;

import com.google.gson.annotations.SerializedName;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Canonical in-plugin holder for the {@code GET /api/clan/<slug>/clog}
 * response from killclog-api. Fields map to the backend response shape
 * verbatim via Gson @SerializedName annotations so the in-plugin code can
 * read camelCase Java while the wire format stays snake_case.
 *
 * <p>Per project_clan_hiscores_plugin.md Tab 1 design sketch (locked
 * 2026-05-20): the data contract Cells.java + tooltips bind to. Mirrors the
 * backend src/clan-clog.js + src/routes/clan.js clog handler shape from
 * commit 20d9fe5 on track-c-clan-clog.
 *
 * <p>Backend response example (relevant fields):
 * <pre>{@code
 * {
 *   "slug": "exclusive-elite-club",
 *   "display_name": "Exclusive Elite Club",
 *   "member_count": 11,
 *   "roster_hash": "abc123...",
 *   "last_synced_at": "2026-05-20T15:00:00Z",
 *   "clog_last_changed": "2026-05-20T14:30:00Z",
 *   "member_coverage": { "total": 11, "clog_ok": 9, "temple_ok": 9, ... },
 *   "clog": {
 *     "items_by_category": { "bosses": [11802, ...] },
 *     "total_obtained": 487,
 *     "item_meta": { "11802": { "holder_count": 5, ... } },
 *     "recently_acquired": [...]
 *   },
 *   "bosses": {
 *     "Zulrah": { "clan_total_kc": 12500, "top_3": [...], "member_coverage": 8 }
 *   }
 * }
 * }</pre>
 */
public class ClanClogResult
{
	@SerializedName("slug")
	private String slug;

	@SerializedName("display_name")
	private String displayName;

	@SerializedName("schema")
	private String schema;

	@SerializedName("source_tier")
	private String sourceTier;

	@SerializedName("build_status")
	private String buildStatus;

	@SerializedName("last_built_at")
	private String lastBuiltAt;

	@SerializedName("member_count")
	private int memberCount;

	@SerializedName("members")
	private List<ProfileMember> members;

	@SerializedName("roster_hash")
	private String rosterHash;

	@SerializedName("last_synced_at")
	private String lastSyncedAt;

	@SerializedName("clog_last_changed")
	private String clogLastChanged;

	@SerializedName("member_coverage")
	private MemberCoverage memberCoverage;

	@SerializedName("clog")
	private ClogUnion clog;

	@SerializedName("bosses")
	private Map<String, BossAggregate> bosses;

	/** Clan-aggregate activity totals (keyed by HiscoreService.ACTIVITY_NAMES). */
	@SerializedName(value = "activities", alternate = "activity_totals")
	private Map<String, Long> activityTotals;

	public String getSlug()
	{
		return slug;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public String getSchema()
	{
		return schema;
	}

	public String getSourceTier()
	{
		return sourceTier;
	}

	public String getBuildStatus()
	{
		return buildStatus;
	}

	public String getLastBuiltAt()
	{
		return lastBuiltAt;
	}

	public int getMemberCount()
	{
		return memberCount;
	}

	public List<ClanMember> getMembers()
	{
		if (members == null || members.isEmpty())
		{
			return Collections.emptyList();
		}
		List<ClanMember> out = new ArrayList<>(members.size());
		for (ProfileMember member : members)
		{
			ClanMember clanMember = member.toClanMember();
			if (clanMember != null)
			{
				out.add(clanMember);
			}
		}
		return out;
	}

	public String getRosterHash()
	{
		return rosterHash;
	}

	public String getLastSyncedAt()
	{
		return lastSyncedAt;
	}

	public String getClogLastChanged()
	{
		return clogLastChanged;
	}

	public MemberCoverage getMemberCoverage()
	{
		return memberCoverage;
	}

	public ClogUnion getClog()
	{
		return clog;
	}

	public Map<String, BossAggregate> getBosses()
	{
		return bosses != null ? bosses : Collections.emptyMap();
	}

	public Map<String, Long> getActivityTotals()
	{
		return activityTotals != null ? activityTotals : Collections.emptyMap();
	}

	/**
	 * True when this result carries data worth rendering in the grid. The new
	 * Clan Profile endpoint can legally return a roster-only shell, so callers
	 * use this before deciding whether to fall back to the legacy /clog route.
	 */
	public boolean hasAggregateData()
	{
		return clog != null
			|| memberCoverage != null
			|| !getBosses().isEmpty()
			|| !getActivityTotals().isEmpty();
	}

	public boolean isReadyProfile()
	{
		return "ready".equalsIgnoreCase(buildStatus);
	}

	public boolean isRosterOnlyProfile()
	{
		return "roster_only".equalsIgnoreCase(buildStatus);
	}

	// ── Package-private mutators for roster-derived overlays ──────────────

	private static class ProfileMember
	{
		@SerializedName("rsn")          private String rsn;
		@SerializedName("rank")         private String rank;
		@SerializedName("custom_title") private String customTitle;
		@SerializedName("join_date")    private String joinDate;

		private ClanMember toClanMember()
		{
			if (rsn == null || rsn.isBlank())
			{
				return null;
			}
			String role = customTitle != null && !customTitle.isBlank()
				? customTitle : rank;
			return new ClanMember(
				rsn,
				rsn,
				role,
				rank,
				AccountType.REGULAR,
				null,
				0L,
				null,
				parseJoinDate(joinDate));
		}

		private static LocalDate parseJoinDate(String raw)
		{
			if (raw == null || raw.isBlank())
			{
				return null;
			}
			try
			{
				return LocalDate.parse(raw);
			}
			catch (DateTimeParseException ignored)
			{
				return null;
			}
		}
	}

	/** Replace boss aggregates with live hiscore-derived data. */
	void setBosses(Map<String, BossAggregate> bosses)
	{
		this.bosses = bosses;
	}

	/** Replace clog union with client-side aggregated data. */
	void setClog(ClogUnion clog)
	{
		this.clog = clog;
	}

	/** Record the freshest member collection-log sync represented in the union. */
	void setClogLastChanged(String clogLastChanged)
	{
		this.clogLastChanged = clogLastChanged;
	}

	/** Attach roster-derived coverage so a sync reports honestly what was collected. */
	void setMemberCoverage(MemberCoverage memberCoverage)
	{
		this.memberCoverage = memberCoverage;
	}

	/** Replace activity totals with live hiscore-derived data. */
	void setActivityTotals(Map<String, Long> activityTotals)
	{
		this.activityTotals = activityTotals;
	}

	/**
	 * Build a ClanClogResult from roster hiscore data when no backend data
	 * exists. Clog stays null until a collection-log source covers members.
	 */
	static ClanClogResult forRoster(String slug, String displayName,
		int memberCount, Map<String, BossAggregate> bosses)
	{
		ClanClogResult r = new ClanClogResult();
		r.slug = slug;
		r.displayName = displayName;
		r.memberCount = memberCount;
		r.bosses = bosses;
		return r;
	}

	/**
	 * Member-coverage tally. Every member ends up in exactly one bucket. The
	 * source-neutral keys are canonical, while provider-era keys are still
	 * emitted and parsed for backend/web compatibility during the migration.
	 */
	public static class MemberCoverage
	{
		@SerializedName("total")          private int total;
		@SerializedName("clog_ok")        private Integer clogOk;
		@SerializedName("temple_ok")      private int templeOk;
		@SerializedName("temple_missing") private int templeMissing;
		@SerializedName("opted_out")      private int optedOut;
		@SerializedName("not_found")      private int notFound;
		@SerializedName("error")          private int error;

		/** Gson reflective constructor. */
		@SuppressWarnings("unused")
		private MemberCoverage()
		{
		}

		/** Built client-side from the analyzed roster before sync. */
		MemberCoverage(int total, int clogOk, int hiscoreOnly,
			int optedOut, int notFound, int error)
		{
			this.total = total;
			this.clogOk = clogOk;
			this.templeOk = clogOk;
			this.templeMissing = hiscoreOnly;
			this.optedOut = optedOut;
			this.notFound = notFound;
			this.error = error;
		}

		public int getTotal()
		{
			return total;
		}

		public int getTempleOk()
		{
			return getClogOk();
		}

		public int getClogOk()
		{
			return clogOk != null ? clogOk : templeOk;
		}

		public int getTempleMissing()
		{
			return templeMissing;
		}

		public int getHiscoreOnly()
		{
			return templeMissing;
		}

		public int getOptedOut()
		{
			return optedOut;
		}

		public int getNotFound()
		{
			return notFound;
		}

		public int getError()
		{
			return error;
		}
	}

	/**
	 * Clan combined clog: items unioned across covered members with source coverage,
	 * per-item enrichment (holder counts + first-seen history), recently-
	 * acquired feed sorted by first_seen_at desc.
	 */
	public static class ClogUnion
	{
		@SerializedName("items_by_category") private Map<String, List<Integer>> itemsByCategory;
		@SerializedName("total_obtained")    private int totalObtained;
		@SerializedName("item_meta")         private Map<String, ItemMeta> itemMeta;
		@SerializedName("recently_acquired") private List<RecentItem> recentlyAcquired;

		/**
		 * Full catalog per category: every item ID that exists in the category,
		 * not just the ones the clan obtained. Populated by client-side
		 * aggregation from per-member collection-log data. Null when deserialized
		 * from a backend response (the backend doesn't ship catalogs yet).
		 */
		private transient Map<String, List<Integer>> catalogByCategory;

		/** Gson reflective constructor. */
		@SuppressWarnings("unused")
		private ClogUnion()
		{
		}

		/** Package-private constructor for client-side aggregation. */
		ClogUnion(Map<String, List<Integer>> itemsByCategory, int totalObtained,
			Map<String, ItemMeta> itemMeta,
			Map<String, List<Integer>> catalogByCategory)
		{
			this.itemsByCategory = itemsByCategory;
			this.totalObtained = totalObtained;
			this.itemMeta = itemMeta;
			this.recentlyAcquired = Collections.emptyList();
			this.catalogByCategory = catalogByCategory;
		}

		public Map<String, List<Integer>> getItemsByCategory()
		{
			return itemsByCategory != null ? itemsByCategory : Collections.emptyMap();
		}

		public int getTotalObtained()
		{
			return totalObtained;
		}

		public Map<String, ItemMeta> getItemMeta()
		{
			return itemMeta != null ? itemMeta : Collections.emptyMap();
		}

		public List<RecentItem> getRecentlyAcquired()
		{
			return recentlyAcquired != null ? recentlyAcquired : Collections.emptyList();
		}

		/**
		 * Full catalog for a category, or null if not available. Only
		 * populated after client-side clog aggregation (not from backend).
		 */
		public List<Integer> getCatalog(String category)
		{
			return catalogByCategory != null ? catalogByCategory.get(category) : null;
		}
	}

	/**
	 * Per-item enrichment: how many clan members have this item, when it
	 * first appeared in the clan's combined-clog scan, who held it first.
	 * For items obtained before the 3a backend reducer shipped, first_seen_at
	 * is pinned to the first scan after release (acknowledged limitation).
	 */
	public static class ItemMeta
	{
		@SerializedName("holder_count")      private int holderCount;
		@SerializedName("first_seen_at")     private String firstSeenAt;
		@SerializedName("first_seen_by_rsn") private String firstSeenByRsn;

		/** Gson reflective constructor. */
		@SuppressWarnings("unused")
		private ItemMeta()
		{
		}

		/** Package-private constructor for client-side aggregation. */
		ItemMeta(int holderCount)
		{
			this.holderCount = holderCount;
		}

		public int getHolderCount()
		{
			return holderCount;
		}

		public String getFirstSeenAt()
		{
			return firstSeenAt;
		}

		public String getFirstSeenByRsn()
		{
			return firstSeenByRsn;
		}
	}

	/** One entry in the recently-acquired feed (sorted desc by first_seen_at on the backend, cap 30). */
	public static class RecentItem
	{
		@SerializedName("item_id")            private int itemId;
		@SerializedName("category")           private String category;
		@SerializedName("first_seen_at")      private String firstSeenAt;
		@SerializedName("first_seen_by_rsn")  private String firstSeenByRsn;
		@SerializedName("holder_count")       private int holderCount;

		public int getItemId()
		{
			return itemId;
		}

		public String getCategory()
		{
			return category;
		}

		public String getFirstSeenAt()
		{
			return firstSeenAt;
		}

		public String getFirstSeenByRsn()
		{
			return firstSeenByRsn;
		}

		public int getHolderCount()
		{
			return holderCount;
		}
	}

	/**
	 * Per-boss aggregation: clan total kc + top-3 contributors with their kc
	 * + member coverage (how many clan members had hiscores data for this boss).
	 * Built by the backend reducer from each member's hiscores.bosses array.
	 */
	public static class BossAggregate
	{
		@SerializedName("clan_total_kc")    private long clanTotalKc;
		@SerializedName("top_3")            private List<MemberKc> top3;
		@SerializedName("member_coverage")  private int memberCoverage;

		/** Gson reflective constructor. */
		@SuppressWarnings("unused")
		private BossAggregate()
		{
		}

		BossAggregate(long clanTotalKc, List<MemberKc> top3, int memberCoverage)
		{
			this.clanTotalKc = clanTotalKc;
			this.top3 = top3 != null ? new ArrayList<>(top3) : Collections.emptyList();
			this.memberCoverage = memberCoverage;
		}

		public long getClanTotalKc()
		{
			return clanTotalKc;
		}

		public List<MemberKc> getTop3()
		{
			return top3 != null ? top3 : Collections.emptyList();
		}

		public int getMemberCoverage()
		{
			return memberCoverage;
		}
	}

	/** One contributor's kc at a boss, used in BossAggregate.top_3. */
	public static class MemberKc
	{
		@SerializedName("rsn") private String rsn;
		@SerializedName("kc")  private long kc;

		/** Gson reflective constructor. */
		@SuppressWarnings("unused")
		private MemberKc()
		{
		}

		MemberKc(String rsn, long kc)
		{
			this.rsn = rsn;
			this.kc = kc;
		}

		public String getRsn()
		{
			return rsn;
		}

		public long getKc()
		{
			return kc;
		}
	}
}
