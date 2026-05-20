package com.clanclog;

import com.google.gson.annotations.SerializedName;
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
 *   "member_coverage": { "total": 11, "temple_ok": 9, ... },
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

	@SerializedName("member_count")
	private int memberCount;

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

	public String getSlug()
	{
		return slug;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public int getMemberCount()
	{
		return memberCount;
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

	/**
	 * Member-coverage tally. Every member ends up in exactly one bucket so
	 * {@code total == temple_ok + temple_missing + opted_out + not_found + error}.
	 * Powers the honest "N of M members synced with Temple" UI surface per
	 * the public-safety canon.
	 */
	public static class MemberCoverage
	{
		@SerializedName("total")          private int total;
		@SerializedName("temple_ok")      private int templeOk;
		@SerializedName("temple_missing") private int templeMissing;
		@SerializedName("opted_out")      private int optedOut;
		@SerializedName("not_found")      private int notFound;
		@SerializedName("error")          private int error;

		public int getTotal()
		{
			return total;
		}

		public int getTempleOk()
		{
			return templeOk;
		}

		public int getTempleMissing()
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
	 * Clan combined clog: items unioned across members with temple coverage,
	 * per-item enrichment (holder counts + first-seen history), recently-
	 * acquired feed sorted by first_seen_at desc.
	 */
	public static class ClogUnion
	{
		@SerializedName("items_by_category") private Map<String, List<Integer>> itemsByCategory;
		@SerializedName("total_obtained")    private int totalObtained;
		@SerializedName("item_meta")         private Map<String, ItemMeta> itemMeta;
		@SerializedName("recently_acquired") private List<RecentItem> recentlyAcquired;

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
