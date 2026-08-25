package com.searchableloottracker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchableLootTrackerPluginSafetyTest
{
	@Test
	public void acceptsOnlyTheUnchangedActiveProfile()
	{
		assertTrue(SearchableLootTrackerPlugin.isExpectedProfile(
			true, 4L, 4L, "profile-a", "profile-a"));
	}

	@Test
	public void rejectsProfileAndLifecycleChanges()
	{
		assertFalse(SearchableLootTrackerPlugin.isExpectedProfile(
			false, 4L, 4L, "profile-a", "profile-a"));
		assertFalse(SearchableLootTrackerPlugin.isExpectedProfile(
			true, 4L, 5L, "profile-a", "profile-a"));
		assertFalse(SearchableLootTrackerPlugin.isExpectedProfile(
			true, 4L, 4L, "profile-a", "profile-b"));
		assertFalse(SearchableLootTrackerPlugin.isExpectedProfile(
			true, 4L, 4L, null, "profile-a"));
	}

	@Test
	public void generationRejectsAnAtoBtoATransition()
	{
		assertFalse(SearchableLootTrackerPlugin.isExpectedProfile(
			true, 10L, 12L, "profile-a", "profile-a"));
	}
}
