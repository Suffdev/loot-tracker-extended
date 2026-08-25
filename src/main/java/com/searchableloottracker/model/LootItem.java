package com.searchableloottracker.model;

import java.util.Locale;
import java.util.Objects;
import net.runelite.client.plugins.loottracker.LootTrackerPriceType;

/** Immutable item aggregate with precomputed GE and high-alchemy totals. */
public final class LootItem
{
	private final int id;
	private final String name;
	private final String normalizedName;
	private final int quantity;
	private final int gePrice;
	private final int haPrice;
	private final long totalGeValue;
	private final long totalHaValue;

	public LootItem(int id, String name, int quantity, int gePrice, int haPrice)
	{
		this.id = id;
		this.name = Objects.requireNonNull(name);
		this.normalizedName = name.trim().toLowerCase(Locale.ENGLISH);
		this.quantity = quantity;
		this.gePrice = gePrice;
		this.haPrice = haPrice;
		this.totalGeValue = (long) gePrice * quantity;
		this.totalHaValue = (long) haPrice * quantity;
	}

	public int getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public String getNormalizedName()
	{
		return normalizedName;
	}

	public int getQuantity()
	{
		return quantity;
	}

	public int getGePrice()
	{
		return gePrice;
	}

	public int getHaPrice()
	{
		return haPrice;
	}

	public long getTotalGeValue()
	{
		return totalGeValue;
	}

	public long getTotalHaValue()
	{
		return totalHaValue;
	}

	public long getTotalValue(LootTrackerPriceType priceType)
	{
		return priceType == LootTrackerPriceType.HIGH_ALCHEMY ? totalHaValue : totalGeValue;
	}
}
