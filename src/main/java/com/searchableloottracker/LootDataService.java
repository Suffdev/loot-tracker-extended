package com.searchableloottracker;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.searchableloottracker.model.LootItem;
import com.searchableloottracker.model.LootSource;
import com.searchableloottracker.model.LootSourceId;
import com.searchableloottracker.model.SessionLootRecord;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;

/**
 * Client-thread-confined store for persisted and session loot.
 *
 * <p>The service owns Loot Tracker Extended's per-profile history. It reads the built-in Loot
 * Tracker's {@code drops_*} records exactly once per profile to seed that history, but never writes
 * to or removes them. Session aggregates and the bounded individual-session ledger are deliberately
 * separate: aggregate totals remain exact even after old individual records are evicted.</p>
 */
@Slf4j
final class LootDataService
{
	static final String LOOT_TRACKER_GROUP = "loottracker";
	private static final String DROP_KEY_PREFIX = "drops_";
	// All mutable history keys and migration state live under SearchableLootTrackerConfig.GROUP.
	// These package-visible constants also let isolation tests assert that ownership boundary.
	static final String HISTORY_KEY_PREFIX = "history_";
	static final String IMPORT_VERSION_KEY = "historyImportVersion";
	static final String IMPORT_VERSION = "1";
	private static final int STORED_FORMAT_VERSION = 2;
	private static final String NPC_IDS_KEY = "observedNpcIds";
	private static final Type NPC_ID_MAP_TYPE = new TypeToken<Map<String, Integer>>() { }.getType();

	private final ProfileConfiguration profileConfiguration;
	private final ItemManager itemManager;
	private final Gson gson;
	private final Map<LootSourceId, MutableLootSource> sources = new LinkedHashMap<>();
	private final Map<LootSourceId, MutableLootSource> sessionSources = new LinkedHashMap<>();
	private final SessionLootLedger sessionLedger = new SessionLootLedger();
	private final Map<String, LootSourceId> configKeySources = new LinkedHashMap<>();
	private final Map<Integer, ItemDetails> itemDetailsCache = new LinkedHashMap<>();
	private final Map<String, Integer> observedNpcIds = new LinkedHashMap<>();
	private String activeProfileKey;

	@Inject
	LootDataService(ConfigManager configManager, ItemManager itemManager, Gson gson)
	{
		this(new RuneLiteProfileConfiguration(configManager), itemManager, gson);
	}

	LootDataService(ProfileConfiguration profileConfiguration, ItemManager itemManager, Gson gson)
	{
		this.profileConfiguration = profileConfiguration;
		this.itemManager = itemManager;
		this.gson = gson;
	}

	void reloadFromActiveProfile(boolean extendedHistory,
		int historyAgeDays, int maximumDropEntries)
	{
		// NPC IDs are optional enrichment. This loader is intentionally tolerant so a damaged
		// auxiliary cache can never prevent the primary Extended history from loading.
		loadObservedNpcIds();
		Map<LootSourceId, MutableLootSource> loaded = new LinkedHashMap<>();
		Map<String, LootSourceId> loadedKeys = new LinkedHashMap<>();
		String profileKey = profileConfiguration.getActiveProfileKey();
		activeProfileKey = profileKey;

		if (profileKey != null)
		{
			// Import is checked before reading Extended's store. Once marked, startup never consults
			// core history again unless the user explicitly requests a replacement import.
			importLootTrackerHistoryOnce(profileKey);
			List<LoadedLoot> candidates = new ArrayList<>();
			for (String key : profileConfiguration.keys(
				SearchableLootTrackerConfig.GROUP, profileKey, HISTORY_KEY_PREFIX))
			{
				MutableLootSource source = parseStoredLoot(key,
					profileConfiguration.get(SearchableLootTrackerConfig.GROUP, profileKey, key));
				if (source != null)
				{
					candidates.add(new LoadedLoot(key, source));
				}
			}

			// Retention selects the visible in-memory working set. It does not delete Extended's
			// persisted records, so changing a limit later can reveal history again.
			List<LoadedLoot> retained = extendedHistory
				? LootHistoryRetention.selectCustom(candidates, Instant.now(), historyAgeDays, maximumDropEntries,
					candidate -> candidate.source.lastReceived, candidate -> candidate.source.persistedDropCount)
				: LootHistoryRetention.select(candidates, Instant.now(),
					candidate -> candidate.source.lastReceived, candidate -> candidate.source.persistedDropCount);
			for (LoadedLoot entry : retained)
			{
				LootSourceId sourceId = entry.source.getId();
				loaded.put(sourceId, entry.source);
				loadedKeys.put(entry.key, sourceId);
			}
		}

		sources.clear();
		sources.putAll(loaded);
		configKeySources.clear();
		configKeySources.putAll(loadedKeys);
	}

