package com.clanclog;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Fan-out wrapper around {@link ClogFetchService} for clan-roster clog fetches.
 *
 * <p>Takes a roster ({@code List<ClanMember>}), fires
 * {@code ClogFetchService.lookup} per member with bounded concurrency, and
 * fills {@link ClanMember#setClog} as each result lands. Returns a
 * {@code CompletableFuture<Void>} that completes once every member has either
 * a populated clog or has resolved to null.
 *
 * <p>Mirrors {@link ClanHiscoreBatch} but tuned for external-API rate limits
 * (Temple + RuneProfile are stricter than Jagex hiscores). Default concurrency
 * of 3 keeps outbound HTTP at roughly 6 in-flight requests (Temple + RuneProfile
 * fallback per member).
 *
 * <p>Per-member failures are intentionally swallowed: a single timed-out lookup
 * must not abort the whole batch. The panel reads {@code getClog()} and treats
 * null as "clog data unavailable for this member".
 */
@Slf4j
@Singleton
public class ClanClogBatch
{
	/**
	 * Concurrency cap. 2 in-flight lookups (each may hit Temple then RuneProfile
	 * as fallback) keeps total HTTP at ~4, well within Temple/RuneProfile
	 * tolerance for sustained traffic.
	 */
	private static final int DEFAULT_CONCURRENCY = 2;

	/** Per-acquire wait before bailing on this member. */
	private static final long ACQUIRE_TIMEOUT_SECONDS = 60;
	private static final long LOOKUP_TIMEOUT_SECONDS = 30;

	/**
	 * Milliseconds between submitting each lookup. Temple and RuneProfile are
	 * stricter than Jagex, so we space requests further apart. 400ms * N members
	 * keeps us under their sustained-burst thresholds.
	 */
	private static final long STAGGER_DELAY_MS = 400;

	private final ClogFetchService clogFetchService;
	private volatile ScheduledExecutorService scheduler = newScheduler();

	@Inject
	public ClanClogBatch(ClogFetchService clogFetchService)
	{
		this.clogFetchService = clogFetchService;
	}

	private static ScheduledExecutorService newScheduler()
	{
		return Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "clan-clog-clog-batch");
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * Fan out clog lookups across {@code roster}.
	 *
	 * @param roster      members to fill -- {@link ClanMember#setClog} is mutated on each.
	 * @param onProgress  optional callback fired after each member resolves;
	 *                    receives the running completed count. null skips.
	 * @return            future completing when every member has resolved.
	 */
	public CompletableFuture<Void> fetchAll(List<ClanMember> roster, IntConsumer onProgress)
	{
		return fetchAll(roster, DEFAULT_CONCURRENCY, onProgress);
	}

	public CompletableFuture<Void> fetchAll(List<ClanMember> roster, int concurrency,
		IntConsumer onProgress)
	{
		if (roster == null || roster.isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}

		final Semaphore gate = new Semaphore(Math.max(1, concurrency));
		final AtomicInteger completed = new AtomicInteger();
		final int total = roster.size();

		CompletableFuture<?>[] perMember = new CompletableFuture<?>[total];
		for (int i = 0; i < total; i++)
		{
			final ClanMember member = roster.get(i);
			long delay = (long) i * STAGGER_DELAY_MS;
			perMember[i] = lookupOne(member, gate, delay).whenComplete((result, ex) ->
			{
				if (result != null)
				{
					member.setClog(result);
				}
				int done = completed.incrementAndGet();
				if (onProgress != null)
				{
					try
					{
						onProgress.accept(done);
					}
					catch (RuntimeException progressEx)
					{
						log.debug("clog onProgress threw: {}", progressEx.getMessage());
					}
				}
			});
		}
		return CompletableFuture.allOf(perMember);
	}

	private CompletableFuture<ClogResult> lookupOne(ClanMember member, Semaphore gate, long delayMs)
	{
		CompletableFuture<ClogResult> out = new CompletableFuture<>();

		// Return cached clog data immediately if it exists. Clog data is
		// persistent disk cache (collection log items change very rarely).
		// Skips 2 external HTTP calls per member (Temple + RuneProfile).
		ClogResult cached = clogFetchService.getCached(member.getRsn());
		if (cached != null)
		{
			out.complete(cached);
			return out;
		}

		scheduler.schedule(() ->
		{
			try
			{
				if (!gate.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
				{
					log.debug("Clog batch slot timeout for {}, skipping", member.getRsn());
					out.complete(cached);
					return;
				}
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				out.complete(cached);
				return;
			}

			try
			{
				clogFetchService.lookup(member.getRsn())
					.orTimeout(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
					.whenComplete((result, ex) ->
					{
						gate.release();
						if (ex != null)
						{
							log.debug("Clog lookup failed for {}: {}", member.getRsn(), ex.getMessage());
							out.complete(cached);
						}
						else
						{
							out.complete(result);
						}
					});
			}
			catch (Exception e)
			{
				gate.release();
				log.debug("Clog lookup threw for {}: {}", member.getRsn(), e.getMessage());
				out.complete(cached);
			}
		}, delayMs, TimeUnit.MILLISECONDS);
		return out;
	}

	public void shutdown()
	{
		ScheduledExecutorService old = scheduler;
		scheduler = newScheduler();
		old.shutdownNow();
	}
}
