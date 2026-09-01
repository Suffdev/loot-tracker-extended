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
		String levelTwo = "";
		String levelThree = "";
		String levelFour = "";
		for (Element element : document.select("h2, h3, h4, table.item-drops"))
		{
			if (element.is("h2"))
			{
				levelTwo = cleanHeading(element.text());
				levelThree = "";
				levelFour = "";
			}
			else if (element.is("h3"))
			{
				levelThree = cleanHeading(element.text());
				levelFour = "";
			}
			else if (element.is("h4"))
			{
				levelFour = cleanHeading(element.text());
			}
			else
			{
				parseTable(element, collectedRates,
					tableLabel(levelTwo, levelThree, levelFour),
					contextLabel(levelTwo, levelThree, levelFour));
			}
		}
		return new WikiDropTable(collectedRates);
	}

	private static void parseTable(Element table, Map<String, List<WikiDropRate>> rates,
		String tableLabel, String contextLabel)
	{
		TableColumns columns = findColumns(table.selectFirst("tr:has(th)"));

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
			WikiDropRate rate = new WikiDropRate(quantity, rarity, tableLabel, contextLabel);
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

	private static String cleanHeading(String heading)
	{
		return clean(heading).replaceFirst("(?i)\\s*\\[edit(?: \\| edit source)?]$", "");
	}

	private static String tableLabel(String levelTwo, String levelThree, String levelFour)
	{
		String local = !levelFour.isEmpty() ? levelFour
			: !levelThree.isEmpty() ? levelThree : levelTwo;
		String localLower = local.toLowerCase();
		if (localLower.contains("rare") && localLower.contains("gem")
			&& localLower.contains("table"))
		{
			return "Rare/Gem Table";
		}
		if (localLower.contains("rare") && localLower.contains("table"))
		{
			return "Rare Drop Table";
		}
		if (localLower.contains("gem") && localLower.contains("table"))
		{
			return "Gem Drop Table";
		}

		if (localLower.contains("wilderness slayer tertiary"))
		{
			return "Wilderness Slayer";
		}
		if (localLower.contains("drop table")
			|| (localLower.endsWith("tertiary") && !"tertiary".equals(localLower)))
		{
			return local;
		}
		return "";
	}

	/**
	 * Finds the variant/location heading that owns a table independently of its local category.
	 * Wiki pages commonly nest "Weapons and armour" beneath headings such as "Standard and
	 * Catacombs of Kourend"; retaining both levels prevents distinct variant rates from collapsing
	 * into one apparently ambiguous table.
	 */
	private static String contextLabel(String levelTwo, String levelThree, String levelFour)
	{
		String major = stripDropSuffix(levelTwo);
		if (!isGenericHeading(major) && tableLabel(levelTwo, "", "").isEmpty())
		{
			return major;
		}

		String secondary = stripDropSuffix(levelThree);
		if (!isGenericHeading(secondary) && tableLabel("", levelThree, "").isEmpty())
		{
			return secondary;
		}

		String tertiary = stripDropSuffix(levelFour);
		if (!isGenericHeading(tertiary) && tableLabel("", "", levelFour).isEmpty())
		{
			return tertiary;
		}
		return "";
	}

	private static String stripDropSuffix(String heading)
	{
		return heading.replaceFirst("(?i)^(?:drops|rewards)\\s*\\((.+)\\)$", "$1")
			.replaceFirst("(?i)\\s+(?:drops|rewards)$", "").trim();
	}

	private static boolean isGenericHeading(String heading)
	{
		switch (heading.toLowerCase())
		{
			case "":
			case "drops":
			case "drop table":
			case "rewards":
			case "100%":
			case "always":
			case "pre-roll":
			case "main drop":
			case "weapons":
			case "armour":
			case "weapons and armour":
			case "weapons and ammunition":
			case "equipment":
			case "runes":
			case "runes and ammunition":
			case "ammunition":
			case "herbs":
			case "seeds":
			case "food":
			case "potions":
			case "resources":
			case "ores and bars":
			case "gems":
			case "materials":
			case "other":
			case "miscellaneous":
			case "tertiary":
			case "unique":
			case "uniques":
			case "guaranteed":
				return true;
			default:
				return false;
		}
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
