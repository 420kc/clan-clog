package com.clanclog;

/**
 * Player record nested inside {@link WomMembership}. WOM's projection of one
 * osrs account.
 *
 * <p>{@code type} values: {@code regular, ironman, hardcore, ultimate, group_ironman,
 * hardcore_group_ironman, unranked_group_ironman}. See {@link ClanMember#parseAccountType}.
 * <p>{@code build} values: {@code main, f2p, lvl3, zerker, def1, hp10}. Display-only.
 */
public class WomPlayer
{
	public String username;
	public String displayName;
	public String type;
	public String build;
	public long exp;
	public String updatedAt;
}
