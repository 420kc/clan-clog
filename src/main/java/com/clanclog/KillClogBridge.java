package com.clanclog;

import java.lang.reflect.Method;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * Optional bridge into the sibling Kill Clog plugin. Clan Clog must not compile
 * against Kill Clog classes, so this uses a tiny public-method contract when
 * Kill Clog is installed and enabled.
 */
@Slf4j
@Singleton
class KillClogBridge
{
	static final String KILL_CLOG_PLUGIN_CLASS = "KillClogPlugin";
	static final String LOOKUP_METHOD = "lookupFromExternalPlugin";

	private final PluginManager pluginManager;

	@Inject
	KillClogBridge(PluginManager pluginManager)
	{
		this.pluginManager = pluginManager;
	}

	boolean lookup(String rsn)
	{
		String normalized = RsnNormalizer.normalize(rsn);
		if (normalized.isEmpty())
		{
			return false;
		}

		try
		{
			for (Plugin plugin : pluginManager.getPlugins())
			{
				if (!KILL_CLOG_PLUGIN_CLASS.equals(plugin.getClass().getSimpleName())
					|| !pluginManager.isPluginEnabled(plugin))
				{
					continue;
				}

				Method method = plugin.getClass().getMethod(LOOKUP_METHOD, String.class);
				method.invoke(plugin, normalized);
				return true;
			}
		}
		catch (ReflectiveOperationException | RuntimeException ex)
		{
			log.debug("Kill Clog bridge unavailable for {}: {}", normalized, ex.getMessage());
		}
		return false;
	}
}
