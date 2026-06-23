package com.osrsflipfinder;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(FlipFinderSyncConfig.GROUP)
public interface FlipFinderSyncConfig extends Config
{
	String GROUP = "flipfindersync";

	@ConfigItem(
		keyName = "apiKey",
		name = "API key",
		description = "The key from OSRS Flip Finder → Settings → API keys",
		secret = true,
		position = 1
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "enableSync",
		name = "Enable sync",
		description = "Master switch for sending Grand Exchange trades to OSRS Flip Finder",
		position = 2
	)
	default boolean enableSync()
	{
		return true;
	}
}
