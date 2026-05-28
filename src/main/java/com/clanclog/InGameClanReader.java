package com.clanclog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
 * dependency; no rate limits; survives WOM / Temple / killclog.com outages.
 *
 * <p>Cache refresh is driven by the plugin's {@code @Subscribe onWidgetLoaded}
 * hook: when the clan sidepanel opens in-game, the plugin calls
 * {@link #refresh()} on the client thread. Cache is read-mostly so listeners
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
	private final List<Consumer<List<ClanMember>>> listeners = new CopyOnWriteArrayList<>();

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
	 * Safe to call from any thread (EDT, batch callback, etc.).
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
		for (net.runelite.api.clan.ClanMember m : cs.getMembers())
		{
			if (m.getName() != null && m.getName().equalsIgnoreCase(localName))
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

	/**
	 * Read fresh roster data from the live client. MUST be called on the client
	 * thread (RuneLite's Client api is not thread-safe). Listeners are invoked
	 * inline; they should marshal to the EDT themselves if they touch UI.
	 */
	public void refresh()
	{
		ClanSettings[] all = new ClanSettings[] {
			client.getClanSettings(0),
			client.getClanSettings(1),
			client.getGuestClanSettings()
		};

		// Extract the clan display name from the first non-null slot.
		// Slot 0 is the user's primary clan; slot 1 is secondary; guest
		// channel is tertiary. First non-null name wins.
		String name = null;
		for (ClanSettings cs : all)
		{
			if (cs != null && cs.getName() != null && !cs.getName().isEmpty())
			{
				name = cs.getName();
				break;
			}
		}
		clanName = name;

		// Dedup by lowercase RSN across slots. If a user is in multiple
		// clans (primary + guest), the same RSN can appear in multiple
		// slots. Without dedup, the batch fires duplicate hiscore lookups.
		Set<String> seen = new HashSet<>();
		List<ClanMember> next = new ArrayList<>();
		for (ClanSettings cs : all)
		{
			if (cs == null)
			{
				continue;
			}
			for (net.runelite.api.clan.ClanMember m : cs.getMembers())
			{
				ClanMember adapted = ClanMember.fromInGame(m, cs);
				if (adapted != null && seen.add(adapted.getRsn().toLowerCase()))
				{
					next.add(adapted);
				}
			}
		}

		cached = Collections.unmodifiableList(next);

		// Cache local player info on the client thread so EDT callers
		// never touch the Client API directly.
		Player local = client.getLocalPlayer();
		cachedLocalName = local != null ? local.getName() : null;
		cachedKeyRank = resolveKeyRank(all[0], cachedLocalName);

		long activeSlots = Arrays.stream(all).filter(Objects::nonNull).count();
		log.debug("clan reader refresh: {} ({}) across {} slots",
			cached.size(), clanName != null ? clanName : "unnamed", activeSlots);

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
