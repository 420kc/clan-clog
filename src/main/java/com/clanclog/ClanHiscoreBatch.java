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
	 * Concurrency cap. 6 in-flight lookups * 4 endpoints each = ~24 inflight HTTP
	 * requests, which matches Jagex's documented tolerance for casual clients.
	 * Configurable later if a busy clan exhausts the budget.
	 */
	private static final int DEFAULT_CONCURRENCY = 6;

	/**
	 * Per-acquire wait. If the slice cannot acquire a slot within this window,
	 * something has gone wrong upstream -- bail rather than wedge the EDT.
	 */
	private static final long ACQUIRE_TIMEOUT_SECONDS = 30;

	private final HiscoreService hiscoreService;
	private final ScheduledExecutorService scheduler;

	@Inject
	public ClanHiscoreBatch(HiscoreService hiscoreService)
	{
		this.hiscoreService = hiscoreService;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r ->
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
			perMember[i] = lookupOne(member, gate).whenComplete((result, ex) ->
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

	private CompletableFuture<HiscoreResult> lookupOne(ClanMember member, Semaphore gate)
	{
		CompletableFuture<HiscoreResult> out = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			try
			{
				if (!gate.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
				{
					log.debug("Batch slot timeout for {}, skipping", member.getRsn());
					out.complete(null);
					return;
				}
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				out.complete(null);
				return;
			}

			hiscoreService.lookup(member.getRsn(), member.getAccountType())
				.whenComplete((result, ex) ->
				{
					gate.release();
					if (ex != null)
					{
						log.debug("Lookup failed for {}: {}", member.getRsn(), ex.getMessage());
						out.complete(null);
					}
					else
					{
						out.complete(result);
					}
				});
		});
		return out;
	}

	public void shutdown()
	{
		scheduler.shutdownNow();
	}
}
