package com.clanclog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;

/**
 * Reads the user's clan roster from the RuneLite runtime. Primary roster
 * source per the 2026-05-13 runtime-data-first locked direction.
 *
 * <p>Wraps {@code client.getClanSettings(slot)} for slots 0, 1, and the guest
 * channel. {@code ClanSettings.getMembers()} returns the full offline-inclusive
 * roster of every member -- not just currently-online ones. No external API
 * dependency; no rate limits; survives WOM / Temple / RuneProfile /
 * killclog.com outages.
 *
 * <p>Cache refresh is driven by clan sidepanel loads plus a short startup/login
 * probe in {@link ClanClogPlugin}: if the player already has the clan tab open,
 * the plugin keeps asking the live client for a few game ticks instead of
 * requiring a chat-channel -> clan-tab toggle. Cache is read-mostly so listeners
 * can pick it up off the EDT.
 *
 * <p>Listeners (e.g. the panel) register a {@code Consumer<List<ClanMember>>}
 * to react to refresh events. Listener invocation is best-effort -- thrown
 * runtime exceptions are logged but do not abort the refresh.
 */
@Slf4j
@Singleton
public class InGameClanReader
{
	private final Client client;

	private volatile List<ClanMember> cached = Collections.emptyList();
	private volatile String clanName;
	/** Cached on ClientThread during refresh(). Safe to read from EDT. */
	private volatile String cachedLocalName;
	/** Cached on ClientThread during refresh(). Safe to read from EDT. */
	private volatile String cachedKeyRank;
	/** Cached guest ClanSettings snapshot. Safe to read from EDT. */
	@Nullable
	private volatile RosterSnapshot guestSnapshot;
	private final List<Consumer<List<ClanMember>>> listeners = new CopyOnWriteArrayList<>();

	public static final class RosterSnapshot
	{
		private final String sourceSlot;
		private final String clanName;
		private final List<ClanMember> roster;
		@Nullable
		private final String localPlayerName;
		@Nullable
		private final String localPlayerKeyRank;

		private RosterSnapshot(String sourceSlot, String clanName, List<ClanMember> roster,
			@Nullable String localPlayerName, @Nullable String localPlayerKeyRank)
		{
			this.sourceSlot = sourceSlot;
			this.clanName = clanName;
			this.roster = Collections.unmodifiableList(roster);
			this.localPlayerName = localPlayerName;
			this.localPlayerKeyRank = localPlayerKeyRank;
		}

		public String getSourceSlot()
		{
			return sourceSlot;
		}

		public String getClanName()
		{
			return clanName;
		}

		public List<ClanMember> getRoster()
		{
			return roster;
		}

		@Nullable
		public String getLocalPlayerName()
		{
			return localPlayerName;
		}

		@Nullable
		public String getLocalPlayerKeyRank()
		{
			return localPlayerKeyRank;
		}

		public boolean hasRoster()
		{
			return !roster.isEmpty();
		}
	}

	@Inject
	public InGameClanReader(Client client)
	{
		this.client = client;
	}

	/** Returns the most recently cached roster across all clan slots. Empty if no clan or no refresh yet. */
	public List<ClanMember> currentRoster()
	{
		return cached;
	}

	/** Returns the display name of the user's primary clan, or null if no clan data yet. */
	public String currentClanName()
	{
		return clanName;
	}

	@Nullable
	public RosterSnapshot currentGuestSnapshot()
	{
		RosterSnapshot snapshot = guestSnapshot;
		return snapshot != null && snapshot.hasRoster() ? snapshot : null;
	}

	public void addListener(Consumer<List<ClanMember>> listener)
	{
		if (listener != null)
		{
			listeners.add(listener);
		}
	}

	public void removeListener(Consumer<List<ClanMember>> listener)
	{
		listeners.remove(listener);
	}

	/**
	 * Returns the local player's RSN cached from the last {@link #refresh()}.
	 * Safe to call from any thread (EDT, async callback, etc.).
	 */
	@Nullable
	public String localPlayerName()
	{
		return cachedLocalName;
	}

