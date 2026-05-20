package com.clanclog;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
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
	// TODO: switch to https://api.killclog.com once the CCX33 deploy lands
	// (gated on 2f per project_clan_hiscores_plugin.md release gate). For now
	// the dev server runs at 127.0.0.1:3010 per killclog-api/README.md.
	private static final String BASE_URL = "http://127.0.0.1:3010";
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
