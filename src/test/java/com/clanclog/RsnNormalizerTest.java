package com.clanclog;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RsnNormalizerTest
{
	@Test
	public void normalizesRuneLiteClanRosterSpaces()
	{
		assertEquals("420 kc", RsnNormalizer.normalize("420\u00A0kc"));
		assertEquals("GIM 420 Vape", RsnNormalizer.normalize("GIM\u202F420\u2007Vape"));
		assertEquals("My Specialty", RsnNormalizer.normalize(" My\uFFFD Specialty "));
	}

	@Test
	public void encodesNormalizedNameForProviders()
	{
		assertEquals("420%20kc", RsnNormalizer.encodeQueryValue("420\u00A0kc"));
	}
}
