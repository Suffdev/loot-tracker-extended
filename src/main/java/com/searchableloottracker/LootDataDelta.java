package com.searchableloottracker;

import com.searchableloottracker.model.LootSource;
import com.searchableloottracker.model.SessionLootRecord;

/** Minimal client-thread update emitted for a newly observed loot event. */
final class LootDataDelta
{
	private final LootSource historySource;
	private final LootSource sessionSource;
	private final SessionLootRecord sessionRecord;
	private final boolean sessionRecordsTruncated;

	LootDataDelta(LootSource historySource, LootSource sessionSource,
		SessionLootRecord sessionRecord, boolean sessionRecordsTruncated)
	{
		this.historySource = historySource;
		this.sessionSource = sessionSource;
		this.sessionRecord = sessionRecord;
		this.sessionRecordsTruncated = sessionRecordsTruncated;
	}

	LootSource getHistorySource()
	{
		return historySource;
	}

	LootSource getSessionSource()
	{
		return sessionSource;
	}

	SessionLootRecord getSessionRecord()
	{
		return sessionRecord;
	}

	boolean areSessionRecordsTruncated()
	{
		return sessionRecordsTruncated;
	}
}
