package com.searchableloottracker.wiki;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

/** A single quantity/rate combination as rendered in a Wiki drop-table row. */
final class WikiDropRate
{
	private final String quantity;
	private final String rate;
	private final String section;
	private final boolean rareGemTable;

	WikiDropRate(String quantity, String rate, String section, boolean rareGemTable)
	{
		this.quantity = quantity;
		this.rate = rate;
		this.section = section;
		this.rareGemTable = rareGemTable;
	}

	boolean isRareGemTable()
	{
		return rareGemTable;
	}

	String format(boolean includeTableLabel)
	{
		StringBuilder formatted = new StringBuilder(rate);
		if (!quantity.isEmpty() && !"N/A".equalsIgnoreCase(quantity))
		{
			formatted.append(" (x").append(formatQuantity(quantity)).append(')');
		}
		if (includeTableLabel && rareGemTable)
		{
			formatted.append(" (Rare/Gem Table)");
		}
		return formatted.toString();
	}

	private static String formatQuantity(String value)
	{
		// Wiki typography uses several Unicode dash variants; RuneLite's small
		// tooltip font renders some of them too low, so display a plain hyphen.
		return value.replace(" (noted)", " noted")
			.replace('\u2010', '-')
			.replace('\u2011', '-')
			.replace('\u2012', '-')
			.replace('\u2013', '-')
			.replace('\u2014', '-')
			.replace('\u2212', '-');
	}

	void write(DataOutput output) throws IOException
	{
		output.writeUTF(quantity);
		output.writeUTF(rate);
		output.writeUTF(section);
		output.writeBoolean(rareGemTable);
	}

	static WikiDropRate read(DataInput input) throws IOException
	{
		return new WikiDropRate(input.readUTF(), input.readUTF(), input.readUTF(), input.readBoolean());
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof WikiDropRate))
		{
			return false;
		}
		WikiDropRate rate = (WikiDropRate) other;
		return rareGemTable == rate.rareGemTable
			&& quantity.equals(rate.quantity)
			&& this.rate.equals(rate.rate)
			&& section.equals(rate.section);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(quantity, rate, section, rareGemTable);
	}
}
