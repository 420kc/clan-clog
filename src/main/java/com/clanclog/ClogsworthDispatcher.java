package com.clanclog;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/**
 * Clogsworth narration dispatcher. Loads the line library from the classpath
 * resource at boot, picks an eligible line per event, substitutes placeholders,
 * and renders into the player's CLAN CHAT tab with sender = "Clogsworth" so
 * the narration reads as if he is an active clanmate responding to the
 * broadcast.
 *
 * <p>Render is still local-only ({@link ChatMessageType#CLAN_CHAT} queued
 * directly into the chatbox, never sent through the OSRS chat protocol).
 * Each installed plugin renders independently into its own player's chatbox.
 * Players without Clan Clog see nothing; players with Clan Clog all see
 * Clogsworth in clan chat. No broadcast, no impersonation, no ToS risk.
 *
 * <p>Line pick is deterministic when a source-message seed is provided
 * (hash of the source chat-message + event type seeds the pick), so all
 * installed clanmates responding to the same source broadcast land on the
 * SAME Clogsworth line at the SAME moment. Shared experience without any
 * actual coordination. Per dyl 2026-05-19: "way more fun if he's an active
 * chat member who everyone sees the same thing of."
 *
 * <p>Per project_clan_hiscores_plugin.md (locked 2026-05-19): v1 ships
 * event-triggered narration only for joined / left / kicked. Ambient
 * narrations (silence detection, milestones, anniversaries) are captured as
 * v2 in the same canon file.
 *
 * <p>Placeholder substitution: lines use {@code <rsn>} / {@code <actor>} /
 * {@code <owner>} / {@code <deputy>} / {@code <last_joined>} /
 * {@code <member_count>} / {@code <clan_name>}. Lines with a {@code requires}
 * tag only enter the pick pool when all required placeholders are non-null.
 */
@Slf4j
@Singleton
public class ClogsworthDispatcher
{
	private static final String LIBRARY_RESOURCE = "/com/clanclog/clogsworth-lines.json";
	private static final String SENDER_NAME = "Clogsworth";
	// Cooldown only applies in random-pick mode (no seed). Deterministic
	// mode (seeded by source message) intentionally skips cooldown so all
	// installed plugins pick the same line for the same source broadcast.
	private static final int COOLDOWN_LINES_PER_EVENT = 3;

	private final ChatMessageManager chatMessageManager;
	private final ClientThread clientThread;
	private final Gson gson;

	private final Map<String, List<Line>> linesByEvent = new HashMap<>();
	private final Map<String, Queue<Integer>> recentByEvent = new HashMap<>();

	@Inject
	public ClogsworthDispatcher(ChatMessageManager chatMessageManager, ClientThread clientThread,
		Gson gson)
	{
		this.chatMessageManager = chatMessageManager;
		this.clientThread = clientThread;
		this.gson = gson;
		loadLibrary();
	}

