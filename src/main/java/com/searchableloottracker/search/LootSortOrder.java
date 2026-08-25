package com.searchableloottracker.search;

public enum LootSortOrder
{
	MOST_RECENT("Most recent"),
	LEAST_RECENT("Least recent"),
	ALPHABETICAL("Alphabetical");

	private final String label;

	LootSortOrder(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
