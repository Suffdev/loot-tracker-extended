package com.searchableloottracker;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class LootHistoryRetentionTest
{
	private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

	@Test
	public void excludesHistoryOlderThanLootTrackersMaximumAge()
	{
		Entry cutoff = new Entry("cutoff", NOW.minus(365, ChronoUnit.DAYS), 1);
		Entry expired = new Entry("expired", NOW.minus(365, ChronoUnit.DAYS).minusSeconds(1), 1);

		List<Entry> retained = select(Arrays.asList(expired, cutoff));

		assertEquals(1, retained.size());
		assertEquals("cutoff", retained.get(0).name);
	}

	@Test
	public void removesTheOldestEntryWhenTheDropLimitIsReached()
	{
		Entry oldest = new Entry("oldest", NOW.minusSeconds(2), 500);
		Entry newest = new Entry("newest", NOW, 600);

		List<Entry> retained = select(Arrays.asList(oldest, newest));

		assertEquals(1, retained.size());
		assertEquals("newest", retained.get(0).name);
	}

	@Test
	public void treatsTheDropLimitAsExclusiveLikeLootTracker()
	{
		Entry older = new Entry("older", NOW.minusSeconds(1), LootHistoryRetention.MAX_DROPS - 1);
		Entry reachesLimit = new Entry("reaches-limit", NOW, 1);

		List<Entry> retained = select(Arrays.asList(older, reachesLimit));

		assertEquals(1, retained.size());
		assertEquals("reaches-limit", retained.get(0).name);
	}

	@Test
	public void matchesLootTrackersTieOrderingAtTheDropLimit()
	{
		Entry first = new Entry("first", NOW, 500);
		Entry second = new Entry("second", NOW, 600);

		List<Entry> retained = select(Arrays.asList(first, second));

		assertEquals(1, retained.size());
		assertEquals("first", retained.get(0).name);
	}

	@Test
	public void customZeroLimitsLoadAllHistory()
	{
		Entry ancient = new Entry("ancient", NOW.minus(1000, ChronoUnit.DAYS), 2000);
		Entry recent = new Entry("recent", NOW, 2000);

		List<Entry> retained = selectCustom(Arrays.asList(ancient, recent), 0, 0);

		assertEquals(2, retained.size());
		assertEquals("ancient", retained.get(0).name);
		assertEquals("recent", retained.get(1).name);
	}

	@Test
	public void customAgeExcludesOnlyEntriesOutsideItsCutoff()
	{
		Entry cutoff = new Entry("cutoff", NOW.minus(730, ChronoUnit.DAYS), 1);
		Entry expired = new Entry("expired", NOW.minus(730, ChronoUnit.DAYS).minusSeconds(1), 1);

		List<Entry> retained = selectCustom(Arrays.asList(expired, cutoff), 730, 0);

		assertEquals(1, retained.size());
		assertEquals("cutoff", retained.get(0).name);
	}

	@Test
	public void customDropLimitIsInclusive()
	{
		Entry older = new Entry("older", NOW.minusSeconds(1), 600);
		Entry newer = new Entry("newer", NOW, 400);

		List<Entry> retained = selectCustom(Arrays.asList(older, newer), 0, 1000);

		assertEquals(2, retained.size());
	}

	@Test
	public void customDropLimitKeepsTheMostRecentEntriesThatFit()
	{
		Entry oldest = new Entry("oldest", NOW.minusSeconds(2), 600);
		Entry newest = new Entry("newest", NOW, 500);

		List<Entry> retained = selectCustom(Arrays.asList(oldest, newest), 0, 1000);

		assertEquals(1, retained.size());
		assertEquals("newest", retained.get(0).name);
	}

	@Test
	public void optimizedRetentionHandlesOutOfOrderEntries()
	{
		Entry newest = new Entry("newest", NOW, 900);
		Entry oldest = new Entry("oldest", NOW.minusSeconds(2), 100);
		Entry middle = new Entry("middle", NOW.minusSeconds(1), 200);

		List<Entry> retained = select(Arrays.asList(newest, oldest, middle));

		assertEquals(2, retained.size());
		assertEquals("middle", retained.get(0).name);
		assertEquals("newest", retained.get(1).name);
	}

	@Test
	public void unlimitedHistoryIsReturnedOldestFirst()
	{
		Entry newest = new Entry("newest", NOW, 1);
		Entry oldest = new Entry("oldest", NOW.minusSeconds(2), 1);
		Entry middle = new Entry("middle", NOW.minusSeconds(1), 1);

		List<Entry> retained = selectCustom(Arrays.asList(newest, oldest, middle), 0, 0);

		assertEquals(Arrays.asList("oldest", "middle", "newest"), Arrays.asList(
			retained.get(0).name, retained.get(1).name, retained.get(2).name));
	}

	@Test
	public void optimizedStandardRetentionMatchesPreviousSelection()
	{
		Random random = new Random(42);
		for (int iteration = 0; iteration < 100; iteration++)
		{
			List<Entry> entries = new ArrayList<>();
			for (int index = 0; index < 100; index++)
			{
				entries.add(new Entry("entry-" + index, NOW.minusSeconds(index + 1), random.nextInt(200)));
			}
			Collections.shuffle(entries, random);

			assertEquals(previousStandardSelection(entries), select(entries));
		}
	}

	@Test
	public void optimizedCustomRetentionMatchesPreviousSelection()
	{
		Random random = new Random(84);
		for (int iteration = 0; iteration < 100; iteration++)
		{
			List<Entry> entries = new ArrayList<>();
			for (int index = 0; index < 100; index++)
			{
				entries.add(new Entry("entry-" + index, NOW.minusSeconds(index + 1), random.nextInt(200)));
			}
			Collections.shuffle(entries, random);

			assertEquals(previousCustomSelection(entries, 1000), selectCustom(entries, 0, 1000));
		}
	}

	private static List<Entry> previousStandardSelection(List<Entry> entries)
	{
		List<Entry> retained = new ArrayList<>();
		Comparator<Entry> oldestFirst = Comparator.comparing(entry -> entry.lastReceived);
		int retainedDrops = 0;
		for (Entry entry : entries)
		{
			if (retainedDrops >= LootHistoryRetention.MAX_DROPS && !retained.isEmpty()
				&& retained.get(0).lastReceived.isAfter(entry.lastReceived))
			{
				continue;
			}
			int index = Collections.binarySearch(retained, entry, oldestFirst);
			retained.add(index < 0 ? -index - 1 : index, entry);
			retainedDrops += entry.drops;
			if (retainedDrops >= LootHistoryRetention.MAX_DROPS)
			{
				retainedDrops -= retained.remove(0).drops;
			}
		}
		return retained;
	}

	private static List<Entry> previousCustomSelection(List<Entry> entries, int maximumDrops)
	{
		List<Entry> retained = new ArrayList<>();
		Comparator<Entry> oldestFirst = Comparator.comparing(entry -> entry.lastReceived);
		long retainedDrops = 0;
		for (Entry entry : entries)
		{
			if (retainedDrops >= maximumDrops && !retained.isEmpty()
				&& retained.get(0).lastReceived.isAfter(entry.lastReceived))
			{
				continue;
			}
			int index = Collections.binarySearch(retained, entry, oldestFirst);
			retained.add(index < 0 ? -index - 1 : index, entry);
			retainedDrops += entry.drops;
			while (retainedDrops > maximumDrops && !retained.isEmpty())
			{
				retainedDrops -= retained.remove(0).drops;
			}
		}
		return retained;
	}

	private static List<Entry> select(List<Entry> entries)
	{
		return LootHistoryRetention.select(entries, NOW, entry -> entry.lastReceived, entry -> entry.drops);
	}

	private static List<Entry> selectCustom(List<Entry> entries, int maximumAgeDays, int maximumDrops)
	{
		return LootHistoryRetention.selectCustom(entries, NOW, maximumAgeDays, maximumDrops,
			entry -> entry.lastReceived, entry -> entry.drops);
	}

	private static final class Entry
	{
		private final String name;
		private final Instant lastReceived;
		private final int drops;

		private Entry(String name, Instant lastReceived, int drops)
		{
			this.name = name;
			this.lastReceived = lastReceived;
			this.drops = drops;
		}
	}
}
