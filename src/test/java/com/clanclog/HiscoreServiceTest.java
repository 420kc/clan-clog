package com.clanclog;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HiscoreServiceTest
{
	private final HiscoreService service = new HiscoreService(null);

	@Test
	public void detectsLowLevelUltimateWhenRegularRowIsMissing()
	{
		assertEquals(AccountType.ULTIMATE_IRONMAN,
			service.detectAccountType(hiscoreBody(420), null, null, null));
	}

	@Test
	public void detectsLowLevelIronWhenRegularRowIsMissing()
	{
		assertEquals(AccountType.IRONMAN,
			service.detectAccountType(null, null, hiscoreBody(420), null));
	}

	@Test
	public void keepsRegularWhenOnlyRegularRowExists()
	{
		assertEquals(AccountType.REGULAR,
			service.detectAccountType(null, null, null, hiscoreBody(420)));
	}

	@Test
	public void mapsKnownSpecialtyTypesToDirectEndpoints()
	{
		assertEquals("hiscore_oldschool_ultimate",
			HiscoreService.directEndpoint(AccountType.ULTIMATE_IRONMAN));
		assertEquals("hiscore_oldschool_hardcore_ironman",
			HiscoreService.directEndpoint(AccountType.HARDCORE_IRONMAN));
		assertEquals("hiscore_oldschool_ironman",
			HiscoreService.directEndpoint(AccountType.IRONMAN));
	}

	@Test
	public void skipsDirectEndpointForRegularAndGroupIron()
	{
		assertEquals(null, HiscoreService.directEndpoint(AccountType.REGULAR));
		assertEquals(null, HiscoreService.directEndpoint(AccountType.GROUP_IRONMAN));
		assertEquals(null, HiscoreService.directEndpoint(AccountType.HARDCORE_GROUP_IRONMAN));
		assertEquals(null, HiscoreService.directEndpoint(AccountType.UNRANKED_GROUP_IRONMAN));
	}

	private static String hiscoreBody(long totalXp)
	{
		return "1,100," + totalXp + "\n";
	}
}
