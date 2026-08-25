package com.searchableloottracker.model;

import java.util.Objects;

/** One session occurrence, including the combat-level split used by the UI. */
public final class SessionLootRecord
{
	private final long sequence;
	private final int combatLevel;
	private final LootSource loot;

	public SessionLootRecord(long sequence, int combatLevel, LootSource loot)
	{
		this.sequence = sequence;
		this.combatLevel = combatLevel;
		this.loot = Objects.requireNonNull(loot);
	}

	public long getSequence()
	{
		return sequence;
	}

	public int getCombatLevel()
	{
		return combatLevel;
	}

	public LootSource getLoot()
	{
		return loot;
	}
}