	/**
	 * Returns the local player's key rank ("OWNER" or "DEPUTY_OWNER") cached
	 * from the last {@link #refresh()}, or null if the player doesn't hold a
	 * key rank. Safe to call from any thread.
	 */
	@Nullable
	public String localPlayerKeyRank()
	{
		return cachedKeyRank;
	}

	/**
	 * Resolve the local player's key rank from the primary clan settings.
	 * Runs on the client thread during {@link #refresh()}.
	 */
	@Nullable
	private static String resolveKeyRank(@Nullable ClanSettings cs, @Nullable String localName)
	{
		if (cs == null || localName == null)
		{
			return null;
		}
		String localKey = RsnNormalizer.cacheKey(localName);
		if (localKey.isEmpty())
		{
			return null;
		}
		for (net.runelite.api.clan.ClanMember m : cs.getMembers())
		{
			if (m.getName() != null && RsnNormalizer.cacheKey(m.getName()).equals(localKey))
			{
				ClanRank rank = m.getRank();
				if (ClanRank.OWNER.equals(rank))
				{
					return "OWNER";
				}
				if (ClanRank.DEPUTY_OWNER.equals(rank))
				{
					return "DEPUTY_OWNER";
				}
				return null;
			}
		}
		return null;
	}

	@Nullable
	private static RosterSnapshot snapshot(String sourceSlot, @Nullable ClanSettings source,
		@Nullable String localName)
	{
		if (source == null)
		{
			return null;
		}
		String name = source.getName() != null && !source.getName().isEmpty()
			? source.getName() : "unnamed clan";
		List<ClanMember> roster = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (net.runelite.api.clan.ClanMember m : source.getMembers())
		{
			ClanMember adapted = ClanMember.fromInGame(m, source);
			if (adapted != null && seen.add(adapted.getRsn().toLowerCase()))
			{
				roster.add(adapted);
			}
		}
		return new RosterSnapshot(sourceSlot, name, roster, localName,
			resolveKeyRank(source, localName));
	}

	@Nullable
	private static RosterSnapshot firstWithRoster(RosterSnapshot... snapshots)
	{
		for (RosterSnapshot snapshot : snapshots)
		{
			if (snapshot != null && snapshot.hasRoster())
			{
				return snapshot;
			}
		}
		for (RosterSnapshot snapshot : snapshots)
		{
			if (snapshot != null)
			{
				return snapshot;
			}
		}
		return null;
	}

	/**
	 * Read fresh roster data from the live client. MUST be called on the client
	 * thread (RuneLite's Client api is not thread-safe). Listeners are invoked
	 * inline; they should marshal to the EDT themselves if they touch UI.
	 */
	public void refresh()
	{
		ClanSettings primary = client.getClanSettings(0);
		ClanSettings secondary = client.getClanSettings(1);
		ClanSettings guest = client.getGuestClanSettings();
		Player local = client.getLocalPlayer();
		String localName = local != null ? local.getName() : null;
		RosterSnapshot primarySnapshot = snapshot("primary", primary, localName);
		RosterSnapshot secondarySnapshot = snapshot("secondary", secondary, localName);
		RosterSnapshot nextGuestSnapshot = snapshot("guest", guest, localName);

		// Pick ONE authoritative display/sync slot rather than merging members
		// across slots. The guest snapshot is cached separately for Dylan's
		// temporary operator import flow; it is never merged into an own-clan
		// roster and never reads guest-channel occupants.
		RosterSnapshot selected = firstWithRoster(primarySnapshot, secondarySnapshot, nextGuestSnapshot);

		clanName = selected != null ? selected.getClanName() : null;
		cached = selected != null ? selected.getRoster() : Collections.emptyList();
		cachedLocalName = localName;
		cachedKeyRank = selected != null ? selected.getLocalPlayerKeyRank() : null;
		guestSnapshot = nextGuestSnapshot;

		log.debug("clan reader refresh: {} members from {} slot ({})",
			cached.size(), selected != null ? selected.getSourceSlot() : "none",
			clanName != null ? clanName : "unnamed");

		for (Consumer<List<ClanMember>> l : listeners)
		{
			try
			{
				l.accept(cached);
			}
			catch (RuntimeException ex)
			{
				log.debug("clan reader listener threw: {}", ex.getMessage());
			}
		}
	}
}
