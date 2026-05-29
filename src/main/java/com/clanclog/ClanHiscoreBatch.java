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
 * Fan-out wrapper around {@link HiscoreService} for clan-roster fetches.
 *
 * <p>Takes a roster ({@code List<ClanMember>}), fires {@code HiscoreService.lookup}
 * per member with a bounded concurrency window, and fills
 * {@link ClanMember#setHiscore} as each result lands. Returns a
 * {@code CompletableFuture<Void>} that completes once every member has either
 * a populated hiscore or has resolved to null.
 *
 * <p>Each {@code HiscoreService.lookup} call fires 4 hiscore endpoints in parallel
 * (UIM / HCIM / Iron / Reg). To respect Jagex's tolerance for sustained traffic,
 * this batcher caps concurrent {@code lookup} calls -- inflight HTTP requests
 * stay at roughly {@code 4 * concurrency}.
 *
 * <p>Per-member failures are intentionally swallowed: a single timed-out
 * lookup must not abort the whole batch. The panel reads {@code getHiscore()}
 * and treats null as "data unavailable for this member".
 */
@Slf4j
@Singleton
public class ClanHiscoreBatch
{
	/**
	 * Concurrency cap. 3 in-flight lookups keeps us well under Jagex's
	 * rate limit for sustained bursts. Combined with the stagger delay,
	 * effective rate is ~3 req/s which survives 100+ member rosters.
	 */
	private static final int DEFAULT_CONCURRENCY = 3;

	/**
	 * Milliseconds between submitting each lookup to the scheduler.
	 * Spreads HTTP requests over time so Jagex doesn't 404 us after
	 * the first ~30 members. 300ms * 106 members = ~32s total, which
	 * is acceptable for a one-time batch that populates a 1h cache.
	 */
	private static final long STAGGER_DELAY_MS = 300;

	/**
	 * Per-acquire wait. If the slice cannot acquire a slot within this window,
	 * something has gone wrong upstream -- bail rather than wedge the EDT.
	 */
	private static final long ACQUIRE_TIMEOUT_SECONDS = 30;
	private static final long LOOKUP_TIMEOUT_SECONDS = 15;

	private final HiscoreService hiscoreService;
	private final LocalHiscoreCache hiscoreCache;
	private volatile ScheduledExecutorService scheduler = newScheduler();

	@Inject
	public ClanHiscoreBatch(HiscoreService hiscoreService, LocalHiscoreCache hiscoreCache)
	{
		this.hiscoreService = hiscoreService;
		this.hiscoreCache = hiscoreCache;
	}

	private static ScheduledExecutorService newScheduler()
	{
		return Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "clan-clog-batch");
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * Fan out hiscore lookups across {@code roster}.
	 *
	 * @param roster      members to fill -- {@link ClanMember#setHiscore} is mutated on each.
	 * @param onProgress  optional callback fired on the scheduler thread after each
	 *                    member resolves; receives the running completed count.
	 *                    null skips progress reporting.
	 * @return            future completing when every member has resolved (success or failure).
	 */
	public CompletableFuture<Void> fetchAll(List<ClanMember> roster, IntConsumer onProgress)
	{
		return fetchAll(roster, DEFAULT_CONCURRENCY, onProgress);
	}

	public CompletableFuture<Void> fetchAll(List<ClanMember> roster, int concurrency, IntConsumer onProgress)
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
					member.setHiscore(result);
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
						log.debug("onProgress callback threw: {}", progressEx.getMessage());
					}
				}
			});
		}
		return CompletableFuture.allOf(perMember);
	}

	private CompletableFuture<HiscoreResult> lookupOne(ClanMember member, Semaphore gate, long delayMs)
	{
		CompletableFuture<HiscoreResult> out = new CompletableFuture<>();

		// Stale-while-revalidate: if a cached result exists and isn't stale,
		// return it immediately without hitting Jagex at all. If stale or
		// missing, fetch fresh data (still uses only the regular endpoint).
		HiscoreResult cached = hiscoreCache.get(member.getRsn());
		if (cached != null && !hiscoreCache.isStale(member.getRsn()))
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
					log.debug("Batch slot timeout for {}, skipping", member.getRsn());
					// Fall back to stale cached data if available
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

			// Clan aggregate only needs the regular hiscore endpoint (1 request
			// per member instead of 4). Account type detection is unnecessary
			// since every player appears on the regular hiscores regardless of
			// iron status. Cuts total HTTP requests by 75%.
			try
			{
				hiscoreService.lookupRegularOnly(member.getRsn())
					.orTimeout(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
					.whenComplete((result, ex) ->
					{
						gate.release();
						if (ex != null)
						{
							log.debug("Lookup failed for {}: {}", member.getRsn(), ex.getMessage());
							out.complete(cached);
						}
						else if (result != null)
						{
							hiscoreCache.put(member.getRsn(), result);
							out.complete(result);
						}
						else
						{
							log.debug("Lookup returned null for {} (rate-limited?)", member.getRsn());
							out.complete(cached);
						}
					});
			}
			catch (Exception e)
			{
				gate.release();
				log.debug("Lookup threw for {}: {}", member.getRsn(), e.getMessage());
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
