package com.searchableloottracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.searchableloottracker.model.LootSourceId;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.LinkedHashSet;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.Test;

public class LootDataServicePersistenceTest
{
	private static final String PROFILE = "test-profile";
	private static final String CORE_KEY = "drops_NPC_Gnome";
	private static final String CORE_JSON = "{\"type\":\"NPC\",\"name\":\"Gnome\","
		+ "\"kills\":7,\"last\":\"2026-08-23T12:00:00Z\",\"drops\":[]}";

	@Test
	public void importsCoreHistoryOnceWithoutMutatingCoreConfiguration()
	{
		FakeProfileConfiguration configuration = withCoreLoot();
		LootDataService service = service(configuration);

		service.importLootTrackerHistoryOnce(PROFILE);

		String extendedKey = LootDataService.historyKey(new LootSourceId("NPC", "Gnome"));
		String imported = configuration.get(SearchableLootTrackerConfig.GROUP, PROFILE, extendedKey);
		assertNotNull(imported);
		assertTrue(imported.contains("\"version\":2"));
		assertEquals(CORE_JSON, configuration.get(LootDataService.LOOT_TRACKER_GROUP, PROFILE, CORE_KEY));
		assertEquals(LootDataService.IMPORT_VERSION, configuration.get(
			SearchableLootTrackerConfig.GROUP, PROFILE, LootDataService.IMPORT_VERSION_KEY));
		assertFalse(configuration.mutations.stream()
			.anyMatch(operation -> operation.contains("|" + LootDataService.LOOT_TRACKER_GROUP + "|")));

		int mutationCount = configuration.mutations.size();
		service.importLootTrackerHistoryOnce(PROFILE);
		assertEquals(mutationCount, configuration.mutations.size());
	}

	@Test
	public void resetSourceDeletesOnlyExtendedHistoryAndDoesNotReimportIt()
	{
		FakeProfileConfiguration configuration = withCoreLoot();
		LootDataService service = service(configuration);
		service.reloadFromActiveProfile(true, 0, 0);
		assertEquals(1, service.snapshot().size());

		service.resetSource(new LootSourceId("NPC", "Gnome"));
		service.reloadFromActiveProfile(true, 0, 0);

		assertTrue(service.snapshot().isEmpty());
		assertEquals(CORE_JSON, configuration.get(LootDataService.LOOT_TRACKER_GROUP, PROFILE, CORE_KEY));
		assertEquals(LootDataService.IMPORT_VERSION, configuration.get(
			SearchableLootTrackerConfig.GROUP, PROFILE, LootDataService.IMPORT_VERSION_KEY));
		assertFalse(configuration.mutations.stream()
			.anyMatch(operation -> operation.contains("|" + LootDataService.LOOT_TRACKER_GROUP + "|")));
	}

	@Test
	public void resetAllPreservesImportMarkerAndCoreHistory()
	{
		FakeProfileConfiguration configuration = withCoreLoot();
		LootDataService service = service(configuration);
		service.reloadFromActiveProfile(true, 0, 0);

		service.resetAllHistory();
		service.reloadFromActiveProfile(true, 0, 0);

		assertTrue(service.snapshot().isEmpty());
		assertEquals(CORE_JSON, configuration.get(LootDataService.LOOT_TRACKER_GROUP, PROFILE, CORE_KEY));
		assertEquals(LootDataService.IMPORT_VERSION, configuration.get(
			SearchableLootTrackerConfig.GROUP, PROFILE, LootDataService.IMPORT_VERSION_KEY));
		assertFalse(configuration.mutations.stream()
			.anyMatch(operation -> operation.contains("|" + LootDataService.LOOT_TRACKER_GROUP + "|")));
	}

	@Test
	public void incompleteImportDoesNotOverwriteAnExistingExtendedRecord()
	{
		FakeProfileConfiguration configuration = withCoreLoot();
		String extendedKey = LootDataService.historyKey(new LootSourceId("NPC", "Gnome"));
		String extendedJson = CORE_JSON.replace("\"kills\":7", "\"kills\":99");
		configuration.values.put(configuration.key(
			SearchableLootTrackerConfig.GROUP, PROFILE, extendedKey), extendedJson);

		service(configuration).importLootTrackerHistoryOnce(PROFILE);

		assertEquals(extendedJson, configuration.get(
			SearchableLootTrackerConfig.GROUP, PROFILE, extendedKey));
		assertEquals(LootDataService.IMPORT_VERSION, configuration.get(
			SearchableLootTrackerConfig.GROUP, PROFILE, LootDataService.IMPORT_VERSION_KEY));
	}

