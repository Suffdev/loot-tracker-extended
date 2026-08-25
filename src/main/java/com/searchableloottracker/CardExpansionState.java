package com.searchableloottracker;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps durable card expansion choices separate from temporary search presentation state.
 *
 * <p>Source cards are disposable Swing components, so their removal from the component cache
 * must not make a previously collapsed source look new. A search has its own expansion set so
 * matching cards can open automatically without overwriting the unfiltered view.</p>
 */
final class CardExpansionState
{
	private final Set<Object> knownCards = new LinkedHashSet<>();
	private final Set<Object> expandedCards = new LinkedHashSet<>();
	private final Set<Object> knownSearchCards = new LinkedHashSet<>();
	private final Set<Object> expandedSearchCards = new LinkedHashSet<>();
	private String searchIdentity;

	void registerDefaultExpanded(Object key)
	{
		if (knownCards.add(key))
		{
			expandedCards.add(key);
		}
	}

	void updateSearch(String identity, Collection<?> matchingKeys)
	{
		if (identity == null)
		{
			searchIdentity = null;
			knownSearchCards.clear();
			expandedSearchCards.clear();
			return;
		}

		if (!Objects.equals(searchIdentity, identity))
		{
			searchIdentity = identity;
			knownSearchCards.clear();
			expandedSearchCards.clear();
		}
		for (Object key : matchingKeys)
		{
			if (knownSearchCards.add(key))
			{
				expandedSearchCards.add(key);
			}
		}
	}

	boolean isExpanded(Object key)
	{
		return activeExpandedCards().contains(key);
	}

	void setExpanded(Object key, boolean expanded)
	{
		Set<Object> activeCards = activeExpandedCards();
		if (expanded)
		{
			activeCards.add(key);
		}
		else
		{
			activeCards.remove(key);
		}
	}

	void remove(Object key)
	{
		knownCards.remove(key);
		expandedCards.remove(key);
		knownSearchCards.remove(key);
		expandedSearchCards.remove(key);
	}

	void clear()
	{
		knownCards.clear();
		expandedCards.clear();
		knownSearchCards.clear();
		expandedSearchCards.clear();
		searchIdentity = null;
	}

	private Set<Object> activeExpandedCards()
	{
		return searchIdentity == null ? expandedCards : expandedSearchCards;
	}
}
