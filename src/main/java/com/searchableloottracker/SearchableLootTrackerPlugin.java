package com.searchableloottracker;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.searchableloottracker.model.LootSource;
import com.searchableloottracker.model.LootSourceId;
import com.searchableloottracker.model.SessionLootRecord;
import com.searchableloottracker.wiki.WikiDropRateService;
import java.util.List;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.loottracker.LootTrackerConfig;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

/**
 * Coordinates profile-backed Loot Tracker history, live loot events, and the Swing side panel.
 * Mutable loot state is confined to the RuneLite client thread; immutable snapshots are handed
 * to the Swing event-dispatch thread for rendering.
 */
@PluginDescriptor(
	name = "Loot Tracker Extended",
	description = "Search, filter, retain, and explore RuneLite loot with independent history",
	tags = {"loot", "drops", "search", "npc", "tracker", "history"}
)
public class SearchableLootTrackerPlugin extends Plugin
{
	@Provides
	SearchableLootTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SearchableLootTrackerConfig.class);
	}

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private LootDataService lootDataService;

	@Inject
	private SearchableLootTrackerConfig config;
	private LootTrackerConfig lootTrackerConfig;

	@Inject
	private WikiDropRateService wikiDropRateService;

	private SearchableLootTrackerPanel panel;
	private NavigationButton navigationButton;
	private volatile boolean active;
	// Incremented for every profile transition so queued destructive actions cannot survive an
	// A -> B -> A switch merely because the same profile key is active again at execution time.
	private volatile long profileGeneration;

	@Override
	protected void startUp()
	{
		migrateLegacyConfig();
		lootTrackerConfig = configManager.getConfig(LootTrackerConfig.class);
		active = true;
		wikiDropRateService.setEnabled(config.wikiDropRates());
		panel = new SearchableLootTrackerPanel(
			itemManager, spriteManager, config, lootTrackerConfig, wikiDropRateService,
			this::resetSession, this::resetSource, this::resetAllHistory);
		navigationButton = NavigationButton.builder()
			.tooltip("Loot Tracker Extended")
			.icon(SearchableLootIcon.create())
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		reloadWhenReady();
	}

	@Override
	protected void shutDown()
	{
		active = false;
		profileGeneration++;
		wikiDropRateService.setEnabled(false);
		clientToolbar.removeNavigation(navigationButton);
		lootDataService.clear();
		panel = null;
		navigationButton = null;
		lootTrackerConfig = null;
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		showUpsert(lootDataService.add(event));
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		NPC npc = event.getNpc();
		LootDataDelta update = lootDataService.observeNpc(npc.getName(), npc.getId());
		if (update != null)
		{
			showUpsert(update);
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		profileGeneration++;
		lootDataService.prepareForProfileChange();
		SearchableLootTrackerPanel currentPanel = panel;
		if (currentPanel != null)
		{
			showSnapshot();
		}
		reloadWhenReady();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getKey() == null)
		{
			return;
		}

		if (SearchableLootTrackerConfig.GROUP.equals(event.getGroup()))
		{
			handleExtendedConfigChanged(event);
			return;
		}
		if (!LootDataService.LOOT_TRACKER_GROUP.equals(event.getGroup()))
		{
			return;
		}

		if (event.getKey().equals("ignoredItems")
			|| event.getKey().equals("ignoredEvents")
			|| event.getKey().equals("priceType")
			|| event.getKey().equals("showPriceType"))
		{
			SearchableLootTrackerPanel currentPanel = panel;
			if (currentPanel != null)
			{
				SwingUtilities.invokeLater(currentPanel::refreshSettings);
			}
		}
	}

	private void handleExtendedConfigChanged(ConfigChanged event)
	{
		if (event.getKey().equals(SearchableLootTrackerConfig.EXTENDED_HISTORY_KEY)
			|| event.getKey().equals(SearchableLootTrackerConfig.HISTORY_AGE_DAYS_KEY)
			|| event.getKey().equals(SearchableLootTrackerConfig.MAXIMUM_DROP_ENTRIES_KEY))
		{
			reloadWhenReady();
		}
		else if (event.getKey().equals(SearchableLootTrackerConfig.REIMPORT_HISTORY_KEY)
			&& Boolean.parseBoolean(event.getNewValue()))
		{
			// Treat the boolean as a momentary action. Reset it before scheduling work so the
			// setting remains ready for a future manual re-import even if the profile is not ready.
			configManager.setConfiguration(SearchableLootTrackerConfig.GROUP,
				SearchableLootTrackerConfig.REIMPORT_HISTORY_KEY, false);
			reimportActiveProfile();
		}
		else if (event.getKey().equals(SearchableLootTrackerConfig.WIKI_DROP_RATES_KEY))
		{
			wikiDropRateService.setEnabled(config.wikiDropRates());
			SearchableLootTrackerPanel currentPanel = panel;
			if (currentPanel != null)
			{
				SwingUtilities.invokeLater(currentPanel::refreshWikiDropRates);
			}
		}
		else if (event.getKey().equals(SearchableLootTrackerConfig.REFRESH_WIKI_DROP_RATES_KEY)
			&& Boolean.parseBoolean(event.getNewValue()))
		{
			wikiDropRateService.clearCache();
			configManager.setConfiguration(SearchableLootTrackerConfig.GROUP,
				SearchableLootTrackerConfig.REFRESH_WIKI_DROP_RATES_KEY, false);
			SearchableLootTrackerPanel currentPanel = panel;
			if (currentPanel != null)
			{
				SwingUtilities.invokeLater(currentPanel::refreshWikiDropRates);
			}
		}
	}

	private void migrateLegacyConfig()
	{
		// Early builds stored Extended's settings in the core Loot Tracker group. Copy only
		// missing values so an explicit setting in the owned group always wins.
		migrateLegacyConfigItem(SearchableLootTrackerConfig.EXTENDED_HISTORY_KEY);
		migrateLegacyConfigItem(SearchableLootTrackerConfig.HISTORY_AGE_DAYS_KEY);
		migrateLegacyConfigItem(SearchableLootTrackerConfig.MAXIMUM_DROP_ENTRIES_KEY);
		migrateLegacyConfigItem(SearchableLootTrackerConfig.WIKI_DROP_RATES_KEY);
	}

	private void migrateLegacyConfigItem(String key)
	{
		if (configManager.getConfiguration(SearchableLootTrackerConfig.GROUP, key) != null)
		{
			return;
		}
		String legacyValue = configManager.getConfiguration(LootDataService.LOOT_TRACKER_GROUP, key);
		if (legacyValue != null)
		{
			configManager.setConfiguration(SearchableLootTrackerConfig.GROUP, key, legacyValue);
		}
	}

	private void reloadWhenReady()
	{
		if (!active)
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			if (!active)
			{
				return true;
			}
			if (client.getGameState().getState() < GameState.LOGIN_SCREEN.getState())
			{
				return false;
			}
			// A profile-change event can arrive before ConfigManager publishes its profile key.
			// Returning false asks ClientThread to retry on a later game tick.
			if (configManager.getRSProfileKey() == null)
			{
				return false;
			}
			lootDataService.reloadFromActiveProfile(config.extendedLootHistory(),
				config.historyAgeDays(), config.maximumDropEntries());
			showSnapshot();
			return true;
		});
	}

	private void reimportActiveProfile()
	{
		// Unlike ordinary reloads, a replacement import is destructive to Extended's copy. Capture
		// its target now and never defer it until an arbitrary future profile becomes active.
		String expectedProfileKey = configManager.getRSProfileKey();
		if (expectedProfileKey == null)
		{
			return;
		}
		long expectedGeneration = profileGeneration;
		clientThread.invokeLater(() ->
		{
			if (!active || expectedGeneration != profileGeneration)
			{
				return;
			}
			if (lootDataService.reimportLootTrackerHistory(expectedProfileKey,
				config.extendedLootHistory(), config.historyAgeDays(), config.maximumDropEntries()))
			{
				showSnapshot();
			}
		});
	}

	private void showSnapshot()
	{
		SearchableLootTrackerPanel currentPanel = panel;
		if (currentPanel != null)
		{
			List<LootSource> historySnapshot = lootDataService.snapshot();
			List<LootSource> sessionSnapshot = lootDataService.sessionSnapshot();
			List<SessionLootRecord> sessionRecordsSnapshot = lootDataService.sessionRecordsSnapshot();
			boolean sessionRecordsTruncated = lootDataService.areSessionRecordsTruncated();
			SwingUtilities.invokeLater(() -> currentPanel.setData(
				historySnapshot, sessionSnapshot, sessionRecordsSnapshot, sessionRecordsTruncated));
		}
	}

	private void showUpsert(LootDataDelta update)
	{
		SearchableLootTrackerPanel currentPanel = panel;
		if (currentPanel != null)
		{
			SwingUtilities.invokeLater(() -> currentPanel.upsertLoot(update));
		}
	}

	private void resetSession()
	{
		clientThread.invokeLater(() ->
		{
			if (!active)
			{
				return;
			}
			lootDataService.clearSession();
			SearchableLootTrackerPanel currentPanel = panel;
			if (currentPanel != null)
			{
				SwingUtilities.invokeLater(currentPanel::clearSession);
			}
		});
	}

	private void resetSource(LootSourceId sourceId)
	{
		// Swing supplies only an immutable identity; persistence and model mutation return to
		// the client thread. Bind the destructive action to the profile that owned the visible
		// card so a queued callback cannot delete a matching source from a later profile.
		String expectedProfileKey = configManager.getRSProfileKey();
		long expectedGeneration = profileGeneration;
		if (expectedProfileKey == null)
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			if (!isExpectedProfile(active, expectedGeneration, profileGeneration,
				expectedProfileKey, configManager.getRSProfileKey()))
			{
				return;
			}
			lootDataService.resetSource(sourceId);
			// Reconcile this deletion incrementally so expansion choices for every surviving
			// source are retained.
			SearchableLootTrackerPanel currentPanel = panel;
			if (currentPanel != null)
			{
				SwingUtilities.invokeLater(() -> currentPanel.removeSource(sourceId));
			}
		});
	}

	private void resetAllHistory()
	{
		// As with per-source reset, the panel never touches ConfigManager directly and the
		// deletion remains bound to the profile for which the confirmation was displayed.
		String expectedProfileKey = configManager.getRSProfileKey();
		long expectedGeneration = profileGeneration;
		if (expectedProfileKey == null)
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			if (!isExpectedProfile(active, expectedGeneration, profileGeneration,
				expectedProfileKey, configManager.getRSProfileKey()))
			{
				return;
			}
			lootDataService.resetAllHistory();
			showSnapshot();
		});
	}

	static boolean isExpectedProfile(boolean active, long expectedGeneration, long currentGeneration,
		String expectedProfileKey, String currentProfileKey)
	{
		return active
			&& expectedGeneration == currentGeneration
			&& expectedProfileKey != null
			&& expectedProfileKey.equals(currentProfileKey);
	}
}
