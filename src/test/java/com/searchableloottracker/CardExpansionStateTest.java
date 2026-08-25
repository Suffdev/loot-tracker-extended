package com.searchableloottracker;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CardExpansionStateTest
{
	@Test
	public void searchExpansionDoesNotOverwriteCollapsedState()
	{
		CardExpansionState state = new CardExpansionState();
		state.registerDefaultExpanded("Vorkath");
		state.setExpanded("Vorkath", false);

		state.updateSearch("SOURCE:vork", Collections.singletonList("Vorkath"));
		assertTrue(state.isExpanded("Vorkath"));

		state.updateSearch(null, Collections.emptyList());
		assertFalse(state.isExpanded("Vorkath"));
	}

	@Test
	public void recreatingKnownCardDoesNotResetItsState()
	{
		CardExpansionState state = new CardExpansionState();
		state.registerDefaultExpanded("Zulrah");
		state.setExpanded("Zulrah", false);

		state.registerDefaultExpanded("Zulrah");

		assertFalse(state.isExpanded("Zulrah"));
	}

	@Test
	public void searchOnlyReopensCardsNewToThatQuery()
	{
		CardExpansionState state = new CardExpansionState();
		state.updateSearch("DROP:rune", Collections.singletonList("Vorkath"));
		state.setExpanded("Vorkath", false);

		state.updateSearch("DROP:rune", Arrays.asList("Vorkath", "Zulrah"));

		assertFalse(state.isExpanded("Vorkath"));
		assertTrue(state.isExpanded("Zulrah"));
	}

	@Test
	public void removingOneCardPreservesOtherExpansionChoices()
	{
		CardExpansionState state = new CardExpansionState();
		state.registerDefaultExpanded("Vorkath");
		state.registerDefaultExpanded("Zulrah");
		state.setExpanded("Zulrah", false);

		state.remove("Vorkath");

		assertFalse(state.isExpanded("Zulrah"));
	}
}
