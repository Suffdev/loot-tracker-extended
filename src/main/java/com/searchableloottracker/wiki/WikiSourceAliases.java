package com.searchableloottracker.wiki;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Maps RuneLite event labels to the Wiki page that actually owns the loot. */
final class WikiSourceAliases
{
	private static final Map<String, String> ALIASES;

	static
	{
		Map<String, String> aliases = new LinkedHashMap<>();
		aliases.put("dusk", "Grotesque Guardians");
		ALIASES = Collections.unmodifiableMap(aliases);
	}

	private WikiSourceAliases()
	{
	}

	static String resolve(String sourceName)
	{
		String trimmed = sourceName.trim();
		return ALIASES.getOrDefault(trimmed.toLowerCase(Locale.ENGLISH), sourceName);
	}
}
