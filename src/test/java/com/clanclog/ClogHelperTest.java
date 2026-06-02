package com.clanclog;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ClogHelperTest
{
	@Test
	public void progressColorUsesNativeStoplight()
	{
		assertEquals(ClogHelper.PROGRESS_EMPTY, ClogHelper.progressColor(0, 10));
		assertEquals(ClogHelper.PROGRESS_EMPTY, ClogHelper.progressColor(5, 0));
		assertEquals(ClogHelper.PROGRESS_PARTIAL, ClogHelper.progressColor(5, 10));
		assertEquals(ClogHelper.PROGRESS_COMPLETE, ClogHelper.progressColor(10, 10));
		assertEquals(ClogHelper.PROGRESS_COMPLETE, ClogHelper.progressColor(11, 10));
	}
}
