package com.searchableloottracker.wiki;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps RuneLite source identities to the Wiki page that owns their drops. Most sources pass
 * through unchanged; the bundled data file contains the exceptional ownership relationships.
 */
final class WikiSourceResolver
{
	private static final String OVERRIDES_RESOURCE = "/wiki-source-overrides.json";
	private static final Pattern CLUE_SOURCE = Pattern.compile(
		"(?i)^clue scroll \\((beginner|easy|medium|hard|elite|master)\\)$");
	private static final List<OverrideEntry> OVERRIDES = loadOverrides();

	private WikiSourceResolver()
	{
	}

	static WikiSourceResolution resolve(String sourceType, String sourceName, Integer npcId)
	{
		String trimmedName = sourceName.trim();
		String normalizedType = sourceType.trim().toUpperCase(Locale.ENGLISH);
		Integer validNpcId = npcId != null && npcId >= 0 ? npcId : null;
		OverrideEntry best = null;
		for (OverrideEntry candidate : OVERRIDES)
		{
			if (candidate.matches(normalizedType, trimmedName, validNpcId)
				&& (best == null || candidate.isMoreSpecificThan(best)))
			{
				best = candidate;
			}
		}
		if (best != null)
		{
			return new WikiSourceResolution(best.pageTitle, best.section,
				best.retainNpcId ? validNpcId : null, best.tableContext);
		}

		Matcher clue = CLUE_SOURCE.matcher(trimmedName);
		if (clue.matches())
		{
			return new WikiSourceResolution(
				"Reward casket (" + clue.group(1).toLowerCase(Locale.ENGLISH) + ")",
				"Rewards", null, null);
		}
		return new WikiSourceResolution(trimmedName, "Drops", validNpcId, null);
	}

	/**
	 * Returns every plausible owner page for ID-less history. Exact-ID overrides may opt into this
	 * list when RuneLite groups several independently documented monsters under one display name.
	 */
	static List<WikiSourceResolution> resolveNameCandidates(String sourceType, String sourceName)
	{
		String trimmedName = sourceName.trim();
		String normalizedType = sourceType.trim().toUpperCase(Locale.ENGLISH);
		Map<String, WikiSourceResolution> candidates = new LinkedHashMap<>();
		WikiSourceResolution primary = resolve(sourceType, sourceName, null);
		candidates.put(primary.getPageTitle().toLowerCase(Locale.ENGLISH), primary);
		for (OverrideEntry entry : OVERRIDES)
		{
			if (entry.includeForNameLookup && entry.matchesSource(normalizedType, trimmedName))
			{
				WikiSourceResolution candidate = new WikiSourceResolution(
					entry.pageTitle, entry.section, null, null);
				candidates.putIfAbsent(
					candidate.getPageTitle().toLowerCase(Locale.ENGLISH), candidate);
			}
		}
		return Collections.unmodifiableList(new ArrayList<>(candidates.values()));
	}

	private static List<OverrideEntry> loadOverrides()
	{
		try (InputStream input = WikiSourceResolver.class.getResourceAsStream(OVERRIDES_RESOURCE))
		{
			if (input == null)
			{
				throw new IllegalStateException("Missing " + OVERRIDES_RESOURCE);
			}
			OverrideEntry[] entries = new Gson().fromJson(
				new InputStreamReader(input, StandardCharsets.UTF_8), OverrideEntry[].class);
			List<OverrideEntry> loaded = new ArrayList<>();
			if (entries != null)
			{
				Collections.addAll(loaded, entries);
			}
			for (OverrideEntry entry : loaded)
			{
				entry.validate();
			}
			return Collections.unmodifiableList(loaded);
		}
		catch (Exception exception)
		{
			throw new ExceptionInInitializerError(exception);
		}
	}

	private static final class OverrideEntry
	{
		private String sourceType;
		private String sourceName;
		private int[] npcIds;
		private String pageTitle;
		private String section = "Drops";
		private boolean retainNpcId;
		private String tableContext;
		private boolean includeForNameLookup;

		private void validate()
		{
			if (sourceType == null || sourceName == null || pageTitle == null || section == null
				|| sourceType.trim().isEmpty() || sourceName.trim().isEmpty()
				|| pageTitle.trim().isEmpty() || section.trim().isEmpty())
			{
				throw new IllegalStateException("Incomplete Wiki source override");
			}
		}

		private boolean matches(String type, String name, Integer npcId)
		{
			if (!matchesSource(type, name))
			{
				return false;
			}
			if (npcIds == null || npcIds.length == 0)
			{
				return true;
			}
			if (npcId == null)
			{
				return false;
			}
			for (int candidate : npcIds)
			{
				if (candidate == npcId)
				{
					return true;
				}
			}
			return false;
		}

		private boolean matchesSource(String type, String name)
		{
			return sourceType.trim().equalsIgnoreCase(type)
				&& sourceName.trim().equalsIgnoreCase(name);
		}

		private boolean isMoreSpecificThan(OverrideEntry other)
		{
			return npcIds != null && npcIds.length > 0
				&& (other.npcIds == null || other.npcIds.length == 0);
		}
	}
}
