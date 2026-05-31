package com.clanclog;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Reports verified own-clan roster deltas observed in-game to killclog.com.
 *
 * <p>Passive events are narrower than the manual sync button: they only carry
 * one joined/left/kicked mutation. They still use the same authority posture:
 * sync must be enabled, a current in-game roster must be cached, and the local
 * player must be OWNER or DEPUTY_OWNER for that roster.
 */
@Slf4j
@Singleton
public class ClanEventReporter
{
	private final ClanClogConfig config;
	private final InGameClanReader clanReader;
	private final KillclogApiClient apiClient;

	@Inject
	public ClanEventReporter(ClanClogConfig config, InGameClanReader clanReader,
		KillclogApiClient apiClient)
	{
		this.config = config;
		this.clanReader = clanReader;
		this.apiClient = apiClient;
	}

	public void report(String eventType, String targetRsn)
	{
		EventRequest request = buildRequest(
			config.enableSync(),
			clanReader.currentClanName(),
			clanReader.localPlayerName(),
			clanReader.localPlayerKeyRank(),
			clanReader.currentRoster(),
			eventType,
			targetRsn,
			Instant.now());
		if (request == null)
		{
			log.debug("clan roster event skipped: type={} target={}", eventType, targetRsn);
			return;
		}

		CompletableFuture<KillclogApiClient.SyncResponse> future =
			apiClient.postClanEvent(request.slug, request.eventType, request.targetRsn,
				request.reporterRsn, request.observedAt);
		future.whenComplete((resp, ex) ->
		{
			if (ex != null)
			{
				log.debug("clan roster event failed: type={} target={} error={}",
					request.eventType, request.targetRsn, ex.getMessage());
			}
			else if (resp == null || !resp.isOk())
			{
				log.debug("clan roster event rejected: type={} target={} detail={}",
					request.eventType, request.targetRsn,
					resp != null ? resp.describe() : "no response");
			}
			else
			{
				log.debug("clan roster event accepted: type={} target={}{}",
					request.eventType, request.targetRsn, resp.describeSuccess());
			}
		});
	}

	@Nullable
	static EventRequest buildRequest(boolean syncEnabled, @Nullable String clanName,
		@Nullable String localPlayerName, @Nullable String readerKeyRank,
		@Nullable List<ClanMember> roster, String eventType, String targetRsn,
		Instant observedAt)
	{
		if (!syncEnabled || !isEventType(eventType) || roster == null || roster.isEmpty())
		{
			return null;
		}
		String slug = slugify(clanName);
		String target = RsnNormalizer.normalize(targetRsn);
		if (slug.isEmpty() || target.isEmpty())
		{
			return null;
		}

		String keyRank = ClanClogPanel.isSyncKeyRank(readerKeyRank)
			? readerKeyRank : ClanClogPanel.syncKeyRank(localPlayerName, roster);
		String reporter = rosterMemberRsn(localPlayerName, roster);
		if (keyRank == null || reporter == null || reporter.isEmpty())
		{
			return null;
		}
		return new EventRequest(slug, eventType, target, reporter, observedAt.toString());
	}

	private static boolean isEventType(String eventType)
	{
		return "joined".equals(eventType)
			|| "left".equals(eventType)
			|| "kicked".equals(eventType);
	}

	@Nullable
	private static String rosterMemberRsn(@Nullable String localPlayerName, List<ClanMember> roster)
	{
		String localKey = RsnNormalizer.cacheKey(localPlayerName);
		if (localKey.isEmpty())
		{
			return null;
		}
		for (ClanMember member : roster)
		{
			if (member != null && RsnNormalizer.cacheKey(member.getRsn()).equals(localKey))
			{
				return member.getRsn();
			}
		}
		return null;
	}

	private static String slugify(@Nullable String name)
	{
		if (name == null)
		{
			return "";
		}
		return name.toLowerCase()
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("^-+|-+$", "");
	}

	static final class EventRequest
	{
		final String slug;
		final String eventType;
		final String targetRsn;
		final String reporterRsn;
		final String observedAt;

		EventRequest(String slug, String eventType, String targetRsn,
			String reporterRsn, String observedAt)
		{
			this.slug = slug;
			this.eventType = eventType;
			this.targetRsn = targetRsn;
			this.reporterRsn = reporterRsn;
			this.observedAt = observedAt;
		}
	}
}
