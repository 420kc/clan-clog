package com.clanclog;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
 * Thin async wrapper around the Wise Old Man v2 API for clan roster fetches.
 *
 * <p>Endpoints used:
 * <ul>
 *   <li>{@code GET /v2/groups/{id}} , full group with memberships array</li>
 *   <li>{@code GET /v2/groups?name=<q>&limit=20} , search groups by name</li>
 * </ul>
 *
 * <p>Anonymous access. Public groups freely readable. Per the 2026-05-13 roster-source
 * audit, single-group reads return quickly; name-search has been observed to time out
 * on some queries -- callers should not block the EDT.
 *
 * <p>Phase 2 swap target: when killclog.com aggregation backend lands, this client
 * targets {@code https://killclog.com/api/clan/...} instead. The shape stays the same.
 */
@Slf4j
@Singleton
public class WomClient
{
	private static final String BASE_URL = "https://api.wiseoldman.net/v2";
	private static final String USER_AGENT = "clan-clog-RuneLite-Plugin/0.1 (https://github.com/420kc/clan-clog)";
	/** Name-search has been observed to hang; cap every call so the UI path can't wedge. */
	private static final long CALL_TIMEOUT_SECONDS = 15;

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	public WomClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	/**
	 * Fetch a single group with its full memberships array. Resolves to null on
	 * any non-200 response, network failure, or JSON parse error -- caller logs
	 * the absence; no exception is thrown.
	 */
	public CompletableFuture<WomGroup> getGroup(int id)
	{
		Request request = new Request.Builder()
			.url(BASE_URL + "/groups/" + id)
			.header("User-Agent", USER_AGENT)
			.build();
		return fetchAsync(request, WomGroup.class);
	}

	/**
	 * Fetch up to {@code limit} groups matching {@code nameQuery}. Each result is a
	 * summary (no memberships). Caller picks one and calls {@link #getGroup(int)}.
	 */
	public CompletableFuture<WomGroup[]> searchGroups(String nameQuery, int limit)
	{
		String encoded = URLEncoder.encode(nameQuery, StandardCharsets.UTF_8);
		Request request = new Request.Builder()
			.url(BASE_URL + "/groups?name=" + encoded + "&limit=" + limit)
			.header("User-Agent", USER_AGENT)
			.build();
		return fetchAsync(request, WomGroup[].class);
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
				log.debug("WOM fetch failed for {}: {}", request.url(), e.getMessage());
				future.complete(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						log.debug("WOM fetch non-200 for {}: status={}", request.url(), response.code());
						future.complete(null);
						return;
					}
					String json = body.string();
					future.complete(gson.fromJson(json, type));
				}
				catch (IOException | JsonSyntaxException e)
				{
					log.debug("WOM fetch parse/read failure for {}: {}", request.url(), e.getMessage());
					future.complete(null);
				}
			}
		});
		return future;
	}
}
