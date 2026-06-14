package com.clanclog;

import java.util.List;
import java.util.Map;
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
	/** item id -> display name, concurrent: written from client thread, read from EDT. */
	private final Map<Integer, String> itemNames;
	/** When the source last reported a collection-log sync, or null if unavailable. */
	private final String lastChanged;
	/** Account type carried by provider metadata, or null if unknown. */
	private final AccountType providerAccountType;
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
		AccountType providerAccountType)
	{
		this.playerName = playerName;
		this.obtainedItems = obtainedItems;
		this.categoryItems = categoryItems;
		this.itemNames = new ConcurrentHashMap<>();
		if (itemNames != null)
		{
			this.itemNames.putAll(itemNames);
		}
		this.lastChanged = lastChanged;
		this.providerAccountType = providerAccountType;
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

	public AccountType getProviderAccountType()
	{
		return providerAccountType;
	}

	ClogResult withFallbackAccountTypeFrom(ClogResult fallback)
	{
		if (providerAccountType != null || fallback == null || fallback.providerAccountType == null)
		{
			return this;
		}

		ClogResult result = new ClogResult(
			playerName,
			obtainedItems,
			categoryItems,
			null,
			lastChanged,
			fallback.providerAccountType);
		result.itemNames.putAll(itemNames);
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
		return itemNames.containsKey(id);
	}

	public void markItemResolved(int id)
	{
		itemNames.putIfAbsent(id, "Item " + id);
	}

	public void markItemResolved(int id, String name)
	{
		if (name == null || name.isBlank() || "null".equalsIgnoreCase(name))
		{
			return;
		}
		itemNames.put(id, name);
	}

	public String getItemName(int id)
	{
		return itemNames.get(id);
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
