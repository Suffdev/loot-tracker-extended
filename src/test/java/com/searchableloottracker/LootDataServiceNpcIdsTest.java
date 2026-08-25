package com.searchableloottracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.Map;
import org.junit.Test;

public class LootDataServiceNpcIdsTest
{
	private final Gson gson = new Gson();

	@Test
	public void readsJsonNpcIdsWrittenByCurrentBuilds()
	{
		Map<String, Integer> parsed = LootDataService.parseObservedNpcIds(
			gson, "{\"chicken\":3661,\"rabbit\":3664}");

		assertEquals(Integer.valueOf(3661), parsed.get("chicken"));
		assertEquals(Integer.valueOf(3664), parsed.get("rabbit"));
	}

	@Test
	public void readsLegacyMapStringsWrittenByPreviousBuilds()
	{
		Map<String, Integer> parsed = LootDataService.parseObservedNpcIds(
			gson, "{chicken=3661, rabbit=3664, gardener=3275}");

		assertEquals(Integer.valueOf(3661), parsed.get("chicken"));
		assertEquals(Integer.valueOf(3664), parsed.get("rabbit"));
		assertEquals(Integer.valueOf(3275), parsed.get("gardener"));
	}

	@Test
	public void malformedNpcIdCacheDoesNotPreventStartup()
	{
		Map<String, Integer> parsed = LootDataService.parseObservedNpcIds(gson, "not a map");

		assertTrue(parsed.isEmpty());
	}
}
