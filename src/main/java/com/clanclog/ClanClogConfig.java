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

	@ConfigItem(
		keyName = "tooltipMode",
		name = "Tooltip Activation",
		description = "How tooltips are triggered on boss and activity cells (hover or click-to-reveal)",
		position = 1
	)
	default TooltipMode tooltipMode()
	{
		return TooltipMode.CLICK;
	}

	@ConfigItem(
		keyName = "hoverStyle",
		name = "Cell Hover",
		description = "Visual feedback when hovering a cell. Outline uses the highlight color, Tint subtly brightens the background.",
		position = 2
	)
	default HoverStyle hoverStyle()
	{
		return HoverStyle.OUTLINE;
	}

	@ConfigItem(
		keyName = "defaultClan",
		name = "Default Clan",
		description = "Clan slug or name to auto-load on panel open (e.g. Clannabis). Leave empty to disable.",
		position = 3
	)
	default String defaultClan()
	{
		return "";
	}
}
