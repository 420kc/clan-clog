package com.clanclog;

/**
 * Canonical clan clog representation of one clan member. Built once from the
 * source-of-truth roster (currently WOM) and then enriched with per-member
 * {@link HiscoreResult} by {@code ClanHiscoreBatch}.
 *
 * <p>Intentionally not coupled to WOM's wire shape -- the swap to a
 * killclog.com aggregation backend in phase 2 only needs a new factory.
 */
public class ClanMember
{
	private final String rsn;
	private final String displayName;
	private final String role;
	private final AccountType accountType;
	private final String build;
	private final long totalXp;
	private final String lastUpdatedAt;

	/** Filled in lazily after the batch hiscore fetch. Null until then. */
	private volatile HiscoreResult hiscore;

	public ClanMember(String rsn, String displayName, String role,
		AccountType accountType, String build, long totalXp, String lastUpdatedAt)
	{
		this.rsn = rsn;
		this.displayName = displayName;
		this.role = role;
		this.accountType = accountType;
		this.build = build;
		this.totalXp = totalXp;
		this.lastUpdatedAt = lastUpdatedAt;
	}

	public static ClanMember fromWom(WomMembership ms)
	{
		if (ms == null || ms.player == null)
		{
			return null;
		}
		WomPlayer p = ms.player;
		return new ClanMember(
			p.username,
			p.displayName != null ? p.displayName : p.username,
			ms.role,
			parseAccountType(p.type),
			p.build,
			p.exp,
			p.updatedAt);
	}

	private static AccountType parseAccountType(String womType)
	{
		if (womType == null)
		{
			return AccountType.REGULAR;
		}
		switch (womType)
		{
			case "ironman":
				return AccountType.IRONMAN;
			case "hardcore":
				return AccountType.HARDCORE_IRONMAN;
			case "ultimate":
				return AccountType.ULTIMATE_IRONMAN;
			case "group_ironman":
				return AccountType.GROUP_IRONMAN;
			case "hardcore_group_ironman":
				return AccountType.HARDCORE_GROUP_IRONMAN;
			case "unranked_group_ironman":
				return AccountType.UNRANKED_GROUP_IRONMAN;
			default:
				return AccountType.REGULAR;
		}
	}

	public String getRsn()
	{
		return rsn;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public String getRole()
	{
		return role;
	}

	public AccountType getAccountType()
	{
		return accountType;
	}

	public String getBuild()
	{
		return build;
	}

	public long getTotalXp()
	{
		return totalXp;
	}

	public String getLastUpdatedAt()
	{
		return lastUpdatedAt;
	}

	public HiscoreResult getHiscore()
	{
		return hiscore;
	}

	public void setHiscore(HiscoreResult hiscore)
	{
		this.hiscore = hiscore;
	}
}
