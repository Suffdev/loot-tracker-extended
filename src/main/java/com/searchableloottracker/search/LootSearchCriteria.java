package com.searchableloottracker.search;

import java.util.Collections;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.runelite.client.plugins.loottracker.LootTrackerPriceType;

/** Normalized, immutable search and display policy supplied to {@link LootSearch}. */
public final class LootSearchCriteria
{
	private final String query;
	private final SearchMode mode;
	private final LootSortOrder sortOrder;
	private final SourceTypeFilter sourceType;
	private final LootTrackerPriceType priceType;
	private final boolean showIgnored;
	private final Set<String> ignoredItems;
	private final Set<String> ignoredSources;

	public LootSearchCriteria(String query, SearchMode mode, LootSortOrder sortOrder,
		SourceTypeFilter sourceType, LootTrackerPriceType priceType, boolean showIgnored,
		Set<String> ignoredItems, Set<String> ignoredSources)
	{
		this(query, mode, sortOrder, sourceType, priceType, showIgnored,
			ignoredItems, ignoredSources, false);
	}

	private LootSearchCriteria(String query, SearchMode mode, LootSortOrder sortOrder,
		SourceTypeFilter sourceType, LootTrackerPriceType priceType, boolean showIgnored,
		Set<String> ignoredItems, Set<String> ignoredSources, boolean normalized)
	{
		this.query = normalize(query);
		this.mode = mode;
		this.sortOrder = sortOrder;
		this.sourceType = sourceType;
		this.priceType = priceType;
		this.showIgnored = showIgnored;
		this.ignoredItems = normalized ? ignoredItems : normalizeSet(ignoredItems);
		this.ignoredSources = normalized ? ignoredSources : normalizeSet(ignoredSources);
	}

	public static LootSearchCriteria withNormalizedIgnoredLoot(String query, SearchMode mode,
		LootSortOrder sortOrder, SourceTypeFilter sourceType, LootTrackerPriceType priceType,
		boolean showIgnored, Set<String> ignoredItems, Set<String> ignoredSources)
	{
		return new LootSearchCriteria(query, mode, sortOrder, sourceType, priceType, showIgnored,
			ignoredItems, ignoredSources, true);
	}

	public static Set<String> normalizeNames(Collection<String> values)
	{
		return normalizeSet(values);
	}

	public String getQuery()
	{
		return query;
	}

	public SearchMode getMode()
	{
		return mode;
	}

	public LootSortOrder getSortOrder()
	{
		return sortOrder;
	}

	public SourceTypeFilter getSourceType()
	{
		return sourceType;
	}

	public LootTrackerPriceType getPriceType()
	{
		return priceType;
	}

	public boolean isItemVisible(String normalizedName)
	{
		return showIgnored || !ignoredItems.contains(normalizedName);
	}

	public boolean isSourceVisible(String normalizedName)
	{
		return showIgnored || !ignoredSources.contains(normalizedName);
	}

	public boolean filtersItems()
	{
		return !showIgnored && !ignoredItems.isEmpty();
	}

	private static Set<String> normalizeSet(Collection<String> values)
	{
		Set<String> normalized = new HashSet<>();
		for (String value : values)
		{
			normalized.add(normalize(value));
		}
		return Collections.unmodifiableSet(normalized);
	}

	private static String normalize(String value)
	{
		return value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
	}
}
