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
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable drop-rate index for one source. It owns the tooltip policy: independently named
 * tables can be shown together, while several alternatives inside one table remain delegated to
 * the full Wiki page where their conditions can be explained safely.
 */
public final class WikiDropTable
{
	private static final int MAX_CACHED_ITEMS = 10_000;
	private static final int MAX_CACHED_RATES_PER_ITEM = 100;
	private static final int MAX_TOOLTIP_RATES = 4;
	private static final String MULTIPLE_RATES = "Multiple rates - right-click to open Wiki";
	private static final String ADDITIONAL_RATES = "Additional rates - right-click to open Wiki";
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
			return Collections.singletonList(rates.get(0).format());
		}

		// Multiple rows within the same table are usually quantity/condition alternatives rather
		// than independent shared tables. Keep deferring those cases to the full Wiki page.
		Map<String, Integer> ratesPerTable = new HashMap<>();
		for (WikiDropRate rate : rates)
		{
			String table = rate.getTableLabel();
			int count = ratesPerTable.merge(table, 1, Integer::sum);
			if (count > 1)
			{
				return Collections.singletonList(MULTIPLE_RATES);
			}
		}

		List<String> lines = new ArrayList<>(Math.min(rates.size(), MAX_TOOLTIP_RATES) + 1);
		for (int index = 0; index < rates.size() && index < MAX_TOOLTIP_RATES; index++)
		{
			lines.add(rates.get(index).format());
		}
		if (rates.size() > MAX_TOOLTIP_RATES)
		{
			lines.add(ADDITIONAL_RATES);
		}
		return Collections.unmodifiableList(lines);
	}

	public boolean isEmpty()
	{
		return ratesByItem.isEmpty();
	}

	static WikiDropTable merge(List<WikiDropTable> tables)
	{
		Map<String, List<WikiDropRate>> merged = new LinkedHashMap<>();
		for (WikiDropTable table : tables)
		{
			for (Map.Entry<String, List<WikiDropRate>> entry : table.ratesByItem.entrySet())
			{
				List<WikiDropRate> rates = merged.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>());
				for (WikiDropRate rate : entry.getValue())
				{
					if (!rates.contains(rate))
					{
						rates.add(rate);
					}
				}
			}
		}
		return new WikiDropTable(merged);
	}

	/**
	 * Selects one variant section while retaining shared conditional tables. If the Wiki markup does
	 * not contain the requested label, the unfiltered table is returned instead of hiding rates.
	 */
	WikiDropTable selectContext(String requestedContext)
	{
		if (requestedContext == null || requestedContext.trim().isEmpty())
		{
			return this;
		}
		if (!hasContext(requestedContext))
		{
			return this;
		}

		Map<String, List<WikiDropRate>> selected = new LinkedHashMap<>();
		for (Map.Entry<String, List<WikiDropRate>> entry : ratesByItem.entrySet())
		{
			List<WikiDropRate> retained = new ArrayList<>();
			for (WikiDropRate rate : entry.getValue())
			{
				if (rate.getTableLabel().equalsIgnoreCase(requestedContext)
					|| isSharedTable(rate.getTableLabel()))
				{
					retained.add(rate);
				}
			}
			if (!retained.isEmpty())
			{
				selected.put(entry.getKey(), retained);
			}
		}
		return new WikiDropTable(selected);
	}

	boolean hasContext(String context)
	{
		return context != null && ratesByItem.values().stream()
			.flatMap(List::stream)
			.anyMatch(rate -> rate.getTableLabel().equalsIgnoreCase(context));
	}

	WikiDropTable selectBaseContext()
	{
		Map<String, List<WikiDropRate>> selected = new LinkedHashMap<>();
		for (Map.Entry<String, List<WikiDropRate>> entry : ratesByItem.entrySet())
		{
			List<WikiDropRate> retained = new ArrayList<>();
			for (WikiDropRate rate : entry.getValue())
			{
				if (rate.getTableLabel().isEmpty() || isSharedTable(rate.getTableLabel()))
				{
					retained.add(rate);
				}
			}
			if (!retained.isEmpty())
			{
				selected.put(entry.getKey(), retained);
			}
		}
		return selected.isEmpty() ? this : new WikiDropTable(selected);
	}

	private static boolean isSharedTable(String label)
	{
		String normalized = label.toLowerCase(Locale.ENGLISH);
		return normalized.equals("rare drop table")
			|| normalized.equals("gem drop table")
			|| normalized.equals("rare/gem table");
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
