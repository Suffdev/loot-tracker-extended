package com.searchableloottracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Method;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

public class SearchableLootTrackerConfigTest
{
	private static final String REIMPORT_WARNING =
		"This deletes all Loot Tracker Extended history before copying the currently saved Loot Tracker history. RuneLite Loot Tracker history is not changed.";

	@Test
	public void usesAnOwnedConfigGroup()
	{
		ConfigGroup group = SearchableLootTrackerConfig.class.getAnnotation(ConfigGroup.class);

		assertEquals(SearchableLootTrackerConfig.GROUP, group.value());
		assertEquals("loottrackerextended", group.value());
	}

	@Test
	public void wikiFetchingIsOptInWithoutAWarning() throws Exception
	{
		SearchableLootTrackerConfig defaults = new SearchableLootTrackerConfig() { };
		Method method = SearchableLootTrackerConfig.class.getMethod("wikiDropRates");
		ConfigItem item = method.getAnnotation(ConfigItem.class);

		assertFalse(defaults.wikiDropRates());
		assertEquals("", item.warning());
	}

	@Test
	public void historyReimportIsAnExplicitDestructiveAction() throws Exception
	{
		SearchableLootTrackerConfig defaults = new SearchableLootTrackerConfig() { };
		Method method = SearchableLootTrackerConfig.class.getMethod("reimportHistory");
		ConfigItem item = method.getAnnotation(ConfigItem.class);

		assertFalse(defaults.reimportHistory());
		assertEquals("Re-import Loot Tracker history", item.name());
		assertEquals(REIMPORT_WARNING, item.warning());
	}
}