	void importLootTrackerHistoryOnce(String profileKey)
	{
		String importedVersion = profileConfiguration.get(
			SearchableLootTrackerConfig.GROUP, profileKey, IMPORT_VERSION_KEY);
		if (IMPORT_VERSION.equals(importedVersion))
		{
			return;
		}

		Set<String> existingKeys = new LinkedHashSet<>(profileConfiguration.keys(
			SearchableLootTrackerConfig.GROUP, profileKey, HISTORY_KEY_PREFIX));
		try
		{
			// This is the only core Loot Tracker history access: every operation is a read.
			// The marker is written last, so a partial import is safely retried on next startup.
			for (String sourceKey : profileConfiguration.keys(
				LOOT_TRACKER_GROUP, profileKey, DROP_KEY_PREFIX))
			{
				MutableLootSource imported = parseStoredLoot(sourceKey,
					profileConfiguration.get(LOOT_TRACKER_GROUP, profileKey, sourceKey));
				if (imported == null)
				{
					continue;
				}
				String targetKey = historyKey(imported.getId());
				// A deterministic key makes retrying an interrupted import idempotent. An existing
				// Extended record always wins because it may contain newer post-import loot.
				if (existingKeys.add(targetKey))
				{
					persist(profileKey, targetKey, imported);
				}
			}
			profileConfiguration.set(SearchableLootTrackerConfig.GROUP,
				profileKey, IMPORT_VERSION_KEY, IMPORT_VERSION);
		}
		catch (RuntimeException exception)
		{
			// Leaving the marker absent makes the deterministic import retryable.
			log.warn("Unable to complete the one-time Loot Tracker history import", exception);
		}
	}

	LootDataDelta add(LootReceived event)
	{
		// One event updates three views: all-history aggregate, exact session aggregate, and the
		// bounded per-occurrence ledger used by individual session rendering.
		Instant receivedAt = Instant.now();
		String type = event.getType().name();
		LootSourceId key = new LootSourceId(type, event.getName());
		MutableLootSource source = sources.get(key);
		if (source == null)
		{
			// Retention limits remove records only from the visible working set. Merge the complete
			// owned aggregate lazily before writing, otherwise a new event would overwrite hidden
			// history at the same deterministic key.
			source = loadPersistedAggregate(key, receivedAt);
			sources.put(key, source);
		}
		MutableLootSource sessionSource = sessionSources.computeIfAbsent(key,
			ignored -> new MutableLootSource(event.getName(), type, 0, receivedAt));
		MutableLootSource occurrence = new MutableLootSource(
			event.getName(), type, event.getAmount(), receivedAt);
		if ("NPC".equals(type))
		{
			Integer npcId = extractNpcId(event.getMetadata());
			if (npcId == null)
			{
				npcId = observedNpcIds.get(normalizeName(event.getName()));
			}
			if (npcId != null)
			{
				source.addNpcId(npcId);
				sessionSource.addNpcId(npcId);
				occurrence.addNpcId(npcId);
				rememberNpcId(event.getName(), npcId);
			}
		}
		source.count = saturatedAdd(source.count, event.getAmount());
		sessionSource.count = saturatedAdd(sessionSource.count, event.getAmount());
		source.lastReceived = receivedAt;
		sessionSource.lastReceived = receivedAt;
		for (ItemStack item : event.getItems())
		{
			source.add(item.getId(), item.getQuantity());
			sessionSource.add(item.getId(), item.getQuantity());
			occurrence.add(item.getId(), item.getQuantity());
		}
		source.invalidateSnapshot();
		sessionSource.invalidateSnapshot();
		// Persist the all-history aggregate only; session detail remains intentionally ephemeral.
		persist(source);
		SessionLootRecord record = sessionLedger.add(event.getCombatLevel(), snapshot(occurrence));
		return new LootDataDelta(snapshot(source), snapshot(sessionSource), record,
			sessionLedger.isTruncated());
	}

