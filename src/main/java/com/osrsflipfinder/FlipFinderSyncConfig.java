package com.osrsflipfinder;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(FlipFinderSyncConfig.GROUP)
public interface FlipFinderSyncConfig extends Config
{
	String GROUP = "flipfindersync";

	@ConfigItem(
		keyName = "baseUrl",
		name = "Base URL",
		description = "Your OSRS Flip Finder URL, e.g. http://localhost:3000 (no trailing slash)",
		position = 1
	)
	default String baseUrl()
	{
		return "http://localhost:3000";
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "API key",
		description = "The key from OSRS Flip Finder → Settings → API keys",
		secret = true,
		position = 2
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "enableSync",
		name = "Enable sync",
		description = "Master switch for sending Grand Exchange trades to OSRS Flip Finder",
		position = 3
	)
	default boolean enableSync()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncExistingOnLogin",
		name = "Sync existing offers on login",
		description = "Also report offers that were already in progress when you logged in. "
			+ "Off by default to avoid re-reporting old fills.",
		position = 4
	)
	default boolean syncExistingOnLogin()
	{
		return false;
	}
}
