package com.osrsflipfinder;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

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

	@ConfigItem(
		keyName = "showSuggestions",
		name = "Show price suggestions",
		description = "Fetch OSRS Flip Finder's suggested buy/sell prices for your live "
			+ "Grand Exchange offers and show them in this panel. Read-only; works "
			+ "independently of trade sync.",
		position = 3
	)
	default boolean showSuggestions()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "risk",
		name = "Risk (%)",
		description = "0 = safe, likely-to-fill prices with thin margins; 100 = patient, "
			+ "fatter margins. Mirrors the risk dial on the website.",
		position = 4
	)
	default int risk()
	{
		return 30;
	}
}
