package com.clanclog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
				if (adapted != null)
				{
					next.add(adapted);
				}
			}
		}

		cached = Collections.unmodifiableList(next);
		long activeSlots = Arrays.stream(all).filter(Objects::nonNull).count();
		log.debug("clan reader refresh: {} members across {} slots", cached.size(), activeSlots);

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
