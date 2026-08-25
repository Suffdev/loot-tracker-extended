package com.searchableloottracker.search;

import com.searchableloottracker.model.LootItem;
import com.searchableloottracker.model.LootSource;
import java.util.List;
import net.runelite.client.plugins.loottracker.LootTrackerPriceType;

/** A matching source plus the projected item subset used for filtered totals. */
public final class LootSearchResult
{
	private final LootSource source;
	private final List<LootItem> visibleItems;
	private final long totalQuantity;
	private final long totalGeValue;
	private final long totalHaValue;

	LootSearchResult(LootSource source, List<LootItem> visibleItems)
	{
		this.source = source;
		this.visibleItems = visibleItems;
		long quantity = 0;
		long geValue = 0;
		long haValue = 0;
		for (LootItem item : visibleItems)
		{
			quantity += item.getQuantity();
			geValue += item.getTotalGeValue();
			haValue += item.getTotalHaValue();
		}
		this.totalQuantity = quantity;
		this.totalGeValue = geValue;
		this.totalHaValue = haValue;
	}

	public LootSource getSource()
	{
		return source;
	}

	public List<LootItem> getVisibleItems()
	{
		return visibleItems;
	}

	public long getTotalQuantity()
	{
		return totalQuantity;
	}

	public long getTotalValue(LootTrackerPriceType priceType)
	{
		return priceType == LootTrackerPriceType.HIGH_ALCHEMY
			? totalHaValue : totalGeValue;
	}
}
