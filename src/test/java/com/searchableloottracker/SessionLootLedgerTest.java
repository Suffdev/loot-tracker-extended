package com.searchableloottracker;

import com.searchableloottracker.model.LootItem;
import com.searchableloottracker.model.LootSource;
import com.searchableloottracker.model.SessionLootRecord;
import com.searchableloottracker.search.LootSearch;
import com.searchableloottracker.search.LootSearchResult;
import com.searchableloottracker.search.SearchMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionLootLedgerTest
{
	@Test
	public void preservesIndividualLootInArrivalOrder()
	{
		SessionLootLedger ledger = new SessionLootLedger(3);

		SessionLootRecord first = ledger.add(100, loot("First"));
		SessionLootRecord second = ledger.add(200, loot("Second"));

		List<SessionLootRecord> records = ledger.snapshot();
		assertEquals(2, records.size());
		assertEquals(first, records.get(0));
		assertEquals(second, records.get(1));
		assertEquals(1, first.getSequence());
		assertEquals(2, second.getSequence());
		assertEquals(200, second.getCombatLevel());
		assertFalse(ledger.isTruncated());
	}

	@Test
	public void retainsOnlyTheNewestDetailedRecords()
	{
		SessionLootLedger ledger = new SessionLootLedger(2);
		ledger.add(1, loot("First"));
		ledger.add(2, loot("Second"));
		ledger.add(3, loot("Third"));

		List<SessionLootRecord> records = ledger.snapshot();
		assertEquals(2, records.size());
		assertEquals("Second", records.get(0).getLoot().getName());
		assertEquals("Third", records.get(1).getLoot().getName());
		assertTrue(ledger.isTruncated());
	}

	@Test
	public void clearingStartsANewSessionSequence()
	{
		SessionLootLedger ledger = new SessionLootLedger(1);
		ledger.add(1, loot("First"));
		ledger.add(1, loot("Second"));
		assertTrue(ledger.isTruncated());

		ledger.clear();

		assertTrue(ledger.snapshot().isEmpty());
		assertFalse(ledger.isTruncated());
		assertEquals(1, ledger.add(1, loot("New session")).getSequence());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsAnUnboundedDetailedLedger()
	{
		new SessionLootLedger(0);
	}

	@Test
	public void sameSourceKillsRemainIndividuallySearchable()
	{
		SessionLootLedger ledger = new SessionLootLedger(3);
		ledger.add(10, loot("Guard", new LootItem(995, "Coins", 2, 1, 1)));
		ledger.add(10, loot("Guard", new LootItem(995, "Coins", 3, 1, 1)));

		List<LootSource> individualLoot = Arrays.asList(
			ledger.snapshot().get(0).getLoot(), ledger.snapshot().get(1).getLoot());
		List<LootSearchResult> results = LootSearch.search(individualLoot, "coins", SearchMode.DROP);

		assertEquals(2, results.size());
		assertEquals(5, LootSearch.totalQuantity(results));
	}

	private static LootSource loot(String name)
	{
		return new LootSource(name, "NPC", 1, Collections.emptyList());
	}

	private static LootSource loot(String name, LootItem item)
	{
		return new LootSource(name, "NPC", 1, Collections.singletonList(item));
	}
}
