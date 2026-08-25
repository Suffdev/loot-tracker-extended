package com.searchableloottracker.wiki;

import java.io.File;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class WikiDropTableDiskCacheTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void persistsParsedDropTablesAcrossCacheInstances() throws Exception
	{
		File directory = temporaryFolder.newFolder("wiki-cache");
		WikiDropTableDiskCache first = new WikiDropTableDiskCache(directory, 10);
		first.put("guard:1", table("Coins", "10", "1/2"));

		WikiDropTable restored = new WikiDropTableDiskCache(directory, 10).get("guard:1");

		assertNotNull(restored);
		assertEquals(Collections.singletonList("1/2 (x10)"), restored.getTooltipLines("Coins"));
	}

	@Test
	public void clearingRemovesPersistentEntries() throws Exception
	{
		WikiDropTableDiskCache cache = new WikiDropTableDiskCache(
			temporaryFolder.newFolder("clear-cache"), 10);
		cache.put("guard:1", table("Coins", "10", "1/2"));
		cache.clear();

		assertNull(cache.get("guard:1"));
	}

	@Test
	public void fullCacheEvictsInsteadOfRejectingNewTables() throws Exception
	{
		File directory = temporaryFolder.newFolder("bounded-cache");
		WikiDropTableDiskCache cache = new WikiDropTableDiskCache(directory, 2);
		cache.put("first", table("Coins", "1", "1/2"));
		cache.put("second", table("Coins", "2", "1/3"));
		cache.put("third", table("Coins", "3", "1/4"));

		File[] entries = directory.listFiles((ignored, name) -> name.endsWith(".table"));
		assertNotNull(entries);
		assertEquals(2, entries.length);
		assertNotNull(cache.get("third"));
	}

	private static WikiDropTable table(String item, String quantity, String rate)
	{
		return new WikiDropTable(Collections.singletonMap(item.toLowerCase(),
			Collections.singletonList(new WikiDropRate(quantity, rate, "Drops", false))));
	}
}
