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

	private static String hiscoreBody(long totalXp)
	{
		return "1,100," + totalXp + "\n";
	}
}
