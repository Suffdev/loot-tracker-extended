package com.searchableloottracker;

import com.searchableloottracker.model.LootSource;
import com.searchableloottracker.model.LootSourceId;
import com.searchableloottracker.model.SessionLootRecord;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Bounded record of individual loot events for the optional per-kill session view.
 * Exact grouped session totals live in {@link LootDataService}; eviction here affects detail only.
 */
final class SessionLootLedger
{
	static final int MAX_RECORDS = 1024;

	private final int maximumRecords;
	private final Deque<SessionLootRecord> records = new ArrayDeque<>();
	private long nextSequence;
	private boolean truncated;

	SessionLootLedger()
	{
		this(MAX_RECORDS);
	}

	SessionLootLedger(int maximumRecords)
	{
		if (maximumRecords <= 0)
		{
			throw new IllegalArgumentException("maximumRecords must be positive");
		}
		this.maximumRecords = maximumRecords;
	}

	SessionLootRecord add(int combatLevel, LootSource loot)
	{
		// Sequence numbers provide deterministic ordering when multiple records share a timestamp.
		SessionLootRecord record = new SessionLootRecord(++nextSequence, combatLevel, loot);
		records.addLast(record);
		if (records.size() > maximumRecords)
		{
			records.removeFirst();
			truncated = true;
		}
		return record;
	}

	List<SessionLootRecord> snapshot()
	{
		return Collections.unmodifiableList(new ArrayList<>(records));
	}

	boolean isTruncated()
	{
		return truncated;
	}

	void remove(LootSourceId sourceId)
	{
		// Per-source history reset also removes its individual current-session occurrences.
		records.removeIf(record -> record.getLoot().getId().equals(sourceId));
	}

	void clear()
	{
		records.clear();
		nextSequence = 0;
		truncated = false;
	}
}