	private MutableLootSource loadPersistedAggregate(LootSourceId sourceId, Instant receivedAt)
	{
		if (activeProfileKey != null)
		{
			String key = historyKey(sourceId);
			MutableLootSource persisted = parseStoredLoot(key,
				profileConfiguration.get(SearchableLootTrackerConfig.GROUP, activeProfileKey, key));
			// Validate the stored identity as well as the key. A malformed or manually edited record
			// must not be merged into an unrelated live source, even though hash collisions are remote.
			if (persisted != null && historyKey(persisted.getId()).equals(key))
			{
				configKeySources.put(key, sourceId);
				return persisted;
			}
		}
		return new MutableLootSource(sourceId.getName(), sourceId.getType(), 0, receivedAt);
	}

	List<LootSource> snapshot()
	{
		List<LootSource> snapshot = new ArrayList<>(sources.size());
		for (MutableLootSource source : sources.values())
		{
			snapshot.add(snapshot(source));
		}
		return snapshot;
	}

	List<LootSource> sessionSnapshot()
	{
		List<LootSource> snapshot = new ArrayList<>(sessionSources.size());
		for (MutableLootSource source : sessionSources.values())
		{
			snapshot.add(snapshot(source));
		}
		return snapshot;
	}

	List<SessionLootRecord> sessionRecordsSnapshot()
	{
		return sessionLedger.snapshot();
	}

	boolean areSessionRecordsTruncated()
	{
		return sessionLedger.isTruncated();
	}

	LootDataDelta observeNpc(String name, int npcId)
	{
		if (name == null || name.trim().isEmpty() || npcId < 0)
		{
			return null;
		}

		String normalizedName = normalizeName(name);
		rememberNpcId(name, npcId);
		MutableLootSource historySource = findSource(sources, name, normalizedName);
		MutableLootSource sessionSource = findSource(sessionSources, name, normalizedName);
		LootSource historySnapshot = historySource != null && historySource.addNpcId(npcId)
			? snapshot(historySource) : null;
		LootSource sessionSnapshot = sessionSource != null && sessionSource.addNpcId(npcId)
			? snapshot(sessionSource) : null;
		if (historySnapshot != null)
		{
			persist(historySource);
		}
		if (historySnapshot == null && sessionSnapshot == null)
		{
			return null;
		}
		return new LootDataDelta(historySnapshot, sessionSnapshot, null,
			sessionLedger.isTruncated());
	}

	private static MutableLootSource findSource(Map<LootSourceId, MutableLootSource> sourceMap,
		String name, String normalizedName)
	{
		MutableLootSource source = sourceMap.get(new LootSourceId("NPC", name));
		if (source != null)
		{
			return source;
		}
		for (MutableLootSource candidate : sourceMap.values())
		{
			if ("NPC".equals(candidate.type) && normalizeName(candidate.name).equals(normalizedName))
			{
				return candidate;
			}
		}
		return null;
	}

	void clearSession()
	{
		sessionSources.clear();
		sessionLedger.clear();
	}

	void prepareForProfileChange()
	{
		// Stop writes to the previous profile immediately; the replacement profile key can
		// become available a few client ticks after RuneScapeProfileChanged is delivered.
		activeProfileKey = null;
		sources.clear();
		configKeySources.clear();
		observedNpcIds.clear();
		clearSession();
	}

	void resetSource(LootSourceId sourceId)
	{
		// Match core Loot Tracker semantics by removing the aggregate and every retained
		// occurrence for this source from the current session as one operation.
		sources.remove(sourceId);
		sessionSources.remove(sourceId);
		sessionLedger.remove(sourceId);
		if (activeProfileKey == null)
		{
			return;
		}
		List<String> keysToRemove = new ArrayList<>();
		for (Map.Entry<String, LootSourceId> entry : configKeySources.entrySet())
		{
			if (entry.getValue().equals(sourceId))
			{
				keysToRemove.add(entry.getKey());
			}
		}
		for (String key : keysToRemove)
		{
			// Only Extended-owned history is removed. The core loottracker group is never mutated.
			profileConfiguration.unset(SearchableLootTrackerConfig.GROUP, activeProfileKey, key);
			configKeySources.remove(key);
		}
	}

	void resetAllHistory()
	{
		// Enumerate persisted keys rather than only loadedKeys: retention filters may currently
		// hide older records, and Reset All must remove those Extended-owned records as well.
		sources.clear();
		configKeySources.clear();
		clearSession();
		if (activeProfileKey == null)
		{
			return;
		}
		for (String key : profileConfiguration.keys(
			SearchableLootTrackerConfig.GROUP, activeProfileKey, HISTORY_KEY_PREFIX))
		{
			profileConfiguration.unset(SearchableLootTrackerConfig.GROUP, activeProfileKey, key);
		}
		// Deliberately preserve IMPORT_VERSION_KEY: reset history must not cause the
		// next enable to resurrect the original Loot Tracker records.
	}

