package com.searchableloottracker;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Selects the history records loaded into Extended without mutating Loot Tracker's storage.
 * The default path intentionally mirrors Loot Tracker's 365-day/1,024-entry policy, while the
 * custom path treats zero limits as unbounded.
 */
final class LootHistoryRetention
{
	static final int MAX_DROPS = 1024;
	static final Duration MAX_AGE = Duration.ofDays(365);

	private LootHistoryRetention()
	{
	}

	static <T> List<T> select(Collection<T> entries, Instant now,
		Function<T, Instant> lastReceived, ToIntFunction<T> dropCount)
	{
		// A priority queue keeps the newest records within the entry budget in O(n log k), rather
		// than repeatedly inserting into and removing from an ordered ArrayList.
		Instant cutoff = now.minus(MAX_AGE);
		RetentionQueue<T> retained = new RetentionQueue<>();
		int retainedDrops = 0;
		for (T entry : entries)
		{
			Instant last = lastReceived.apply(entry);
			if (last == null || last.isBefore(cutoff))
			{
				continue;
			}
			if (retainedDrops >= MAX_DROPS && retained.oldestIsAfter(last))
			{
				continue;
			}

			retained.add(entry, last);
			int drops = Math.max(0, dropCount.applyAsInt(entry));
			retainedDrops += drops;
			if (retainedDrops >= MAX_DROPS)
			{
				retainedDrops -= Math.max(0, dropCount.applyAsInt(retained.removeOldest()));
			}
		}
		return retained.toOldestFirstList();
	}

	static <T> List<T> selectCustom(Collection<T> entries, Instant now, int maximumAgeDays,
		int maximumDrops, Function<T, Instant> lastReceived, ToIntFunction<T> dropCount)
	{
		Instant cutoff = maximumAgeDays > 0 ? now.minus(Duration.ofDays(maximumAgeDays)) : null;
		RetentionQueue<T> retained = new RetentionQueue<>();
		long retainedDrops = 0;
		for (T entry : entries)
		{
			Instant last = lastReceived.apply(entry);
			if (last == null || cutoff != null && last.isBefore(cutoff))
			{
				continue;
			}
			if (maximumDrops > 0 && retainedDrops >= maximumDrops && retained.oldestIsAfter(last))
			{
				continue;
			}

			retained.add(entry, last);
			retainedDrops += Math.max(0, dropCount.applyAsInt(entry));
			while (maximumDrops > 0 && retainedDrops > maximumDrops && !retained.isEmpty())
			{
				retainedDrops -= Math.max(0, dropCount.applyAsInt(retained.removeOldest()));
			}
		}
		return retained.toOldestFirstList();
	}

	private static final class RetentionQueue<T>
	{
		private final Comparator<RankedEntry<T>> oldestFirst;
		private final PriorityQueue<RankedEntry<T>> entries;
		private long sequence;

		private RetentionQueue()
		{
			oldestFirst = Comparator
				.comparing((RankedEntry<T> ranked) -> ranked.lastReceived)
				.thenComparing(Comparator.comparingLong(
					(RankedEntry<T> ranked) -> ranked.sequence).reversed());
			entries = new PriorityQueue<>(oldestFirst);
		}

		private void add(T entry, Instant lastReceived)
		{
			entries.add(new RankedEntry<>(entry, lastReceived, sequence++));
		}

		private boolean oldestIsAfter(Instant receivedAt)
		{
			return !entries.isEmpty() && entries.peek().lastReceived.isAfter(receivedAt);
		}

		private T removeOldest()
		{
			return entries.remove().entry;
		}

		private boolean isEmpty()
		{
			return entries.isEmpty();
		}

		private List<T> toOldestFirstList()
		{
			List<RankedEntry<T>> sorted = new ArrayList<>(entries);
			sorted.sort(oldestFirst);
			List<T> result = new ArrayList<>(sorted.size());
			for (RankedEntry<T> ranked : sorted)
			{
				result.add(ranked.entry);
			}
			return result;
		}
	}

	private static final class RankedEntry<T>
	{
		private final T entry;
		private final Instant lastReceived;
		private final long sequence;

		private RankedEntry(T entry, Instant lastReceived, long sequence)
		{
			this.entry = entry;
			this.lastReceived = lastReceived;
			this.sequence = sequence;
		}
	}
}
