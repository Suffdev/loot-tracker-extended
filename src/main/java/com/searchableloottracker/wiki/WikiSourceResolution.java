package com.searchableloottracker.wiki;

import java.util.Objects;

/** Immutable Wiki target selected for one RuneLite loot source. */
final class WikiSourceResolution
{
	private final String pageTitle;
	private final String section;
	private final Integer npcId;
	private final String tableContext;

	WikiSourceResolution(String pageTitle, String section, Integer npcId, String tableContext)
	{
		this.pageTitle = Objects.requireNonNull(pageTitle);
		this.section = Objects.requireNonNull(section);
		this.npcId = npcId;
		this.tableContext = tableContext;
	}

	String getPageTitle()
	{
		return pageTitle;
	}

	String getSection()
	{
		return section;
	}

	Integer getNpcId()
	{
		return npcId;
	}

	String getTableContext()
	{
		return tableContext;
	}
}