	boolean reimportLootTrackerHistory(String expectedProfileKey, boolean extendedHistory, int historyAgeDays,
		int maximumDropEntries)
	{
		String profileKey = profileConfiguration.getActiveProfileKey();
		if (expectedProfileKey == null || !expectedProfileKey.equals(profileKey))
		{
			return false;
		}
		if (!profileKey.equals(activeProfileKey))
		{
			// The action is bound to expectedProfileKey. Reloading is safe only while ConfigManager
			// still reports that same profile, and no mutation occurs before this check.
			reloadFromActiveProfile(extendedHistory, historyAgeDays, maximumDropEntries);
			if (!expectedProfileKey.equals(profileConfiguration.getActiveProfileKey())
				|| !expectedProfileKey.equals(activeProfileKey))
			{
				return false;
			}
		}
		// Ordinary resets preserve the marker. Re-import deliberately performs reset, marker
		// removal, and the normal import path in that order so there is only one import algorithm.
		resetAllHistory();
		// This explicit recovery action is the only operation that removes the
		// Extended-owned marker. The core group remains read-only throughout.
		profileConfiguration.unset(
			SearchableLootTrackerConfig.GROUP, profileKey, IMPORT_VERSION_KEY);
		reloadFromActiveProfile(extendedHistory, historyAgeDays, maximumDropEntries);
		return true;
	}

	void clear()
	{
		sources.clear();
		configKeySources.clear();
		itemDetailsCache.clear();
		observedNpcIds.clear();
		activeProfileKey = null;
		clearSession();
	}

	private void persist(MutableLootSource source)
	{
		if (activeProfileKey == null)
		{
			return;
		}
		String key = historyKey(source.getId());
		persist(activeProfileKey, key, source);
		configKeySources.put(key, source.getId());
	}

	private void persist(String profileKey, String key, MutableLootSource source)
	{
		StoredLoot stored = new StoredLoot();
		stored.version = STORED_FORMAT_VERSION;
		stored.type = source.type;
		stored.name = source.name;
		stored.kills = source.count;
		stored.last = source.lastReceived;
		stored.npcIds = source.npcIds.stream().mapToInt(Integer::intValue).toArray();
		stored.drops = new int[source.quantities.size() * 2];
		int index = 0;
		for (Map.Entry<Integer, Integer> item : source.quantities.entrySet())
		{
			stored.drops[index++] = item.getKey();
			stored.drops[index++] = item.getValue();
		}
		profileConfiguration.set(SearchableLootTrackerConfig.GROUP,
			profileKey, key, gson.toJson(stored));
	}

