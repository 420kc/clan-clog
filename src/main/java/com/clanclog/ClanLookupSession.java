package com.clanclog;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;

/**
 * Adapted from kcpdev LookupSession (refactor-cells-collapse branch, ~280 LOC)
 * for the clan-aggregate use case. One backend call per lookup, version-
 * stamped against stale results.
 *
 * <p>Differences from the kcpdev per-player LookupSession:
 * <ul>
 *   <li>Single async call (backend /api/clan/<slug>/clog) instead of parallel
 *       hiscores + clog API fan-out. The backend already did the fan-out via
 *       its reducer (3a + 3c on track-c-clan-clog); the plugin just reads.</li>
 *   <li>No first-self-greeting flow (clan profile isn't "your own", it's the
 *       clan's combined surface).</li>
 *   <li>No knownType (account types are per-member, surfaced inside the
 *       result, not at the session level).</li>
 *   <li>Caching deferred: relies on the backend's own cache layer + Cloudflare
 *       edge for read TTL. A per-clan in-plugin cache can be added later.</li>
 * </ul>
 *
 * <p>Per project_clan_hiscores_plugin.md Tab 1 design sketch (3b.3.2):
 * Listener invocations all happen on the EDT via SwingUtilities.invokeLater.
 * Background result threads bridge to EDT before firing listener callbacks.
 * The volatile version stamp gates stale results so an in-flight call
 * superseded by a newer {@link #start} cannot overwrite the UI state.
 */
@Slf4j
@Singleton
public class ClanLookupSession
{
	/**
	 * Lifecycle events from a clan lookup. All methods are invoked on the EDT.
	 */
	public interface Listener
	{
		/** Lookup begun: UI should reset state, set loading indicator, post a search-status line. */
		void onClanLookupStart(String slug);

		/** Live result arrived from the backend. */
		void onClanResult(String slug, ClanClogResult result);

		/** Backend returned 404 for the clan slug. UI surfaces "no clan found". */
		void onClanNotFound(String slug);

		/** Backend errored or was unreachable. {@code detail} may be null. */
		void onClanError(String slug, @Nullable String detail);
	}

	private final KillclogApiClient apiClient;

	private volatile int currentVersion;
	private volatile String currentSlug;

	@Inject
	public ClanLookupSession(KillclogApiClient apiClient)
	{
		this.apiClient = apiClient;
	}

	/**
	 * Kick off a clan lookup. Must be called from the EDT. Subsequent calls
	 * invalidate any in-flight call's listener payload via the version stamp.
	 */
	public void start(String slug, Listener listener)
	{
		if (slug == null || slug.isEmpty())
		{
			SwingUtilities.invokeLater(() -> listener.onClanError(slug, "invalid_slug"));
			return;
		}

		final int version = ++currentVersion;
		currentSlug = slug;
		listener.onClanLookupStart(slug);

		apiClient.fetchClanClog(slug).whenComplete((result, error) ->
			SwingUtilities.invokeLater(() ->
			{
				if (version != currentVersion)
				{
					log.debug("stale clan result for {} (version {} != {})",
						slug, version, currentVersion);
					return;
				}
				if (error != null)
				{
					log.debug("clan lookup error for {}: {}", slug, error.getMessage());
					listener.onClanError(slug, error.getMessage());
					return;
				}
				if (result == null)
				{
					// fetchClanClog returns null on 404 + network failure + parse
					// failure indistinguishably. Treat as not-found for v1; the
					// distinction matters for retry logic, deferred to v1.5.
					listener.onClanNotFound(slug);
					return;
				}
				listener.onClanResult(slug, result);
			}));
	}

	/** The most-recently-requested slug. May be null if no lookup has fired yet. */
	@Nullable
	public String getCurrentSlug()
	{
		return currentSlug;
	}

	/** The current version stamp. Useful for tests + diagnostic logging. */
	public int getCurrentVersion()
	{
		return currentVersion;
	}
}
