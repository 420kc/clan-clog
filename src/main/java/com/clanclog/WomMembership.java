package com.clanclog;

/**
 * One row in {@link WomGroup#memberships}. Pairs a player with their clan role.
 *
 * <p>{@code role} is a free-form string from WOM. Values seen in real responses:
 * {@code owner, deputy_owner, overseer, coordinator, helper, member}, plus the
 * non-game custom roles WOM allows ({@code moderator, artisan, legend, medic}).
 * No enum mapping here -- the panel renders the string as-is, prettified.
 */
public class WomMembership
{
	public String role;
	public WomPlayer player;
}