	@Test
	public void newLootIsPersistedOnlyToExtendedHistory()
	{
		FakeProfileConfiguration configuration = withCoreLoot();
		LootDataService service = service(configuration);
		service.reloadFromActiveProfile(true, 0, 0);
		configuration.mutations.clear();

		service.add(new LootReceived("Goblin", 2, LootRecordType.NPC,
			java.util.Collections.emptyList(), 1, null));

		assertTrue(configuration.mutations.stream()
			.anyMatch(operation -> operation.startsWith("set|" + SearchableLootTrackerConfig.GROUP + "|history_")));
		assertFalse(configuration.mutations.stream()
			.anyMatch(operation -> operation.contains("|" + LootDataService.LOOT_TRACKER_GROUP + "|")));
	}

	@Test
	public void exactNpcVariantsArePersistedWithoutSplittingTheDisplayedSource()
	{
		FakeProfileConfiguration configuration = new FakeProfileConfiguration(PROFILE);
		configuration.values.put(configuration.key(SearchableLootTrackerConfig.GROUP, PROFILE,
			LootDataService.IMPORT_VERSION_KEY), LootDataService.IMPORT_VERSION);
		LootDataService service = service(configuration);
		service.reloadFromActiveProfile(true, 0, 0);

		service.add(new LootReceived("Greater demon", 104, LootRecordType.NPC,
			java.util.Collections.emptyList(), 1, 7871));
		service.add(new LootReceived("Greater demon", 104, LootRecordType.NPC,
			java.util.Collections.emptyList(), 1, 7872));
		service.reloadFromActiveProfile(true, 0, 0);

		assertEquals(1, service.snapshot().size());
		assertEquals(new LinkedHashSet<>(Arrays.asList(7871, 7872)),
			service.snapshot().get(0).getNpcIds());
	}

	@Test
	public void explicitReimportRestoresCoreHistoryWithoutChangingIt()
	{
		FakeProfileConfiguration configuration = withCoreLoot();
		LootDataService service = service(configuration);
		service.reloadFromActiveProfile(true, 0, 0);
		service.resetSource(new LootSourceId("NPC", "Gnome"));
		assertTrue(service.snapshot().isEmpty());
		configuration.mutations.clear();

		assertTrue(service.reimportLootTrackerHistory(PROFILE, true, 0, 0));

		assertEquals(1, service.snapshot().size());
		assertEquals("Gnome", service.snapshot().get(0).getName());
		assertEquals(7, service.snapshot().get(0).getCount());
		assertEquals(CORE_JSON, configuration.get(LootDataService.LOOT_TRACKER_GROUP, PROFILE, CORE_KEY));
		assertEquals(LootDataService.IMPORT_VERSION, configuration.get(
			SearchableLootTrackerConfig.GROUP, PROFILE, LootDataService.IMPORT_VERSION_KEY));
		assertFalse(configuration.mutations.stream()
			.anyMatch(operation -> operation.contains("|" + LootDataService.LOOT_TRACKER_GROUP + "|")));
	}

	@Test
	public void newLootMergesHistoryHiddenByAgeLimit()
	{
		FakeProfileConfiguration configuration = new FakeProfileConfiguration(PROFILE);
		putExtendedLoot(configuration, PROFILE, "NPC", "Gnome", 7,
			"2020-01-01T00:00:00Z", "[]");
		LootDataService service = service(configuration);

		service.reloadFromActiveProfile(true, 1, 0);
		assertTrue(service.snapshot().isEmpty());
		service.add(new LootReceived("Gnome", 1, LootRecordType.NPC,
			java.util.Collections.emptyList(), 1, null));
		service.reloadFromActiveProfile(true, 0, 0);

		assertEquals(1, service.snapshot().size());
		assertEquals(8, service.snapshot().get(0).getCount());
	}

	@Test
	public void newLootMergesHistoryHiddenByEntryLimit()
	{
		FakeProfileConfiguration configuration = new FakeProfileConfiguration(PROFILE);
		putExtendedLoot(configuration, PROFILE, "NPC", "Guard", 3,
			"2026-08-24T12:00:00Z", "[995,1]");
		putExtendedLoot(configuration, PROFILE, "NPC", "Gnome", 7,
			"2026-08-23T12:00:00Z", "[]");
		LootDataService service = service(configuration);

		service.reloadFromActiveProfile(true, 0, 1);
		LootDataDelta update = service.add(new LootReceived("Gnome", 1, LootRecordType.NPC,
			java.util.Collections.emptyList(), 1, null));

		assertEquals("Gnome", update.getHistorySource().getName());
		assertEquals(8, update.getHistorySource().getCount());
		String gnomeKey = LootDataService.historyKey(new LootSourceId("NPC", "Gnome"));
		assertTrue(configuration.get(SearchableLootTrackerConfig.GROUP, PROFILE, gnomeKey)
			.contains("\"kills\":8"));
	}

