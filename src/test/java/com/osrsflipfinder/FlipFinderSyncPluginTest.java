package com.osrsflipfinder;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Dev entry point: boots a RuneLite client with Flip Finder Sync sideloaded.
 * Run via `./gradlew run`.
 */
public class FlipFinderSyncPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FlipFinderSyncPlugin.class);
		RuneLite.main(args);
	}
}
