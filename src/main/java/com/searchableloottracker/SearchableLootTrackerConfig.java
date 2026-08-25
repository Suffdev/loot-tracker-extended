package com.searchableloottracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

/**
 * Settings owned by Loot Tracker Extended. Core Loot Tracker preferences are read through
 * {@code LootTrackerConfig} instead of sharing its config group, which avoids key collisions.
 */
@ConfigGroup(SearchableLootTrackerConfig.GROUP)
public interface SearchableLootTrackerConfig extends Config
{
	String GROUP = "loottrackerextended";
	String EXTENDED_HISTORY_KEY = "extendedLootHistory";
	String HISTORY_AGE_DAYS_KEY = "historyAgeDays";
	String MAXIMUM_DROP_ENTRIES_KEY = "maximumDropEntries";
	String REIMPORT_HISTORY_KEY = "reimportHistory";
	String WIKI_DROP_RATES_KEY = "wikiDropRates";
	String REFRESH_WIKI_DROP_RATES_KEY = "refreshWikiDropRates";

	@ConfigSection(
		name = "Loot history",
		description = "Control how much saved Loot Tracker Extended history is loaded",
		position = 0
	)
	String historySection = "history";

	@ConfigSection(
		name = "Wiki drop rates",
		description = "Control OSRS Wiki drop-rate lookups and cached tables",
		position = 1
	)
	String wikiSection = "wiki";

	@ConfigItem(
		keyName = EXTENDED_HISTORY_KEY,
		name = "Extended loot history",
		description = "Use custom history limits instead of Loot Tracker's standard 365-day and 1,024-entry limits.",
		position = 0,
		section = historySection
	)
	default boolean extendedLootHistory()
	{
		return false;
	}

	@Range(min = 0, max = 36500)
	@ConfigItem(
		keyName = HISTORY_AGE_DAYS_KEY,
		name = "History age (days)",
		description = "Only load loot recorded within this many days. Set to 0 for unlimited history.",
		position = 1,
		section = historySection
	)
	default int historyAgeDays()
	{
		return 0;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = MAXIMUM_DROP_ENTRIES_KEY,
		name = "Maximum drop entries",
		description = "Maximum number of stored item entries to load. Set to 0 for unlimited entries.",
		position = 2,
		section = historySection
	)
	default int maximumDropEntries()
	{
		return 0;
	}

	// RuneLite renders action settings as booleans. The plugin handles the true edge and
	// immediately restores false, making this behave as a guarded one-shot action.
	@ConfigItem(
		keyName = REIMPORT_HISTORY_KEY,
		name = "Re-import Loot Tracker history",
		description = "While logged in, delete this profile's Loot Tracker Extended history and replace it with its currently saved RuneLite Loot Tracker history.",
		warning = "This deletes all Loot Tracker Extended history before copying the currently saved Loot Tracker history. RuneLite Loot Tracker history is not changed.",
		position = 3,
		section = historySection
	)
	default boolean reimportHistory()
	{
		return false;
	}

	@ConfigItem(
		keyName = WIKI_DROP_RATES_KEY,
		name = "Wiki drop rates",
		description = "Fetch official rates for NPC, pickpocket, and event sources. Requests send the source name and, for NPCs when known, numeric NPC ID.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		position = 0,
		section = wikiSection
	)
	default boolean wikiDropRates()
	{
		return false;
	}

	@ConfigItem(
		keyName = REFRESH_WIKI_DROP_RATES_KEY,
		name = "Refresh cached drop rates",
		description = "Clear cached Wiki drop tables. Rates will be downloaded again when items are next hovered.",
		position = 1,
		section = wikiSection
	)
	default boolean refreshWikiDropRates()
	{
		return false;
	}
}
