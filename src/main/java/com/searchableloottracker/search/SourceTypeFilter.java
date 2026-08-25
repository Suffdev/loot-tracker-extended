package com.searchableloottracker.search;

import java.util.Locale;

public enum SourceTypeFilter
{
	ALL("All"),
	NPC("NPCs", "NPC"),
	EVENT("Activities", "EVENT"),
	PICKPOCKET("Pickpockets", "PICKPOCKET"),
	PLAYER("Players", "PLAYER"),
	OTHER("Other");

	private final String label;
	private final String recordType;

	SourceTypeFilter(String label)
	{
		this(label, null);
	}

	SourceTypeFilter(String label, String recordType)
	{
		this.label = label;
		this.recordType = recordType;
	}

	public boolean matches(String type)
	{
		String normalized = type.toUpperCase(Locale.ENGLISH);
		if (this == ALL)
		{
			return true;
		}
		if (this == OTHER)
		{
			return !(normalized.equals("NPC")
				|| normalized.equals("EVENT")
				|| normalized.equals("PICKPOCKET")
				|| normalized.equals("PLAYER"));
		}
		return recordType.equals(normalized);
	}

	@Override
	public String toString()
	{
		return label;
	}
}
