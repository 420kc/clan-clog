package com.clanclog;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
 * Thin async wrapper around the killclog-api backend ({@code api.killclog.com}
 * in prod, {@code 127.0.0.1:3010} for dev). Mirrors the WomClient shape from
 * the earlier vertical slice; the WomClient is now superseded by this for
 * clan-aggregated data per the Option B revision in
 * project_killclog_data_aggregation_layer.md.
 *
 * <p>v1 endpoints used:
 * <ul>
 *   <li>{@code GET /api/clan/<slug>/clog} - combined-clog union with per-boss
 *       aggregation + per-item meta + recently-acquired feed</li>
 * </ul>
 *
 * <p>Subsequent slices will add the mutation endpoints (POST /sync, POST /check,
 * POST /events). Read-only is the first wire-up since Tab 1 renders from a
 * cached read.
 *
 * <p>Resolves to {@code null} on any non-200, network failure, or JSON parse
 * error. Callers log the absence and treat as "data unavailable"; no exception
 * is thrown.
 */
@Slf4j
@Singleton
public class KillclogApiClient
{
	private static final String BASE_URL = "https://killclog.com";
	private static final String USER_AGENT = "clan-clog-RuneLite-Plugin/0.1 (+https://github.com/420kc/clan-clog)";

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	public KillclogApiClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	/**
	 * Fetch the combined-clog union for a clan. Resolves to {@code null} when
	 * the clan is not found (404), the backend is unreachable, or the response
	 * fails to parse. Use the result's getters defensively; nested collections
	 * are non-null but may be empty.
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
	 * Sync the clan roster to the backend. Creates or updates the clan
	 * record at {@code /api/clan/<slug>/sync}. Returns true on success
	 * (200 or 201), false on any error.
	 */
	public CompletableFuture<Boolean> syncRoster(String slug, String clanName,
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
	 * Returns true on success, false on any error.
	 */
	public CompletableFuture<Boolean> syncResult(String slug,
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

	private CompletableFuture<Boolean> postAsync(Request request)
	{
		CompletableFuture<Boolean> future = new CompletableFuture<>();
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("killclog-api POST failed for {}: {}", request.url(), e.getMessage());
				future.complete(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody responseBody = response.body())
				{
					boolean ok = response.isSuccessful();
					if (!ok)
					{
						log.debug("killclog-api POST non-2xx for {}: status={}",
							request.url(), response.code());
					}
					future.complete(ok);
				}
			}
		});
		return future;
	}

	private <T> CompletableFuture<T> fetchAsync(Request request, Class<T> type)
	{
		CompletableFuture<T> future = new CompletableFuture<>();
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("killclog-api fetch failed for {}: {}", request.url(), e.getMessage());
				future.complete(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						log.debug("killclog-api non-200 for {}: status={}", request.url(), response.code());
						future.complete(null);
						return;
					}
					String json = body.string();
					future.complete(gson.fromJson(json, type));
				}
				catch (IOException | JsonSyntaxException e)
				{
					log.debug("killclog-api parse/read failure for {}: {}", request.url(), e.getMessage());
					future.complete(null);
				}
			}
		});
		return future;
	}
}
