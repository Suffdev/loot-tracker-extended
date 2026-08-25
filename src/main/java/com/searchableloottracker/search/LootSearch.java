package com.searchableloottracker.search;

import com.searchableloottracker.model.LootItem;
import com.searchableloottracker.model.LootSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.runelite.client.plugins.loottracker.LootTrackerPriceType;

/**
 * Pure search/aggregation layer shared by grouped history and individual session views.
 * Inputs are immutable snapshots, so filtering can run without touching ConfigManager or Swing.
 */
public final class LootSearch
{
	private LootSearch()
	{
	}

	public static List<LootSearchResult> search(Collection<LootSource> sources, String query, SearchMode mode)
	{
		return search(sources, new LootSearchCriteria(query, mode, LootSortOrder.MOST_RECENT,
			SourceTypeFilter.ALL, LootTrackerPriceType.GRAND_EXCHANGE, true,
			Collections.emptySet(), Collections.emptySet()));
	}

	public static List<LootSearchResult> search(Collection<LootSource> sources, LootSearchCriteria criteria)
	{
		String needle = criteria.getQuery();
		List<LootSearchResult> results = new ArrayList<>();

		for (LootSource source : sources)
		{
			if (!criteria.getSourceType().matches(source.getType())
				|| !criteria.isSourceVisible(source.getNormalizedName()))
			{
				continue;
			}

			boolean sourceMatches = criteria.getMode() == SearchMode.SOURCE
				&& source.getNormalizedName().contains(needle);
			if (!needle.isEmpty() && criteria.getMode() == SearchMode.SOURCE && !sourceMatches)
			{
				continue;
			}

			List<LootItem> sourceItems = source.getItems(criteria.getPriceType());
			// Item-name searches keep their parent source but expose only matching item rows. This
			// lets totals represent matching quantities without losing the NPC/source breakdown.
			boolean matchingItemsOnly = !needle.isEmpty() && criteria.getMode() == SearchMode.DROP;
			List<LootItem> visibleItems;
			if (!criteria.filtersItems() && !matchingItemsOnly)
			{
				visibleItems = sourceItems;
			}
			else
			{
				List<LootItem> matchingItems = null;
				for (LootItem item : sourceItems)
				{
					if (criteria.isItemVisible(item.getNormalizedName())
						&& (!matchingItemsOnly || item.getNormalizedName().contains(needle)))
					{
						if (matchingItems == null)
						{
							matchingItems = new ArrayList<>();
						}
						matchingItems.add(item);
					}
				}
				visibleItems = matchingItems == null
					? Collections.emptyList() : Collections.unmodifiableList(matchingItems);
			}

			if (!visibleItems.isEmpty())
			{
				results.add(new LootSearchResult(source, visibleItems));
			}
		}

		results.sort(sourceComparator(criteria.getSortOrder()));
		return results;
	}

	public static long totalQuantity(Collection<LootSearchResult> results)
	{
		return results.stream().mapToLong(LootSearchResult::getTotalQuantity).sum();
	}

	public static long totalSourceCount(Collection<LootSearchResult> results)
	{
		return results.stream().mapToLong(result -> result.getSource().getCount()).sum();
	}

	public static long totalValue(Collection<LootSearchResult> results, LootTrackerPriceType priceType)
	{
		return results.stream().mapToLong(result -> result.getTotalValue(priceType)).sum();
	}

	private static Comparator<LootSearchResult> sourceComparator(LootSortOrder order)
	{
		Comparator<LootSearchResult> alphabetical = Comparator.comparing(
			result -> result.getSource().getName(), String.CASE_INSENSITIVE_ORDER);
		if (order == LootSortOrder.ALPHABETICAL)
		{
			return alphabetical;
		}
		Comparator<LootSearchResult> recent = Comparator.comparing(
			result -> result.getSource().getLastReceived());
		return (order == LootSortOrder.MOST_RECENT ? recent.reversed() : recent).thenComparing(alphabetical);
	}
}
