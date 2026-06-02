package com.clanclog;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parsed collection log data for a player from any supported source:
 * local client cache, TempleOSRS, or RuneProfile.
 */
public class ClogResult
{
	/** Canonical player name with the best casing returned by the source. */
	private final String playerName;
	/** category key -> list of obtained items with counts */
	private final Map<String, List<ClogItem>> obtainedItems;
	/** category key -> all item IDs in that category */
	private final Map<String, List<Integer>> categoryItems;
	/** item IDs whose names have been resolved (concurrent: written from client thread, read from EDT) */
	private final Set<Integer> resolvedItemIds;
	/** When the source last reported a collection-log sync, or null if unavailable. */
	private final String lastChanged;
	/** Account type carried by provider metadata, or null if unknown. */
	private final AccountType templeAccountType;
	/** Game-reported unique obtained count (varp 2943), or -1 if unavailable */
	private int uniqueObtained = -1;
	/** Game-reported total clog slots (varp 2944), or -1 if unavailable */
	private int uniqueTotal = -1;

	public ClogResult(
		String playerName,
		Map<String, List<ClogItem>> obtainedItems,
		Map<String, List<Integer>> categoryItems,
		Map<Integer, String> itemNames,
		String lastChanged,
		AccountType templeAccountType)
	{
		this.playerName = playerName;
		this.obtainedItems = obtainedItems;
		this.categoryItems = categoryItems;
		this.resolvedItemIds = ConcurrentHashMap.newKeySet();
		if (itemNames != null)
		{
			resolvedItemIds.addAll(itemNames.keySet());
		}
		this.lastChanged = lastChanged;
		this.templeAccountType = templeAccountType;
	}

	public String getPlayerName()
	{
		return playerName;
	}

	public String getLastChanged()
	{
		return lastChanged;
	}

	public Map<String, List<ClogItem>> getObtainedItems()
	{
		return obtainedItems;
	}

	public Map<String, List<Integer>> getCategoryItems()
	{
		return categoryItems;
	}

	public AccountType getTempleAccountType()
	{
		return templeAccountType;
	}

	ClogResult withFallbackAccountTypeFrom(ClogResult fallback)
	{
		if (templeAccountType != null || fallback == null || fallback.templeAccountType == null)
		{
			return this;
		}

		ClogResult result = new ClogResult(
			playerName,
			obtainedItems,
			categoryItems,
			null,
			lastChanged,
			fallback.templeAccountType);
		result.resolvedItemIds.addAll(resolvedItemIds);
		result.uniqueObtained = uniqueObtained;
		result.uniqueTotal = uniqueTotal;
		return result;
	}

	public int getUniqueObtained()
	{
		return uniqueObtained;
	}

	public void setUniqueObtained(int count)
	{
		this.uniqueObtained = count;
	}

	public int getUniqueTotal()
	{
		return uniqueTotal;
	}

	public void setUniqueTotal(int count)
	{
		this.uniqueTotal = count;
	}

	public boolean isItemResolved(int id)
	{
		return resolvedItemIds.contains(id);
	}

	public void markItemResolved(int id)
	{
		resolvedItemIds.add(id);
	}

	public static class ClogItem
	{
		private final int id;
		private final int count;
		private final String date;

		public ClogItem(int id, int count, String date)
		{
			this.id = id;
			this.count = count;
			this.date = date;
		}

		public int getId()
		{
			return id;
		}

		public int getCount()
		{
			return count;
		}

		public String getDate()
		{
			return date;
		}
	}
}
