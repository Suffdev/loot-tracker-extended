package com.searchableloottracker.wiki;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Converts the Wiki's rendered item-drop tables into the small immutable
 * representation used by tooltips. Parsing rendered markup avoids coupling
 * the rest of the plugin to Wiki templates and their intermediate notation.
 */
final class WikiDropTableParser
{
	private WikiDropTableParser()
	{
	}

	static WikiDropTable parse(String html)
	{
		return parse(Jsoup.parse(html));
	}

	static WikiDropTable parse(InputStream html) throws IOException
	{
		return parse(Jsoup.parse(html, null, ""));
	}

	private static WikiDropTable parse(Document document)
	{
		Map<String, List<WikiDropRate>> collectedRates = new LinkedHashMap<>();
		for (Element table : document.select("table.item-drops"))
		{
			parseTable(table, collectedRates);
		}
		return new WikiDropTable(collectedRates);
	}

	private static void parseTable(Element table, Map<String, List<WikiDropRate>> rates)
	{
		TableColumns columns = findColumns(table.selectFirst("tr:has(th)"));
		// The closest preceding heading owns the table and lets the UI distinguish
		// ordinary rates from Rare/Gem Drop Table rolls.
		String section = findSectionHeading(table);
		boolean rareGemTable = isRareGemTable(section);

		for (Element row : table.select("tr"))
		{
			Elements cells = row.select("td");
			if (!columns.areAvailable(cells.size()))
			{
				continue;
			}

			String itemName = parseItemName(cells.get(columns.item));
			String quantity = clean(cells.get(columns.quantity).text());
			String rarity = parseRarity(cells.get(columns.rarity));
			if (itemName.isEmpty() || rarity.isEmpty())
			{
				continue;
			}
			WikiDropRate rate = new WikiDropRate(quantity, rarity, section, rareGemTable);
			List<WikiDropRate> itemRates = rates.computeIfAbsent(
				WikiDropTable.normalize(itemName), ignored -> new ArrayList<>());
			if (!itemRates.contains(rate))
			{
				itemRates.add(rate);
			}
		}
	}

	private static String parseItemName(Element cell)
	{
		for (Element link : cell.select("a"))
		{
			String linkedName = clean(link.text());
			if (!linkedName.isEmpty())
			{
				return linkedName;
			}
		}
		return clean(cell.text()).replaceFirst("(?i)\\s*\\(m\\)$", "");
	}

	private static TableColumns findColumns(Element header)
	{
		int item = 0;
		int quantity = 1;
		int rarity = 2;
		if (header == null)
		{
			return new TableColumns(item, quantity, rarity);
		}

		int column = 0;
		for (Element heading : header.select("th"))
		{
			int span = parsePositiveInt(heading.attr("colspan"), 1);
			String label = clean(heading.text()).toLowerCase();
			if (label.equals("item") || label.equals("drop"))
			{
				item = column + span - 1;
			}
			else if (label.contains("quantity") || label.contains("amount"))
			{
				quantity = column;
			}
			else if (label.contains("rarity") || label.contains("chance") || label.contains("rate"))
			{
				rarity = column;
			}
			column += span;
		}
		return new TableColumns(item, quantity, rarity);
	}

	private static String parseRarity(Element cell)
	{
		Elements dropRates = cell.select("[data-drop-fraction]");
		if (dropRates.isEmpty())
		{
			return clean(cell.text());
		}

		List<String> formatted = new ArrayList<>();
		for (Element dropRate : dropRates)
		{
			String fraction = clean(dropRate.attr("data-drop-fraction"));
			String oneOver = clean(dropRate.attr("data-drop-oneover"));
			String display = startsWithOneOver(fraction) || oneOver.isEmpty() ? fraction : oneOver;
			if (!display.isEmpty() && !formatted.contains(display))
			{
				formatted.add(display);
			}
		}
		return String.join("; ", formatted);
	}

	private static boolean startsWithOneOver(String rate)
	{
		return rate.matches("^1\\s*/.*");
	}

	private static String findSectionHeading(Element table)
	{
		Element level = table;
		while (level != null && !"body".equals(level.tagName()))
		{
			for (Element sibling = level.previousElementSibling(); sibling != null;
				sibling = sibling.previousElementSibling())
			{
				Elements headings = sibling.select("h2, h3, h4");
				if (sibling.is("h2, h3, h4"))
				{
					return clean(sibling.text());
				}
				if (!headings.isEmpty())
				{
					return clean(headings.last().text());
				}
			}
			level = level.parent();
		}
		return "";
	}

	private static boolean isRareGemTable(String section)
	{
		String normalized = section.toLowerCase();
		return normalized.contains("drop table")
			&& (normalized.contains("rare") || normalized.contains("gem"));
	}

	private static int parsePositiveInt(String value, int fallback)
	{
		try
		{
			int parsed = Integer.parseInt(value);
			return parsed > 0 ? parsed : fallback;
		}
		catch (NumberFormatException ignored)
		{
			return fallback;
		}
	}

	private static String clean(String value)
	{
		return value.replaceAll("\\[[^]]*]", "")
			.replace('\u00a0', ' ')
			.trim()
			.replaceAll("\\s+", " ");
	}

	private static final class TableColumns
	{
		private final int item;
		private final int quantity;
		private final int rarity;

		private TableColumns(int item, int quantity, int rarity)
		{
			this.item = item;
			this.quantity = quantity;
			this.rarity = rarity;
		}

		private boolean areAvailable(int cellCount)
		{
			return cellCount > Math.max(item, Math.max(quantity, rarity));
		}
	}
}
