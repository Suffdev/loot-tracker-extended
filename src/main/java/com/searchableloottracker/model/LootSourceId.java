package com.searchableloottracker.model;

import java.util.Locale;
import java.util.Objects;

/** Stable source identity with display text preserved separately from canonical equality. */
public final class LootSourceId
{
	private final String type;
	private final String name;
	private final String canonicalType;
	private final String canonicalName;

	public LootSourceId(String type, String name)
	{
		this.type = Objects.requireNonNull(type);
		this.name = Objects.requireNonNull(name);
		this.canonicalType = type.trim().toUpperCase(Locale.ENGLISH);
		this.canonicalName = name.trim().toLowerCase(Locale.ENGLISH);
	}

	public String getType()
	{
		return type;
	}

	public String getName()
	{
		return name;
	}

	public String getCanonicalType()
	{
		return canonicalType;
	}

	public String getCanonicalName()
	{
		return canonicalName;
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof LootSourceId))
		{
			return false;
		}
		LootSourceId id = (LootSourceId) other;
		return canonicalType.equals(id.canonicalType) && canonicalName.equals(id.canonicalName);
	}

	@Override
	public int hashCode()
	{
		return 31 * canonicalType.hashCode() + canonicalName.hashCode();
	}
}
