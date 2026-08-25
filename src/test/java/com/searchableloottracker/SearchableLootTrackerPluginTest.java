package com.searchableloottracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SearchableLootTrackerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SearchableLootTrackerPlugin.class);
		RuneLite.main(args);
	}
}
