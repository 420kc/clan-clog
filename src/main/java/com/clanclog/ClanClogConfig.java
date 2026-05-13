package com.clanclog;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("clanclog")
public interface ClanClogConfig extends Config
{
	@ConfigItem(
		keyName = "rememberLastClan",
		name = "Remember Last Clan",
		description = "Restore the last searched clan when the panel reopens",
		position = 0
	)
	default boolean rememberLastClan()
	{
		return true;
	}
}