	@Test
	public void malformedHiddenHistoryStartsFreshSafely()
	{
		FakeProfileConfiguration configuration = new FakeProfileConfiguration(PROFILE);
		String key = LootDataService.historyKey(new LootSourceId("NPC", "Gnome"));
		configuration.values.put(configuration.key(
			SearchableLootTrackerConfig.GROUP, PROFILE, key), "{not-json");
		configuration.values.put(configuration.key(SearchableLootTrackerConfig.GROUP, PROFILE,
			LootDataService.IMPORT_VERSION_KEY), LootDataService.IMPORT_VERSION);
		LootDataService service = service(configuration);

		service.reloadFromActiveProfile(true, 0, 0);
		service.add(new LootReceived("Gnome", 1, LootRecordType.NPC,
			java.util.Collections.emptyList(), 1, null));
		service.reloadFromActiveProfile(true, 0, 0);

		assertEquals(1, service.snapshot().size());
		assertEquals(1, service.snapshot().get(0).getCount());
	}

	@Test
	public void reimportRefusesAChangedProfileWithoutMutation()
	{
		FakeProfileConfiguration configuration = withCoreLoot();
		LootDataService service = service(configuration);
		service.reloadFromActiveProfile(true, 0, 0);
		configuration.activeProfile = "different-profile";
		configuration.mutations.clear();

		assertFalse(service.reimportLootTrackerHistory(PROFILE, true, 0, 0));

		assertTrue(configuration.mutations.isEmpty());
		assertEquals(CORE_JSON, configuration.get(
			LootDataService.LOOT_TRACKER_GROUP, PROFILE, CORE_KEY));
	}

	@Test
	public void historyKeysAreStableAcrossCaseAndWhitespace()
	{
		assertEquals(
			LootDataService.historyKey(new LootSourceId("NPC", "Gnome")),
			LootDataService.historyKey(new LootSourceId("npc", "  GNOME  ")));
	}

	private static LootDataService service(FakeProfileConfiguration configuration)
	{
		// These fixtures contain no item IDs, so persistence tests never need ItemManager.
		return new LootDataService(configuration, null, runeLiteCompatibleGson());
	}

	private static Gson runeLiteCompatibleGson()
	{
		return new GsonBuilder().registerTypeAdapter(Instant.class, new TypeAdapter<Instant>()
		{
			@Override
			public void write(JsonWriter output, Instant value) throws IOException
			{
				output.value(value.toString());
			}

			@Override
			public Instant read(JsonReader input) throws IOException
			{
				return Instant.parse(input.nextString());
			}
		}).create();
	}

	private static FakeProfileConfiguration withCoreLoot()
	{
		FakeProfileConfiguration configuration = new FakeProfileConfiguration(PROFILE);
		configuration.values.put(configuration.key(LootDataService.LOOT_TRACKER_GROUP, PROFILE, CORE_KEY), CORE_JSON);
		return configuration;
	}

	private static void putExtendedLoot(FakeProfileConfiguration configuration, String profile,
		String type, String name, int kills, String last, String drops)
	{
		String key = LootDataService.historyKey(new LootSourceId(type, name));
		String json = "{\"version\":1,\"type\":\"" + type + "\",\"name\":\"" + name
			+ "\",\"kills\":" + kills + ",\"last\":\"" + last + "\",\"drops\":" + drops + "}";
		configuration.values.put(configuration.key(
			SearchableLootTrackerConfig.GROUP, profile, key), json);
		configuration.values.put(configuration.key(SearchableLootTrackerConfig.GROUP, profile,
			LootDataService.IMPORT_VERSION_KEY), LootDataService.IMPORT_VERSION);
	}

	private static final class FakeProfileConfiguration implements LootDataService.ProfileConfiguration
	{
		private String activeProfile;
		private final Map<String, String> values = new LinkedHashMap<>();
		private final List<String> mutations = new ArrayList<>();

		private FakeProfileConfiguration(String activeProfile)
		{
			this.activeProfile = activeProfile;
		}

		@Override
		public String getActiveProfileKey()
		{
			return activeProfile;
		}

		@Override
		public List<String> keys(String group, String profileKey, String prefix)
		{
			String start = key(group, profileKey, prefix);
			List<String> keys = new ArrayList<>();
			for (String stored : values.keySet())
			{
				if (stored.startsWith(start))
				{
					keys.add(stored.substring(key(group, profileKey, "").length()));
				}
			}
			return keys;
		}

		@Override
		public String get(String group, String profileKey, String key)
		{
			return values.get(key(group, profileKey, key));
		}

		@Override
		public void set(String group, String profileKey, String key, String value)
		{
			values.put(key(group, profileKey, key), value);
			mutations.add("set|" + group + "|" + key);
		}

		@Override
		public void unset(String group, String profileKey, String key)
		{
			values.remove(key(group, profileKey, key));
			mutations.add("unset|" + group + "|" + key);
		}

		@Override
		public String getActive(String group, String key)
		{
			return get(group, activeProfile, key);
		}

		@Override
		public void setActive(String group, String key, String value)
		{
			set(group, activeProfile, key, value);
		}

		private String key(String group, String profileKey, String key)
		{
			return group + '\0' + profileKey + '\0' + key;
		}
	}
}
