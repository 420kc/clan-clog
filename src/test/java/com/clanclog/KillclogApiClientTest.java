package com.clanclog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KillclogApiClientTest
{
	@Test
	public void resultSyncCarriesRosterRescopeMetadata()
	{
		KillclogApiClient.SyncResponse roster =
			new KillclogApiClient.SyncResponse(true, 200, null, null,
				true, true, 2, 1);
		KillclogApiClient.SyncResponse result =
			new KillclogApiClient.SyncResponse(true, 200, null, null);

		KillclogApiClient.SyncResponse combined = result.withRosterMetadataFrom(roster);

		assertTrue(combined.isOk());
		assertTrue(combined.isRosterChanged());
		assertTrue(combined.isResultRescoped());
		assertEquals(2, combined.getRosterAdded());
		assertEquals(1, combined.getRosterRemoved());
		assertEquals(" · roster updated (+2/-1)", combined.describeSuccess());
	}

	@Test
	public void plainSyncSuccessHasNoStatusSuffix()
	{
		KillclogApiClient.SyncResponse response =
			new KillclogApiClient.SyncResponse(true, 200, null, null);

		assertEquals("", response.describeSuccess());
	}
}
