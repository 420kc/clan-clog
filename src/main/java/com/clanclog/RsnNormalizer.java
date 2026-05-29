package com.clanclog;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class RsnNormalizer
{
	private RsnNormalizer()
	{
	}

	static String normalize(String value)
	{
		if (value == null)
		{
			return "";
		}

		StringBuilder out = new StringBuilder(value.length());
		boolean lastWasSpace = false;
		for (int i = 0; i < value.length(); i++)
		{
			char ch = value.charAt(i);
			if (isNameSpace(ch))
			{
				if (!lastWasSpace)
				{
					out.append(' ');
					lastWasSpace = true;
				}
			}
			else
			{
				out.append(ch);
				lastWasSpace = false;
			}
		}
		return out.toString().trim();
	}

	static String cacheKey(String value)
	{
		return normalize(value).toLowerCase(Locale.ROOT);
	}

	static String encodeQueryValue(String value)
	{
		return URLEncoder.encode(normalize(value), StandardCharsets.UTF_8)
			.replace("+", "%20");
	}

	private static boolean isNameSpace(char ch)
	{
		return Character.isWhitespace(ch)
			|| Character.isSpaceChar(ch)
			|| ch == '\uFFFD';
	}
}
