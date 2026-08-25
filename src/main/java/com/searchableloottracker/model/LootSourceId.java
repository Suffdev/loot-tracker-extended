package com.searchableloottracker.model;

import java.util.Objects;

/** Stable source identity; names alone are not unique across record types. */
public final class LootSourceId
{
	private final String type;
	private final String name;

	public LootSourceId(String type, String name)
	{
		this.type = Objects.requireNonNull(type);
		this.name = Objects.requireNonNull(name);
	}

	public String getType()
	{
		return type;
	}

	public String getName()
	{
		return name;
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
		return type.equals(id.type) && name.equals(id.name);
	}

	@Override
	public int hashCode()
	{
		return 31 * type.hashCode() + name.hashCode();
	}
}
