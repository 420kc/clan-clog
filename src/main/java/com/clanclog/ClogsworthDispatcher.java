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
 * resource at boot, picks an eligible line per event with a short cooldown
 * to prevent immediate repetition, substitutes placeholders, and renders to
 * the local chatbox via {@link ChatMessageType#GAMEMESSAGE} so the player
 * sees it without broadcasting to clan chat.
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
	private static final int COOLDOWN_LINES_PER_EVENT = 3;
	// kc4 hex per style_guide_typography_canon. Prefixes the [Clogsworth]
	// tag so the narration is visibly part of the kc brand family in chat.
	private static final String PREFIX = "<col=ff5700>[Clogsworth]</col> ";

	private final ChatMessageManager chatMessageManager;
	private final ClientThread clientThread;

	private final Map<String, List<Line>> linesByEvent = new HashMap<>();
	private final Map<String, Queue<Integer>> recentByEvent = new HashMap<>();

	@Inject
	public ClogsworthDispatcher(ChatMessageManager chatMessageManager, ClientThread clientThread)
	{
		this.chatMessageManager = chatMessageManager;
		this.clientThread = clientThread;
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
			JsonObject root = new Gson().fromJson(
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
	 */
	public void narrate(String eventType, Map<String, String> placeholders)
	{
		List<Line> available = linesByEvent.get(eventType);
		if (available == null || available.isEmpty())
		{
			log.debug("clogsworth: no lines for event '{}', skipping", eventType);
			return;
		}

		// First pass: eligible AND not on cooldown
		Queue<Integer> recent = recentByEvent.get(eventType);
		List<Integer> eligibleIndices = collectEligible(available, placeholders, recent);

		// Second pass fallback: if cooldown blocked everything, ignore it
		if (eligibleIndices.isEmpty())
		{
			eligibleIndices = collectEligible(available, placeholders, null);
		}

		if (eligibleIndices.isEmpty())
		{
			log.debug("clogsworth: no eligible lines for event '{}' with placeholders {}",
				eventType, placeholders);
			return;
		}

		int pick = eligibleIndices.get(ThreadLocalRandom.current().nextInt(eligibleIndices.size()));
		Line chosen = available.get(pick);
		String rendered = PREFIX + substitute(chosen.text, placeholders);

		if (recent != null)
		{
			recent.offer(pick);
			while (recent.size() > COOLDOWN_LINES_PER_EVENT)
			{
				recent.poll();
			}
		}

		// Chat manager calls must run on the client thread
		clientThread.invoke(() -> chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
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
