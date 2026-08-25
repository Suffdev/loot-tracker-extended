package com.searchableloottracker.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.runelite.client.plugins.loottracker.LootTrackerPriceType;

/**
 * Immutable aggregate for one RuneLite loot source. GE ordering is prepared
 * eagerly because it is the common view; high-alchemy ordering is built only
 * if the corresponding Loot Tracker setting is selected.
 */
public final class LootSource
{
	private final String name;
	private final String normalizedName;
	private final String type;
	private final LootSourceId id;
	private final Integer npcId;
	private final int count;
	private final Instant lastReceived;
	private final List<LootItem> itemsByGe;
	private volatile List<LootItem> itemsByHa;
	private final long totalGeValue;
	private final long totalHaValue;

	public LootSource(String name, String type, int count, List<LootItem> items)
	{
		this(name, type, count, Instant.EPOCH, items, null);
	}

	public LootSource(String name, String type, int count, Instant lastReceived, List<LootItem> items)
	{
		this(name, type, count, lastReceived, items, null);
	}

	public LootSource(String name, String type, int count, Instant lastReceived, List<LootItem> items,
		Integer npcId)
	{
		this.name = Objects.requireNonNull(name);
		this.normalizedName = name.trim().toLowerCase(Locale.ENGLISH);
		this.type = Objects.requireNonNull(type);
		this.id = new LootSourceId(type, name);
		this.npcId = npcId;
		this.count = count;
		this.lastReceived = Objects.requireNonNull(lastReceived);
		List<LootItem> geSorted = new ArrayList<>(items);
		geSorted.sort(Comparator.comparingLong(LootItem::getTotalGeValue).reversed());
		itemsByGe = Collections.unmodifiableList(geSorted);
		long geValue = 0;
		long haValue = 0;
		for (LootItem item : items)
		{
			geValue += item.getTotalGeValue();
			haValue += item.getTotalHaValue();
		}
		totalGeValue = geValue;
		totalHaValue = haValue;
	}

	public String getName()
	{
		return name;
	}

	public String getNormalizedName()
	{
		return normalizedName;
	}

	public LootSourceId getId()
	{
		return id;
	}

	public String getType()
	{
		return type;
	}

	public Integer getNpcId()
	{
		return npcId;
	}

	public int getCount()
	{
		return count;
	}

	public Instant getLastReceived()
	{
		return lastReceived;
	}

	public List<LootItem> getItems()
	{
		return itemsByGe;
	}

	public List<LootItem> getItems(LootTrackerPriceType priceType)
	{
		if (priceType != LootTrackerPriceType.HIGH_ALCHEMY)
		{
			return itemsByGe;
		}

		List<LootItem> sorted = itemsByHa;
		if (sorted == null)
		{
			synchronized (this)
			{
				sorted = itemsByHa;
				if (sorted == null)
				{
					List<LootItem> haSorted = new ArrayList<>(itemsByGe);
					haSorted.sort(Comparator.comparingLong(LootItem::getTotalHaValue).reversed());
					sorted = Collections.unmodifiableList(haSorted);
					itemsByHa = sorted;
				}
			}
		}
		return sorted;
	}

	public long getTotalValue(LootTrackerPriceType priceType)
	{
		return priceType == LootTrackerPriceType.HIGH_ALCHEMY ? totalHaValue : totalGeValue;
	}
}
