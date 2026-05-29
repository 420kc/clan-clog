package com.clanclog;

import java.time.LocalDate;
import net.runelite.api.clan.ClanRank;

/**
 * Canonical clan clog representation of one clan member. Built from whichever
 * roster source is supplying the data (in-game runtime via
 * {@code ClanSettings.getMembers()}, WOM, RuneProfile, Temple, or eventually
 * killclog.com aggregation) and then enriched with per-member
 * {@link HiscoreResult} by {@code ClanHiscoreBatch}.
 *
 * <p>Provider-dependent fields are nullable -- in-game source supplies
 * {@code joinDate} but not {@code build} / {@code totalXp}, WOM supplies the
 * latter pair but not {@code joinDate}, and so on. Panel code treats nulls
 * as "data unavailable from this source" and renders accordingly.
 */
public class ClanMember
{
	private final String rsn;
	private final String displayName;
	private final String role;
	private final String rankName;
	private final AccountType accountType;
	private final String build;
	private final long totalXp;
	private final String lastUpdatedAt;
	private final LocalDate joinDate;

	/** Filled in lazily after the batch hiscore fetch. Null until then. */
	private volatile HiscoreResult hiscore;

	/** Filled in lazily after the batch clog fetch. Null until then. */
	private volatile ClogResult clog;

	public ClanMember(String rsn, String displayName, String role,
		String rankName, AccountType accountType, String build, long totalXp,
		String lastUpdatedAt, LocalDate joinDate)
	{
		String normalizedRsn = RsnNormalizer.normalize(rsn);
		String normalizedDisplayName = RsnNormalizer.normalize(displayName);
		this.rsn = normalizedRsn;
		this.displayName = normalizedDisplayName.isEmpty() ? normalizedRsn : normalizedDisplayName;
		this.role = role;
		this.rankName = rankName;
		this.accountType = accountType;
		this.build = build;
		this.totalXp = totalXp;
		this.lastUpdatedAt = lastUpdatedAt;
		this.joinDate = joinDate;
	}

	public static ClanMember fromWom(WomMembership ms)
	{
		if (ms == null || ms.player == null)
		{
			return null;
		}
		WomPlayer p = ms.player;
		if (p.username == null)
		{
			return null;
		}
		return new ClanMember(
			p.username,
			p.displayName != null ? p.displayName : p.username,
			ms.role,
			null,
			parseAccountType(p.type),
			p.build,
			p.exp,
			p.updatedAt,
			null);
	}

	/**
	 * Build a {@code ClanMember} from RuneLite's in-game clan data. {@code build}
	 * and {@code totalXp} stay null/0 because the runtime doesn't expose them;
	 * the hiscore fan-out fills account type later. {@code role} prefers the
	 * clan's localized {@code ClanTitle.getName()} (custom rank names the owner
	 * configured in-game) and falls back to the rank enum name.
	 */
	public static ClanMember fromInGame(net.runelite.api.clan.ClanMember member,
		net.runelite.api.clan.ClanSettings settings)
	{
		if (member == null || member.getName() == null)
		{
			return null;
		}
		String rankTitle = null;
		String rawRank = null;
		if (settings != null && member.getRank() != null)
		{
			rawRank = rankToString(member.getRank());
			net.runelite.api.clan.ClanTitle title = settings.titleForRank(member.getRank());
			if (title != null)
			{
				rankTitle = title.getName();
			}
		}
		if (rankTitle == null || rankTitle.isEmpty())
		{
			rankTitle = member.getRank() != null ? member.getRank().toString() : "";
		}
		return new ClanMember(
			member.getName(),
			member.getName(),
			rankTitle,
			rawRank,
			AccountType.REGULAR,
			null,
			0L,
			null,
			member.getJoinDate());
	}

	/**
	 * Map a ClanRank to the backend's expected string. ClanRank is a @Value
	 * class in RuneLite's API, not an enum, so we compare against the static
	 * constants and fall back to "GUEST" for numbered custom ranks.
	 */
	private static String rankToString(ClanRank rank)
	{
		if (ClanRank.OWNER.equals(rank))
		{
			return "OWNER";
		}
		if (ClanRank.DEPUTY_OWNER.equals(rank))
		{
			return "DEPUTY_OWNER";
		}
		if (ClanRank.ADMINISTRATOR.equals(rank))
		{
			return "ADMINISTRATOR";
		}
		if (ClanRank.JMOD.equals(rank))
		{
			return "JMOD";
		}
		return "GUEST";
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

	/**
	 * Raw ClanRank enum name (OWNER, DEPUTY_OWNER, ADMINISTRATOR, GUEST, etc.)
	 * Null for WOM-sourced members (WOM uses its own role strings).
	 */
	public String getRankName()
	{
		return rankName;
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

	public LocalDate getJoinDate()
	{
		return joinDate;
	}

	public HiscoreResult getHiscore()
	{
		return hiscore;
	}

	public void setHiscore(HiscoreResult hiscore)
	{
		this.hiscore = hiscore;
	}

	public ClogResult getClog()
	{
		return clog;
	}

	public void setClog(ClogResult clog)
	{
		this.clog = clog;
	}
}
