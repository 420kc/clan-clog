package com.clanclog;

import com.google.gson.Gson;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.swing.SwingUtilities;
import okhttp3.OkHttpClient;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ClanLookupSessionTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void startFallsBackWhenProfileHasOnlyEmptyCoverage() throws Exception
	{
		ClanClogResult profile = result("{"
			+ "\"slug\":\"clannabis\","
			+ "\"display_name\":\"Clannabis\","
			+ "\"build_status\":\"ready\","
			+ "\"member_coverage\":{\"total\":2,\"clog_ok\":0,\"hiscore_only\":0,\"not_found\":2},"
			+ "\"bosses\":{},"
			+ "\"activity_totals\":{},"
			+ "\"clog\":null"
			+ "}");
		ClanClogResult clog = result("{"
			+ "\"slug\":\"clannabis\","
			+ "\"display_name\":\"Clannabis\","
			+ "\"member_coverage\":{\"total\":2,\"clog_ok\":1,\"hiscore_only\":0,\"not_found\":1},"
			+ "\"bosses\":{},"
			+ "\"activity_totals\":{},"
			+ "\"clog\":{\"items_by_category\":{\"barrows\":[4708]},\"total_obtained\":1}"
			+ "}");
		FakeApiClient apiClient = new FakeApiClient(profile, clog);
		RecordingListener listener = new RecordingListener();

		SwingUtilities.invokeAndWait(() ->
			new ClanLookupSession(apiClient).start("clannabis", listener));

		assertTrue(listener.await());
		assertSame(clog, listener.result.get());
		assertEquals(1, apiClient.clogCalls);
	}

	@Test
	public void startFallsBackWhenProfileRequestFails() throws Exception
	{
		ClanClogResult clog = result("{"
			+ "\"slug\":\"clannabis\","
			+ "\"display_name\":\"Clannabis\","
			+ "\"member_coverage\":{\"total\":2,\"clog_ok\":1,\"hiscore_only\":0,\"not_found\":1},"
			+ "\"clog\":{\"items_by_category\":{\"barrows\":[4708]},\"total_obtained\":1}"
			+ "}");
		FakeApiClient apiClient = new FakeApiClient(
			new IllegalStateException("profile unavailable"), clog);
		RecordingListener listener = new RecordingListener();

		SwingUtilities.invokeAndWait(() ->
			new ClanLookupSession(apiClient).start("clannabis", listener));

		assertTrue(listener.await());
		assertSame(clog, listener.result.get());
		assertEquals(1, apiClient.clogCalls);
	}

	@Test
	public void startFallsBackWhenProfileReturnsNull() throws Exception
	{
		ClanClogResult clog = result("{"
			+ "\"slug\":\"clannabis\","
			+ "\"display_name\":\"Clannabis\","
			+ "\"member_coverage\":{\"total\":2,\"clog_ok\":1,\"hiscore_only\":0,\"not_found\":1},"
			+ "\"clog\":{\"items_by_category\":{\"barrows\":[4708]},\"total_obtained\":1}"
			+ "}");
		FakeApiClient apiClient = new FakeApiClient((ClanClogResult) null, clog);
		RecordingListener listener = new RecordingListener();

		SwingUtilities.invokeAndWait(() ->
			new ClanLookupSession(apiClient).start("clannabis", listener));

		assertTrue(listener.await());
		assertSame(clog, listener.result.get());
		assertEquals(1, apiClient.clogCalls);
	}

	@Test
	public void startKeepsRepresentedProfileWithoutFallback() throws Exception
	{
		ClanClogResult profile = result("{"
			+ "\"slug\":\"clannabis\","
			+ "\"display_name\":\"Clannabis\","
			+ "\"build_status\":\"ready\","
			+ "\"member_coverage\":{\"total\":2,\"clog_ok\":1,\"hiscore_only\":0,\"not_found\":1},"
			+ "\"bosses\":{},"
			+ "\"activity_totals\":{},"
			+ "\"clog\":null"
			+ "}");
		ClanClogResult clog = result("{"
			+ "\"slug\":\"clannabis\","
			+ "\"display_name\":\"Clannabis fallback\","
			+ "\"member_coverage\":{\"total\":2,\"clog_ok\":2,\"hiscore_only\":0,\"not_found\":0}"
			+ "}");
		FakeApiClient apiClient = new FakeApiClient(profile, clog);
		RecordingListener listener = new RecordingListener();

		SwingUtilities.invokeAndWait(() ->
			new ClanLookupSession(apiClient).start("clannabis", listener));

		assertTrue(listener.await());
		assertSame(profile, listener.result.get());
		assertEquals(0, apiClient.clogCalls);
	}

	private static ClanClogResult result(String json)
	{
		return GSON.fromJson(json, ClanClogResult.class);
	}

	private static class FakeApiClient extends KillclogApiClient
	{
		private final ClanClogResult profile;
		private final ClanClogResult clog;
		private final Throwable profileError;
		private int clogCalls;

		FakeApiClient(ClanClogResult profile, ClanClogResult clog)
		{
			this(profile, null, clog);
		}

		FakeApiClient(Throwable profileError, ClanClogResult clog)
		{
			this(null, profileError, clog);
		}

		private FakeApiClient(ClanClogResult profile, Throwable profileError,
			ClanClogResult clog)
		{
			super(new OkHttpClient(), GSON);
			this.profile = profile;
			this.profileError = profileError;
			this.clog = clog;
		}

		@Override
		public CompletableFuture<ClanClogResult> fetchClanProfile(String slug)
		{
			if (profileError != null)
			{
				return CompletableFuture.failedFuture(profileError);
			}
			return CompletableFuture.completedFuture(profile);
		}

		@Override
		public CompletableFuture<ClanClogResult> fetchClanClog(String slug)
		{
			clogCalls++;
			return CompletableFuture.completedFuture(clog);
		}
	}

	private static class RecordingListener implements ClanLookupSession.Listener
	{
		private final CountDownLatch latch = new CountDownLatch(1);
		private final AtomicReference<ClanClogResult> result = new AtomicReference<>();

		@Override
		public void onClanLookupStart(String slug)
		{
		}

		@Override
		public void onClanResult(String slug, ClanClogResult result)
		{
			this.result.set(result);
			latch.countDown();
		}

		@Override
		public void onClanNotFound(String slug)
		{
			latch.countDown();
		}

		@Override
		public void onClanError(String slug, @Nullable String detail)
		{
			latch.countDown();
		}

		private boolean await() throws InterruptedException
		{
			return latch.await(5, TimeUnit.SECONDS);
		}
	}
}
