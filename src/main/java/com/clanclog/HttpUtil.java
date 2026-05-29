package com.clanclog;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Shared HTTP plumbing used by {@link ClogFetchService} and any future
 * external-API callers. Mirrors kcpdev's HttpUtil: async GET with User-Agent
 * header, status-code tracking, transport-failure as code -1.
 */
@Slf4j
final class HttpUtil
{
	static final String USER_AGENT =
		"clan-clog-RuneLite-Plugin/0.1 (https://github.com/420kc/clan-clog)";
	private static final long CALL_TIMEOUT_SECONDS = 15;

	/** HTTP status code (-1 on transport failure) plus the body of a successful response. */
	static final class HttpResult
	{
		final int code;
		final String body;

		HttpResult(int code, String body)
		{
			this.code = code;
			this.body = body;
		}
	}

	static CompletableFuture<HttpResult> httpGet(OkHttpClient client, String url)
	{
		CompletableFuture<HttpResult> future = new CompletableFuture<>();

		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.build();

		Call call = client.newCall(request);
		call.timeout().timeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("HTTP GET failed for {}: {}", url, e.getMessage());
				future.complete(new HttpResult(-1, null));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					String text = response.isSuccessful() && body != null ? body.string() : null;
					future.complete(new HttpResult(response.code(), text));
				}
				catch (IOException e)
				{
					log.debug("Failed to read response for {}: {}", url, e.getMessage());
					future.complete(new HttpResult(-1, null));
				}
			}
		});

		return future;
	}

	private HttpUtil()
	{
	}
}
