package com.clanclog;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Thin async wrapper around the killclog-api backend. Same-origin contract:
 * requests go to {@code https://killclog.com/api/*}, where nginx proxies the
 * {@code /api/(profile|snapshot|optout|clan)} prefixes to the node service on
 * {@code 127.0.0.1:3010}. There is no {@code api.killclog.com} subdomain; the
 * web {@code /c/<slug>} page reads the same same-origin path. Mirrors the
 * WomClient shape from the earlier vertical slice; WomClient is now superseded
 * by this for clan-aggregated data per the Option B revision in
 * project_killclog_data_aggregation_layer.md.
 *
 * <p>v1 endpoints used:
 * <ul>
 *   <li>{@code GET /api/clan/<slug>} - discovery profile with roster status
 *       and cached aggregate fields when available</li>
 *   <li>{@code GET /api/clan/<slug>/clog} - combined-clog union with per-boss
 *       aggregation + per-item meta + recently-acquired feed</li>
 * </ul>
 *
 * <p>Subsequent slices will add the mutation endpoints (POST /sync, POST /check,
 * POST /events). Read-only is the first wire-up since Tab 1 renders from a
 * cached read.
 *
 * <p>Reads resolve to {@code null} only on a confirmed 404 (clan not found).
 * Transport failures, non-404 error statuses, and parse errors complete
 * exceptionally so callers can tell "no such clan" apart from "backend
 * unavailable". Sync POSTs never throw -- they resolve to a {@link SyncResponse}.
 */
@Slf4j
@Singleton
public class KillclogApiClient
{
	private static final String BASE_URL = "https://killclog.com";
	private static final String USER_AGENT = "clan-clog-RuneLite-Plugin/0.1 (+https://github.com/420kc/clan-clog)";

	/** Per-call ceiling so a wedged backend can never hang a sync the user kicked off. */
	private static final long CALL_TIMEOUT_SECONDS = 15;

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	public KillclogApiClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	/**
	 * Fetch the Clan Profile contract. When the profile is only roster-shaped,
	 * callers can fall back to {@link #fetchClanClog(String)} for the legacy
	 * aggregate endpoint.
	 */
	public CompletableFuture<ClanClogResult> fetchClanProfile(String slug)
	{
		Request request = new Request.Builder()
			.url(BASE_URL + "/api/clan/" + slug)
			.header("User-Agent", USER_AGENT)
			.header("Accept", "application/json")
			.build();
		return fetchAsync(request, ClanClogResult.class);
	}

	/**
	 * Fetch the combined-clog union for a clan. Resolves to {@code null} only on
	 * a confirmed 404 (clan not synced yet); completes exceptionally on transport
	 * failure, non-404 status, or parse error. Use the result's getters
	 * defensively; nested collections are non-null but may be empty.
	 */
	public CompletableFuture<ClanClogResult> fetchClanClog(String slug)
	{
		Request request = new Request.Builder()
			.url(BASE_URL + "/api/clan/" + slug + "/clog")
			.header("User-Agent", USER_AGENT)
			.header("Accept", "application/json")
			.build();
		return fetchAsync(request, ClanClogResult.class);
	}

	private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

	/**
	 * Outcome of a sync POST. Carries enough detail to debug a failure from the
	 * UI or logs without a packet capture: HTTP status, the backend's
	 * {@code error} code when the body is JSON (e.g. {@code rank_not_authorized},
	 * {@code slug_mismatch}, {@code owner_not_in_roster}), and an exception
	 * summary on transport failure.
	 */
	public static final class SyncResponse
	{
		private final boolean ok;
		private final int status;
		@Nullable private final String errorCode;
		@Nullable private final String detail;

		SyncResponse(boolean ok, int status, @Nullable String errorCode, @Nullable String detail)
		{
			this.ok = ok;
			this.status = status;
			this.errorCode = errorCode;
			this.detail = detail;
		}

		public boolean isOk()
		{
			return ok;
		}

		public int getStatus()
		{
			return status;
		}

		@Nullable
		public String getErrorCode()
		{
			return errorCode;
		}

		/** Short human-readable failure summary for status lines + logs. */
		public String describe()
		{
			if (ok)
			{
				return "ok";
			}
			if (errorCode != null)
			{
				return errorCode + (status > 0 ? " (" + status + ")" : "");
			}
			if (status > 0)
			{
				return "http " + status;
			}
			return detail != null ? detail : "network error";
		}
	}

	/**
	 * Sync the clan roster to the backend. Creates or updates the clan
	 * record at {@code /api/clan/<slug>/sync}. Resolves to a {@link SyncResponse}
	 * carrying success + failure detail; never throws.
	 */
	public CompletableFuture<SyncResponse> syncRoster(String slug, String clanName,
		String ownerRsn, String claimedRank, List<ClanMember> roster)
	{
		JsonObject body = new JsonObject();
		body.addProperty("clan_name", clanName);
		body.addProperty("owner_rsn", ownerRsn);
		body.addProperty("claimed_rank", claimedRank);
		body.add("members", gson.toJsonTree(
			roster.stream().map(m ->
			{
				JsonObject member = new JsonObject();
				member.addProperty("rsn", m.getRsn());
				member.addProperty("rank", m.getRankName() != null
					? m.getRankName() : "GUEST");
				if (m.getJoinDate() != null)
				{
					member.addProperty("join_date", m.getJoinDate().toString());
				}
				return member;
			}).toArray()));

		Request request = new Request.Builder()
			.url(BASE_URL + "/api/clan/" + slug + "/sync")
			.header("User-Agent", USER_AGENT)
			.post(RequestBody.create(JSON_TYPE, gson.toJson(body)))
			.build();

		return postAsync(request);
	}

	/**
	 * Sync the pre-computed ClanClogResult to the backend. Stores the
	 * boss aggregates + clog union at {@code /api/clan/<slug>/result}.
	 * Resolves to a {@link SyncResponse} carrying success + failure detail.
	 */
	public CompletableFuture<SyncResponse> syncResult(String slug,
		String ownerRsn, String claimedRank, ClanClogResult result)
	{
		JsonObject body = new JsonObject();
		body.addProperty("owner_rsn", ownerRsn);
		body.addProperty("claimed_rank", claimedRank);
		body.add("result", gson.toJsonTree(result));

		Request request = new Request.Builder()
			.url(BASE_URL + "/api/clan/" + slug + "/result")
			.header("User-Agent", USER_AGENT)
			.post(RequestBody.create(JSON_TYPE, gson.toJson(body)))
			.build();

		return postAsync(request);
	}

	private CompletableFuture<SyncResponse> postAsync(Request request)
	{
		CompletableFuture<SyncResponse> future = new CompletableFuture<>();
		Call call = httpClient.newCall(request);
		call.timeout().timeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("killclog-api POST failed for {}: {}", request.url(), e.getMessage());
				future.complete(new SyncResponse(false, -1, null, e.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody responseBody = response.body())
				{
					int status = response.code();
					if (response.isSuccessful())
					{
						future.complete(new SyncResponse(true, status, null, null));
						return;
					}
					String errorCode = extractErrorCode(responseBody);
					log.debug("killclog-api POST non-2xx for {}: status={} error={}",
						request.url(), status, errorCode);
					future.complete(new SyncResponse(false, status, errorCode, errorCode));
				}
			}
		});
		return future;
	}

	/** Pull the backend's {@code {"error": "..."}} code from a failure body, or null. */
	@Nullable
	private String extractErrorCode(@Nullable ResponseBody body)
	{
		if (body == null)
		{
			return null;
		}
		try
		{
			JsonObject json = gson.fromJson(body.string(), JsonObject.class);
			if (json != null && json.has("error") && json.get("error").isJsonPrimitive())
			{
				return json.get("error").getAsString();
			}
		}
		catch (IOException | JsonSyntaxException | IllegalStateException ignored)
		{
			// Non-JSON or unreadable body -- the HTTP status alone carries the signal.
		}
		return null;
	}

	private <T> CompletableFuture<T> fetchAsync(Request request, Class<T> type)
	{
		CompletableFuture<T> future = new CompletableFuture<>();
		Call call = httpClient.newCall(request);
		call.timeout().timeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				// Transport/timeout failure is an error, not a "not found" --
				// complete exceptionally so the caller can distinguish a missing
				// clan (404 -> null) from an unreachable backend.
				log.debug("killclog-api fetch failed for {}: {}", request.url(), e.getMessage());
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					int status = response.code();
					if (status == 404)
					{
						// Confirmed not-found: the one case that resolves to null.
						future.complete(null);
						return;
					}
					if (!response.isSuccessful() || body == null)
					{
						log.debug("killclog-api non-200 for {}: status={}", request.url(), status);
						future.completeExceptionally(new IOException("http " + status));
						return;
					}
					future.complete(gson.fromJson(body.string(), type));
				}
				catch (IOException | JsonSyntaxException e)
				{
					log.debug("killclog-api parse/read failure for {}: {}", request.url(), e.getMessage());
					future.completeExceptionally(e);
				}
			}
		});
		return future;
	}
}
