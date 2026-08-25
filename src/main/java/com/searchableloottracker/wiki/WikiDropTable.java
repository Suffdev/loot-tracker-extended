package com.searchableloottracker.wiki;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable drop-rate index for one source. It also owns the tooltip policy:
 * show an unambiguous rate, identify a single Rare/Gem table alternative, and
 * defer genuinely multi-rate cases to the Wiki page.
 */
public final class WikiDropTable
{
	private static final int MAX_CACHED_ITEMS = 10_000;
	private static final int MAX_CACHED_RATES_PER_ITEM = 100;
	private static final String MULTIPLE_RATES = "Multiple rates - right-click to open Wiki";
	private static final Pattern SCROLL_BOX = Pattern.compile(
		"^scroll box \\((beginner|easy|medium|hard|elite|master)\\)$");
	private final Map<String, List<WikiDropRate>> ratesByItem;

	WikiDropTable(Map<String, List<WikiDropRate>> ratesByItem)
	{
		Map<String, List<WikiDropRate>> copy = new LinkedHashMap<>();
		ratesByItem.forEach((name, rates) ->
			copy.put(name, Collections.unmodifiableList(new ArrayList<>(rates))));
		this.ratesByItem = Collections.unmodifiableMap(copy);
	}

	public List<String> getTooltipLines(String itemName)
	{
		List<WikiDropRate> rates = ratesByItem.get(normalize(itemName));
		if (rates == null || rates.isEmpty())
		{
			return Collections.emptyList();
		}
		if (rates.size() == 1)
		{
			WikiDropRate rate = rates.get(0);
			return Collections.singletonList(rate.format(rate.isRareGemTable()));
		}
		if (rates.size() == 2)
		{
			WikiDropRate first = rates.get(0);
			WikiDropRate second = rates.get(1);
			if (first.isRareGemTable() != second.isRareGemTable())
			{
				WikiDropRate main = first.isRareGemTable() ? second : first;
				WikiDropRate rareGem = first.isRareGemTable() ? first : second;
				List<String> lines = new ArrayList<>(2);
				lines.add(main.format(false));
				lines.add(rareGem.format(true));
				return Collections.unmodifiableList(lines);
			}
		}
		return Collections.singletonList(MULTIPLE_RATES);
	}

	public boolean isEmpty()
	{
		return ratesByItem.isEmpty();
	}

	void write(DataOutput output) throws IOException
	{
		output.writeInt(ratesByItem.size());
		for (Map.Entry<String, List<WikiDropRate>> entry : ratesByItem.entrySet())
		{
			output.writeUTF(entry.getKey());
			output.writeInt(entry.getValue().size());
			for (WikiDropRate rate : entry.getValue())
			{
				rate.write(output);
			}
		}
	}

	static WikiDropTable read(DataInput input) throws IOException
	{
		// Bounds make corrupt or hostile cache files fail before allocating an
		// unreasonable collection.
		int itemCount = input.readInt();
		if (itemCount < 0 || itemCount > MAX_CACHED_ITEMS)
		{
			throw new IOException("Invalid cached item count: " + itemCount);
		}
		Map<String, List<WikiDropRate>> rates = new LinkedHashMap<>();
		for (int itemIndex = 0; itemIndex < itemCount; itemIndex++)
		{
			String itemName = input.readUTF();
			int rateCount = input.readInt();
			if (rateCount < 0 || rateCount > MAX_CACHED_RATES_PER_ITEM)
			{
				throw new IOException("Invalid cached rate count: " + rateCount);
			}
			List<WikiDropRate> itemRates = new ArrayList<>(rateCount);
			for (int rateIndex = 0; rateIndex < rateCount; rateIndex++)
			{
				itemRates.add(WikiDropRate.read(input));
			}
			rates.put(itemName, itemRates);
		}
		return new WikiDropTable(rates);
	}

	static String normalize(String value)
	{
		String normalized = value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ENGLISH)
			.replaceFirst("\\s*\\(m\\)$", "");
		// Scroll boxes replace clue scrolls for players using clue juggling, but
		// both represent the same roll in an NPC's drop table.
		Matcher scrollBox = SCROLL_BOX.matcher(normalized);
		return scrollBox.matches() ? "clue scroll (" + scrollBox.group(1) + ")" : normalized;
	}
}
