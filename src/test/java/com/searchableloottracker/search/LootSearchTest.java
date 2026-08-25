package com.searchableloottracker.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.searchableloottracker.model.LootItem;
import com.searchableloottracker.model.LootSource;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;
import net.runelite.client.plugins.loottracker.LootTrackerPriceType;

public class LootSearchTest
{
	@Test
	public void allSourceFilterUsesConciseLabel()
	{
		assertEquals("All", SourceTypeFilter.ALL.toString());
	}

	private final List<LootSource> sources = Arrays.asList(
		new LootSource("Vorkath", "NPC", 10, Arrays.asList(
			new LootItem(11230, "Dragon dart tip", 120, 1_000, 300),
			new LootItem(995, "Coins", 50_000, 1, 1))),
		new LootSource("Zulrah", "NPC", 4, Collections.singletonList(
			new LootItem(12934, "Zulrah's scales", 500, 100, 60)))
	);

	@Test
	public void sourceSearchReturnsAllDropsForMatchingSource()
	{
		List<LootSearchResult> results = LootSearch.search(sources, "vork", SearchMode.SOURCE);

		assertEquals(1, results.size());
		assertEquals("Vorkath", results.get(0).getSource().getName());
		assertEquals(2, results.get(0).getVisibleItems().size());
		assertSame(sources.get(0).getItems(), results.get(0).getVisibleItems());
	}

	@Test
	public void normalizedIgnoredLootCanBeReusedAcrossSearches()
	{
		java.util.Set<String> ignoredItems = LootSearchCriteria.normalizeNames(
			Collections.singletonList(" Coins "));
		LootSearchCriteria criteria = LootSearchCriteria.withNormalizedIgnoredLoot("vorkath",
			SearchMode.SOURCE, LootSortOrder.MOST_RECENT, SourceTypeFilter.ALL,
			LootTrackerPriceType.GRAND_EXCHANGE, false, ignoredItems, Collections.emptySet());

		LootSearchResult result = LootSearch.search(sources, criteria).get(0);

		assertEquals(1, result.getVisibleItems().size());
		assertEquals("Dragon dart tip", result.getVisibleItems().get(0).getName());
	}

	@Test
	public void dropSearchReturnsOnlyMatchingDropsAcrossSources()
	{
		List<LootSearchResult> results = LootSearch.search(sources, "dragon dart", SearchMode.DROP);

		assertEquals(1, results.size());
		assertEquals(1, results.get(0).getVisibleItems().size());
		assertEquals("Dragon dart tip", results.get(0).getVisibleItems().get(0).getName());
	}

	@Test
	public void emptySearchReturnsEverySource()
	{
		assertEquals(2, LootSearch.search(sources, " ", SearchMode.DROP).size());
	}

	@Test
	public void itemsAreOrderedByTrackedGeValueLikeLootTracker()
	{
		LootSearchResult vorkath = LootSearch.search(sources, "vorkath", SearchMode.SOURCE).get(0);

		assertEquals("Dragon dart tip", vorkath.getVisibleItems().get(0).getName());
		assertEquals("Coins", vorkath.getVisibleItems().get(1).getName());
	}

	@Test
	public void sourcesAreOrderedByMostRecentLoot()
	{
		LootSource older = new LootSource("Abyssal demon", "NPC", 1,
			Instant.parse("2026-01-01T00:00:00Z"), Collections.singletonList(
				new LootItem(995, "Coins", 1, 1, 1)));
		LootSource newer = new LootSource("Zulrah", "NPC", 1,
			Instant.parse("2026-02-01T00:00:00Z"), Collections.singletonList(
				new LootItem(995, "Coins", 1, 1, 1)));

		List<LootSearchResult> results = LootSearch.search(Arrays.asList(older, newer), "", SearchMode.SOURCE);

		assertEquals("Zulrah", results.get(0).getSource().getName());
		assertEquals("Abyssal demon", results.get(1).getSource().getName());
	}

	@Test
	public void dropSearchAggregatesQuantitiesAndGeValueAcrossSources()
	{
		LootSource first = new LootSource("Guard", "NPC", 20, Collections.singletonList(
			new LootItem(1215, "Dragon dagger", 2, 18_000, 12_000)));
		LootSource second = new LootSource("Ninja impling", "EVENT", 10, Collections.singletonList(
			new LootItem(1215, "Dragon dagger", 5, 18_000, 12_000)));

		List<LootSearchResult> results = LootSearch.search(Arrays.asList(first, second), "dragon dagger", SearchMode.DROP);

		assertEquals(2, results.size());
		assertEquals(2, results.get(0).getTotalQuantity());
		assertEquals(5, results.get(1).getTotalQuantity());
		assertEquals(7, LootSearch.totalQuantity(results));
		assertEquals(30, LootSearch.totalSourceCount(results));
		assertEquals(126_000, LootSearch.totalValue(results, LootTrackerPriceType.GRAND_EXCHANGE));
	}

	@Test
	public void sourceIsTheDefaultMode()
	{
		assertEquals(SearchMode.SOURCE, SearchMode.values()[0]);
	}

