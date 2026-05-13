package com.clanclog;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Clan Clog",
	description = "Search a clan, see every member ranked",
	tags = {"clan", "hiscores", "420kc"}
)
public class ClanClogPlugin extends Plugin
{
	@Inject
	private ClanClogConfig config;

	@Provides
	ClanClogConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClanClogConfig.class);
	}

	@Override
	protected void startUp()
	{
		log.debug("clan clog: startUp");
	}

	@Override
	protected void shutDown()
	{
		log.debug("clan clog: shutDown");
	}
}