	private void loadLibrary()
	{
		try (InputStream in = ClogsworthDispatcher.class.getResourceAsStream(LIBRARY_RESOURCE))
		{
			if (in == null)
			{
				log.warn("clogsworth: line library resource not found at {}", LIBRARY_RESOURCE);
				return;
			}
			JsonObject root = gson.fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);

			for (Map.Entry<String, JsonElement> entry : root.entrySet())
			{
				String eventType = entry.getKey();
				if (eventType.startsWith("_"))
				{
					// underscore-prefixed keys are metadata (_meta etc.), skip
					continue;
				}
				JsonArray arr = entry.getValue().getAsJsonArray();
				List<Line> list = new ArrayList<>(arr.size());
				for (int i = 0; i < arr.size(); i++)
				{
					JsonObject obj = arr.get(i).getAsJsonObject();
					String text = obj.get("text").getAsString();
					List<String> requires = new ArrayList<>();
					if (obj.has("requires"))
					{
						for (JsonElement req : obj.get("requires").getAsJsonArray())
						{
							requires.add(req.getAsString());
						}
					}
					list.add(new Line(text, requires));
				}
				linesByEvent.put(eventType, list);
				recentByEvent.put(eventType, new LinkedList<>());
				log.debug("clogsworth: loaded {} lines for event '{}'", list.size(), eventType);
			}
		}
		catch (Exception ex)
		{
			log.warn("clogsworth: failed to load line library: {}", ex.getMessage());
		}
	}

	/**
	 * Render a Clogsworth line for the given event type, substituting any
	 * available placeholders. Silent no-op if no eligible lines exist or the
	 * library failed to load.
	 *
	 * @param sourceSeed when non-null, the line pick is deterministic across
	 *                   all installed plugins responding to the same source
	 *                   broadcast (hash-seeded into the eligible pool, no
	 *                   cooldown applied). When null, falls back to random
	 *                   pick with cooldown. ChatScanner passes the raw source
	 *                   chat message text for event-triggered narrations.
	 */
	public void narrate(String eventType, Map<String, String> placeholders, String sourceSeed)
	{
		List<Line> available = linesByEvent.get(eventType);
		if (available == null || available.isEmpty())
		{
			log.debug("clogsworth: no lines for event '{}', skipping", eventType);
			return;
		}

		final boolean deterministic = sourceSeed != null && !sourceSeed.isEmpty();
		Queue<Integer> recent = deterministic ? null : recentByEvent.get(eventType);

		List<Integer> eligibleIndices = collectEligible(available, placeholders, recent);
		// Fallback: if cooldown blocked everything in random mode, ignore it
		if (eligibleIndices.isEmpty() && !deterministic)
		{
			eligibleIndices = collectEligible(available, placeholders, null);
		}

		if (eligibleIndices.isEmpty())
		{
			log.debug("clogsworth: no eligible lines for event '{}' with placeholders {}",
				eventType, placeholders);
			return;
		}

		int pick;
		if (deterministic)
		{
			// Deterministic hash so every plugin instance picks the same line
			// for the same source broadcast. String.hashCode is JVM-stable.
			int seed = (eventType + "" + sourceSeed).hashCode();
			int idx = Math.floorMod(seed, eligibleIndices.size());
			pick = eligibleIndices.get(idx);
		}
		else
		{
			pick = eligibleIndices.get(ThreadLocalRandom.current().nextInt(eligibleIndices.size()));
			if (recent != null)
			{
				recent.offer(pick);
				while (recent.size() > COOLDOWN_LINES_PER_EVENT)
				{
					recent.poll();
				}
			}
		}

		Line chosen = available.get(pick);
		String rendered = substitute(chosen.text, placeholders);

		// Queue into the player's CLAN CHAT tab with sender = "Clogsworth"
		// so it reads as an active clanmate response in-game. Local-only;
		// other players' chatboxes are unaffected. Each plugin instance
		// renders independently into its own player's clan chat.
		clientThread.invoke(() -> chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CLAN_CHAT)
			.name(SENDER_NAME)
			.runeLiteFormattedMessage(rendered)
			.build()));
	}

	private static List<Integer> collectEligible(List<Line> lines, Map<String, String> placeholders, Queue<Integer> recentToSkip)
	{
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++)
		{
			if (recentToSkip != null && recentToSkip.contains(i))
			{
				continue;
			}
			Line line = lines.get(i);
			boolean ok = true;
			for (String req : line.requires)
			{
				if (placeholders == null || placeholders.get(req) == null)
				{
					ok = false;
					break;
				}
			}
			if (ok)
			{
				out.add(i);
			}
		}
		return out;
	}

	private static String substitute(String text, Map<String, String> placeholders)
	{
		if (placeholders == null || placeholders.isEmpty())
		{
			return text;
		}
		String out = text;
		for (Map.Entry<String, String> e : placeholders.entrySet())
		{
			if (e.getValue() != null)
			{
				out = out.replace("<" + e.getKey() + ">", e.getValue());
			}
		}
		return out;
	}

	/** Test-only helper: report how many lines were loaded per event type. */
	public Map<String, Integer> libraryStats()
	{
		Map<String, Integer> out = new HashMap<>();
		for (Map.Entry<String, List<Line>> e : linesByEvent.entrySet())
		{
			out.put(e.getKey(), e.getValue().size());
		}
		return out;
	}

	private static final class Line
	{
		final String text;
		final List<String> requires;

		Line(String text, List<String> requires)
		{
			this.text = text;
			this.requires = requires;
		}
	}
}