	static String historyKey(LootSourceId sourceId)
	{
		// Hashing the normalized composite identity produces stable, path-safe config keys and
		// avoids exposing arbitrary source names in configuration-key syntax.
		String identity = sourceId.getType().toUpperCase(Locale.ENGLISH) + '\0'
			+ sourceId.getName().trim().toLowerCase(Locale.ENGLISH);
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(identity.getBytes(StandardCharsets.UTF_8));
			StringBuilder key = new StringBuilder(HISTORY_KEY_PREFIX);
			for (byte part : digest)
			{
				key.append(String.format("%02x", part & 0xff));
			}
			return key.toString();
		}
		catch (NoSuchAlgorithmException exception)
		{
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private LootSource snapshot(MutableLootSource source)
	{
		if (source.cachedSnapshot != null)
		{
			return source.cachedSnapshot;
		}

		List<LootItem> items = new ArrayList<>(source.quantities.size());
		for (Map.Entry<Integer, Integer> entry : source.quantities.entrySet())
		{
			int itemId = entry.getKey();
			ItemDetails details = itemDetailsCache.computeIfAbsent(itemId, this::loadItemDetails);
			items.add(new LootItem(itemId, details.name, entry.getValue(), details.gePrice, details.haPrice));
		}
		source.cachedSnapshot = new LootSource(
			source.name, source.type, source.count, source.lastReceived, items, source.npcIds);
		return source.cachedSnapshot;
	}

	private MutableLootSource parseStoredLoot(String key, String json)
	{
		// StoredLoot remains compatible with built-in imports (version zero) and Extended version-one
		// records. Version two adds exact NPC variants without changing the name-grouped identity.
		// Invalid entries are skipped independently
		// so one corrupt source does not hide the rest.
		try
		{
			StoredLoot stored = gson.fromJson(json, StoredLoot.class);
			if (stored == null || stored.name == null || stored.type == null
				|| stored.drops == null || stored.drops.length % 2 != 0)
			{
				log.debug("Skipping incomplete Loot Tracker entry {}", key);
				return null;
			}
			MutableLootSource source = new MutableLootSource(stored.name, stored.type, stored.kills,
				stored.last == null ? Instant.EPOCH : stored.last);
			if ("NPC".equals(stored.type) && stored.npcIds != null)
			{
				for (int npcId : stored.npcIds)
				{
					if (npcId >= 0)
					{
						source.npcIds.add(npcId);
					}
				}
			}
			source.persistedDropCount = stored.drops.length / 2;
			for (int index = 0; index < stored.drops.length; index += 2)
			{
				source.add(stored.drops[index], stored.drops[index + 1]);
			}
			return source;
		}
		catch (JsonParseException | IllegalStateException ex)
		{
			log.debug("Unable to read Loot Tracker entry {}", key, ex);
			return null;
		}
	}

	private void loadObservedNpcIds()
	{
		observedNpcIds.clear();
		if (profileConfiguration.getActiveProfileKey() == null)
		{
			return;
		}
		try
		{
			String serialized = profileConfiguration.getActive(
				SearchableLootTrackerConfig.GROUP, NPC_IDS_KEY);
			for (Map.Entry<String, Integer> entry : parseObservedNpcIds(gson, serialized).entrySet())
			{
				String name = entry.getKey();
				Integer npcId = entry.getValue();
				if (name != null && npcId != null && npcId >= 0)
				{
					observedNpcIds.put(normalizeName(name), npcId);
				}
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("Unable to read observed NPC IDs", ex);
		}
	}

	static Map<String, Integer> parseObservedNpcIds(Gson gson, String serialized)
	{
		// Current versions store JSON. The fallback below preserves maps written by early builds
		// through ConfigManager as Java's "{name=id}" Map.toString() representation.
		Map<String, Integer> parsed = new LinkedHashMap<>();
		if (serialized == null || serialized.trim().isEmpty())
		{
			return parsed;
		}

		try
		{
			Map<String, Integer> json = gson.fromJson(serialized, NPC_ID_MAP_TYPE);
			if (json != null)
			{
				parsed.putAll(json);
				return parsed;
			}
		}
		catch (JsonParseException | IllegalStateException ignored)
		{
			// Older builds passed the map directly to ConfigManager, which stored Map.toString().
		}

		String legacy = serialized.trim();
		if (legacy.length() < 2 || legacy.charAt(0) != '{' || legacy.charAt(legacy.length() - 1) != '}')
		{
			return parsed;
		}
		legacy = legacy.substring(1, legacy.length() - 1);
		if (legacy.isEmpty())
		{
			return parsed;
		}
		for (String entry : legacy.split(", "))
		{
			int separator = entry.lastIndexOf('=');
			if (separator <= 0 || separator == entry.length() - 1)
			{
				continue;
			}
			try
			{
				parsed.put(entry.substring(0, separator), Integer.parseInt(entry.substring(separator + 1)));
			}
			catch (NumberFormatException ignored)
			{
				// Ignore only the malformed legacy entry; other observed IDs remain usable.
			}
		}
		return parsed;
	}

	private static String normalizeName(String name)
	{
		return name.trim().toLowerCase(Locale.ENGLISH);
	}

	private Integer extractNpcId(Object metadata)
	{
		if (metadata instanceof Number)
		{
			int npcId = ((Number) metadata).intValue();
			return npcId >= 0 ? npcId : null;
		}
		if (metadata == null)
		{
			return null;
		}
		try
		{
			JsonElement tree = gson.toJsonTree(metadata);
			if (tree.isJsonObject())
			{
				JsonElement id = tree.getAsJsonObject().get("id");
				if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber())
				{
					int npcId = id.getAsInt();
					return npcId >= 0 ? npcId : null;
				}
			}
		}
		catch (RuntimeException ignored)
		{
			// Metadata is optional enrichment; malformed plugin metadata must not drop loot.
		}
		return null;
	}

	private void rememberNpcId(String name, int npcId)
	{
		String normalizedName = normalizeName(name);
		Integer previous = observedNpcIds.put(normalizedName, npcId);
		// Exact IDs now live with owned history. Keep this legacy fallback cheap: persist only its
		// first observation, while the in-memory value may follow the most recently looted variant.
		if (previous == null && profileConfiguration.getActiveProfileKey() != null)
		{
			profileConfiguration.setActive(
				SearchableLootTrackerConfig.GROUP, NPC_IDS_KEY, gson.toJson(observedNpcIds));
		}
	}

	private ItemDetails loadItemDetails(int itemId)
	{
		ItemComposition composition = itemManager.getItemComposition(itemId);
		return new ItemDetails(composition.getMembersName(), itemManager.getItemPrice(itemId), composition.getHaPrice());
	}

	private static int saturatedAdd(int left, int right)
	{
		long value = (long) left + right;
		return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
	}

	/**
	 * Narrow configuration boundary used to make ownership rules directly testable. Tests record
	 * every mutation and reject any write or deletion whose group is the core {@code loottracker}.
	 */
	interface ProfileConfiguration
	{
		String getActiveProfileKey();

		List<String> keys(String group, String profileKey, String prefix);

		String get(String group, String profileKey, String key);

		void set(String group, String profileKey, String key, String value);

		void unset(String group, String profileKey, String key);

		String getActive(String group, String key);

		void setActive(String group, String key, String value);
	}

	private static final class RuneLiteProfileConfiguration implements ProfileConfiguration
	{
		private final ConfigManager configManager;

		private RuneLiteProfileConfiguration(ConfigManager configManager)
		{
			this.configManager = configManager;
		}

		@Override
		public String getActiveProfileKey()
		{
			return configManager.getRSProfileKey();
		}

		@Override
		public List<String> keys(String group, String profileKey, String prefix)
		{
			return configManager.getRSProfileConfigurationKeys(group, profileKey, prefix);
		}

		@Override
		public String get(String group, String profileKey, String key)
		{
			return configManager.getConfiguration(group, profileKey, key);
		}

		@Override
		public void set(String group, String profileKey, String key, String value)
		{
			configManager.setConfiguration(group, profileKey, key, value);
		}

		@Override
		public void unset(String group, String profileKey, String key)
		{
			configManager.unsetConfiguration(group, profileKey, key);
		}

		@Override
		public String getActive(String group, String key)
		{
			return configManager.getRSProfileConfiguration(group, key);
		}

		@Override
		public void setActive(String group, String key, String value)
		{
			configManager.setRSProfileConfiguration(group, key, value);
		}
	}

	private static final class MutableLootSource
	{
		private final String name;
		private final String type;
		private int count;
		private Instant lastReceived;
		private int persistedDropCount;
		private final Set<Integer> npcIds = new LinkedHashSet<>();
		private final Map<Integer, Integer> quantities = new LinkedHashMap<>();
		private LootSource cachedSnapshot;

		private MutableLootSource(String name, String type, int count, Instant lastReceived)
		{
			this.name = name;
			this.type = type;
			this.count = count;
			this.lastReceived = lastReceived;
		}

		private void add(int itemId, int quantity)
		{
			quantities.merge(itemId, quantity, LootDataService::saturatedAdd);
		}

		private boolean addNpcId(Integer newNpcId)
		{
			if (newNpcId == null || newNpcId < 0 || !npcIds.add(newNpcId))
			{
				return false;
			}
			invalidateSnapshot();
			return true;
		}

		private void invalidateSnapshot()
		{
			cachedSnapshot = null;
		}

		private LootSourceId getId()
		{
			return new LootSourceId(type, name);
		}
	}

	private static final class ItemDetails
	{
		private final String name;
		private final int gePrice;
		private final int haPrice;

		private ItemDetails(String name, int gePrice, int haPrice)
		{
			this.name = name;
			this.gePrice = gePrice;
			this.haPrice = haPrice;
		}
	}

	private static final class LoadedLoot
	{
		private final String key;
		private final MutableLootSource source;

		private LoadedLoot(String key, MutableLootSource source)
		{
			this.key = key;
			this.source = source;
		}
	}

	@SuppressWarnings("unused")
	private static final class StoredLoot
	{
		// Imported core records omit version and deserialize as zero; newly-owned records use two.
		private int version;
		private String type;
		private String name;
		private int kills;
		private Instant last;
		private int[] drops;
		private int[] npcIds;
	}
}
