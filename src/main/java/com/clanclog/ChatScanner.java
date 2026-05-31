package com.clanclog;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;

/**
 * Listens for modern OSRS Clan-system chatbox events (joined / left / kicked)
 * and dispatches Clogsworth narrations. Own-clan events also POST to the
 * killclog-api {@code /api/clan/<slug>/events} endpoint when sync is enabled
 * and the local player has key-rank authority.
 *
 * <p>Wired by {@link ClanClogPlugin} which holds the {@code @Subscribe}
 * dispatch and delegates here so this class stays a pure event-parser.
 *
 * <p>Regex patterns target the modern OSRS Clan broadcast strings. Exact
 * wording may need adjustment after live verification in the RuneLite client
 * (the in-game text varies subtly between clan tiers and game updates). All
 * pattern matches use a stripped-token form of the message; the bare RSN is
 * what gets passed to the dispatcher. v2 polish: preserve source-message
 * formatting tokens around the RSN so Clogsworth's narration mirrors the
 * in-game styling.
 *
 * <p>Per project_clan_hiscores_plugin.md (locked 2026-05-19) v1 scope: chat
 * scanner drives both Clogsworth narration and (next sub-phase) backend
 * roster sync.
 */
@Slf4j
@Singleton
public class ChatScanner
{
	// "X has been invited into your clan by Y" + close variants.
	private static final Pattern JOINED = Pattern.compile(
		"^(.+?) has been invited into (?:your |the )?clan by (.+?)\\.?$");
	// "X has joined your clan" + variants (some game flows fire this instead).
	private static final Pattern JOINED_NO_ACTOR = Pattern.compile(
		"^(.+?) has joined (?:your |the )?clan\\.?$");
	// "X has left your clan."
	private static final Pattern LEFT = Pattern.compile(
		"^(.+?) has left (?:your |the )?clan\\.?$");
	// "X has been kicked from your clan" optionally with " by Y".
	private static final Pattern KICKED = Pattern.compile(
		"^(.+?) has been kicked from (?:your |the )?clan(?: by (.+?))?\\.?$");

	private final ClogsworthDispatcher clogsworth;
	private final ClanEventReporter eventReporter;

	@Inject
	public ChatScanner(ClogsworthDispatcher clogsworth, ClanEventReporter eventReporter)
	{
		this.clogsworth = clogsworth;
		this.eventReporter = eventReporter;
	}

	public void handle(ChatMessage event)
	{
		if (event == null)
		{
			return;
		}
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.CLAN_MESSAGE && type != ChatMessageType.CLAN_GUEST_MESSAGE)
		{
			return;
		}

		String raw = event.getMessage();
		if (raw == null || raw.isEmpty())
		{
			return;
		}
		String stripped = stripTokens(raw).trim();

		boolean reportBackend = type == ChatMessageType.CLAN_MESSAGE;
		Matcher m = JOINED.matcher(stripped);
		if (m.matches())
		{
			dispatch("joined", m.group(1), m.group(2), reportBackend);
			return;
		}
		m = JOINED_NO_ACTOR.matcher(stripped);
		if (m.matches())
		{
			dispatch("joined", m.group(1), null, reportBackend);
			return;
		}
		m = LEFT.matcher(stripped);
		if (m.matches())
		{
			dispatch("left", m.group(1), null, reportBackend);
			return;
		}
		m = KICKED.matcher(stripped);
		if (m.matches())
		{
			dispatch("kicked", m.group(1), m.group(2), reportBackend);
			return;
		}
	}

	private void dispatch(String eventType, String rsn, String actor, boolean reportBackend)
	{
		if (rsn == null)
		{
			return;
		}
		String rsnClean = rsn.trim();
		String actorClean = actor != null ? actor.trim() : null;

		Map<String, String> placeholders = new HashMap<>();
		placeholders.put("rsn", rsnClean);
		if (actorClean != null && !actorClean.isEmpty())
		{
			placeholders.put("actor", actorClean);
		}

		log.debug("clan event: type={} rsn={} actor={}", eventType, rsnClean, actorClean);
		// Pass the rsn + event type combo as the deterministic seed so all
		// installed plugins responding to the same source broadcast pick the
		// same line. Stable across JVMs (String.hashCode is spec-stable).
		// Using the rsn-and-type composite rather than the raw source text
		// because the raw text may include per-player chat-formatting tokens
		// that vary slightly across clients.
		String seed = eventType + ":" + rsnClean.toLowerCase() + (actorClean != null ? ":" + actorClean.toLowerCase() : "");
		clogsworth.narrate(eventType, placeholders, seed);
		if (reportBackend)
		{
			eventReporter.report(eventType, rsnClean);
		}
	}

	/** Strip RuneLite chat format tokens like &lt;col=...&gt;...&lt;/col&gt; and &lt;img=N&gt;. */
	private static String stripTokens(String s)
	{
		return s.replaceAll("<[^>]+>", "");
	}
}
