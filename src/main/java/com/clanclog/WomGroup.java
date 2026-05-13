package com.clanclog;

import java.util.Collections;
import java.util.List;

/**
 * Wise Old Man /v2/groups/{id} response DTO. Gson-deserialized.
 *
 * <p>Minimal projection: only the fields clan clog actually reads. The real
 * response carries dozens more (socialLinks, roleOrders, banner urls, etc.) --
 * Gson ignores unknown JSON keys by default, so extending later costs nothing.
 *
 * <p>Sample shape, captured 2026-05-13 from group 139:
 * <pre>
 * {
 *   "id": 139,
 *   "name": "Exclusive Elite Club",
 *   "memberCount": 11,
 *   "memberships": [
 *     { "role": "moderator",
 *       "player": { "username": "boom", "displayName": "Boom",
 *                   "type": "regular", "build": "main", "exp": 210802606,
 *                   "updatedAt": "2026-05-12T17:16:22.656Z" } },
 *     ...
 *   ]
 * }
 * </pre>
 */
public class WomGroup
{
	public int id;
	public String name;
	public int memberCount;
	public List<WomMembership> memberships;

	public List<WomMembership> getMemberships()
	{
		return memberships != null ? memberships : Collections.emptyList();
	}
}