	@Test
	public void supportsLeastRecentAndAlphabeticalSorting()
	{
		LootSource alphabeticalFirst = new LootSource("Abyssal demon", "NPC", 1,
			Instant.parse("2026-02-01T00:00:00Z"), Collections.singletonList(
				new LootItem(995, "Coins", 1, 1, 1)));
		LootSource oldest = new LootSource("Zulrah", "NPC", 1,
			Instant.parse("2026-01-01T00:00:00Z"), Collections.singletonList(
				new LootItem(995, "Coins", 1, 1, 1)));

		List<LootSearchResult> leastRecent = LootSearch.search(Arrays.asList(alphabeticalFirst, oldest),
			criteria("", SearchMode.SOURCE, LootSortOrder.LEAST_RECENT, SourceTypeFilter.ALL,
				LootTrackerPriceType.GRAND_EXCHANGE, true, Collections.emptySet(), Collections.emptySet()));
		List<LootSearchResult> alphabetical = LootSearch.search(Arrays.asList(oldest, alphabeticalFirst),
			criteria("", SearchMode.SOURCE, LootSortOrder.ALPHABETICAL, SourceTypeFilter.ALL,
				LootTrackerPriceType.GRAND_EXCHANGE, true, Collections.emptySet(), Collections.emptySet()));

		assertEquals("Zulrah", leastRecent.get(0).getSource().getName());
		assertEquals("Abyssal demon", alphabetical.get(0).getSource().getName());
	}

	@Test
	public void sourceTypeFiltersUseRecordTypesAndKeepUnknownTypesInOther()
	{
		LootSource event = new LootSource("Barrows", "EVENT", 1, Collections.singletonList(
			new LootItem(995, "Coins", 1, 1, 1)));
		LootSource futureType = new LootSource("Future source", "FUTURE_TYPE", 1, Collections.singletonList(
			new LootItem(995, "Coins", 1, 1, 1)));
		List<LootSource> all = Arrays.asList(sources.get(0), event, futureType);

		assertEquals(1, LootSearch.search(all, criteria("", SearchMode.SOURCE, LootSortOrder.MOST_RECENT,
			SourceTypeFilter.EVENT, LootTrackerPriceType.GRAND_EXCHANGE, true,
			Collections.emptySet(), Collections.emptySet())).size());
		assertEquals("Future source", LootSearch.search(all, criteria("", SearchMode.SOURCE,
			LootSortOrder.MOST_RECENT, SourceTypeFilter.OTHER, LootTrackerPriceType.GRAND_EXCHANGE,
			true, Collections.emptySet(), Collections.emptySet())).get(0).getSource().getName());
	}

	@Test
	public void ignoredLootMirrorsVisibilitySetting()
	{
		HashSet<String> ignoredItems = new HashSet<>(Collections.singletonList("Coins"));
		HashSet<String> ignoredSources = new HashSet<>(Collections.singletonList("Zulrah"));
		LootSearchCriteria hidden = criteria("", SearchMode.SOURCE, LootSortOrder.MOST_RECENT,
			SourceTypeFilter.ALL, LootTrackerPriceType.GRAND_EXCHANGE, false, ignoredItems, ignoredSources);
		LootSearchCriteria shown = criteria("", SearchMode.SOURCE, LootSortOrder.MOST_RECENT,
			SourceTypeFilter.ALL, LootTrackerPriceType.GRAND_EXCHANGE, true, ignoredItems, ignoredSources);

		List<LootSearchResult> hiddenResults = LootSearch.search(sources, hidden);
		assertEquals(1, hiddenResults.size());
		assertEquals(1, hiddenResults.get(0).getVisibleItems().size());
		assertEquals("Dragon dart tip", hiddenResults.get(0).getVisibleItems().get(0).getName());
		assertEquals(2, LootSearch.search(sources, shown).size());
	}

	@Test
	public void highAlchemyModeChangesItemOrderingAndTotals()
	{
		LootSearchCriteria highAlchemy = criteria("vorkath", SearchMode.SOURCE, LootSortOrder.MOST_RECENT,
			SourceTypeFilter.ALL, LootTrackerPriceType.HIGH_ALCHEMY, true,
			Collections.emptySet(), Collections.emptySet());
		LootSearchResult result = LootSearch.search(sources, highAlchemy).get(0);

		assertEquals("Coins", result.getVisibleItems().get(0).getName());
		assertEquals(86_000, result.getTotalValue(LootTrackerPriceType.HIGH_ALCHEMY));
		assertSame(result.getSource().getItems(LootTrackerPriceType.HIGH_ALCHEMY),
			result.getSource().getItems(LootTrackerPriceType.HIGH_ALCHEMY));
	}

	@Test
	public void dropModeDoesNotMatchANameOfASource()
	{
		LootSource dragon = new LootSource("Dragon", "NPC", 1, Collections.singletonList(
			new LootItem(995, "Coins", 10, 1, 1)));

		assertTrue(LootSearch.search(Collections.singletonList(dragon), "dragon", SearchMode.DROP).isEmpty());
	}

	@Test
	public void dropSummaryAggregatesQuantityAndValueAcrossSources()
	{
		LootSource first = new LootSource("First", "NPC", 1, Collections.singletonList(
			new LootItem(995, "Coins", 10, 1, 1)));
		LootSource second = new LootSource("Second", "EVENT", 1, Collections.singletonList(
			new LootItem(995, "Coins", 20, 1, 1)));
		List<LootSearchResult> results = LootSearch.search(Arrays.asList(first, second), "coins", SearchMode.DROP);

		assertEquals(2, results.size());
		assertEquals(30, LootSearch.totalQuantity(results));
		assertEquals(30, LootSearch.totalValue(results, LootTrackerPriceType.GRAND_EXCHANGE));
	}

	private static LootSearchCriteria criteria(String query, SearchMode mode, LootSortOrder order,
		SourceTypeFilter sourceType, LootTrackerPriceType priceType, boolean showIgnored,
		java.util.Set<String> ignoredItems, java.util.Set<String> ignoredSources)
	{
		return new LootSearchCriteria(query, mode, order, sourceType, priceType, showIgnored,
			ignoredItems, ignoredSources);
	}
}
