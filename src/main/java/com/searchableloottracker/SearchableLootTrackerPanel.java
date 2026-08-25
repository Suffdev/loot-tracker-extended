package com.searchableloottracker;

import com.searchableloottracker.model.LootItem;
import com.searchableloottracker.model.LootSource;
import com.searchableloottracker.model.LootSourceId;
import com.searchableloottracker.model.SessionLootRecord;
import com.searchableloottracker.search.LootSearch;
import com.searchableloottracker.search.LootSearchCriteria;
import com.searchableloottracker.search.LootSearchResult;
import com.searchableloottracker.search.LootSortOrder;
import com.searchableloottracker.search.SearchMode;
import com.searchableloottracker.search.SourceTypeFilter;
import com.searchableloottracker.wiki.WikiDropRateService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicToggleButtonUI;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.loottracker.LootTrackerConfig;
import net.runelite.client.plugins.loottracker.LootTrackerPriceType;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.SwingUtil;
import net.runelite.client.util.Text;

/**
 * Swing presentation layer for immutable loot snapshots.
 *
 * <p>All mutations are marshalled onto the Swing event-dispatch thread. Search results are
 * paginated, source cards are reused, and item grids are built lazily so a large saved history
 * does not create thousands of Swing components when the panel opens.</p>
 */
final class SearchableLootTrackerPanel extends PluginPanel
{
	private static final int ITEMS_PER_ROW = 5;
	private static final int LOOT_BAG_SPRITE_ID = 900;
	private static final Dimension ITEM_SLOT_SIZE = new Dimension(40, 40);
	private static final int SOURCES_PER_BATCH = 10;
	private static final int SEARCH_DEBOUNCE_MS = 120;
	private static final DateTimeFormatter SESSION_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter SESSION_TIME_TOOLTIP_FORMAT =
		DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss");
	private static final String LOOT_ITEM_PROPERTY = "lootTrackerExtended.item";
	private static final String WIKI_REQUESTED_PROPERTY = "lootTrackerExtended.wikiRequested";
	private static final Color MUTED_TEXT = ColorScheme.LIGHT_GRAY_COLOR;
	private static final BufferedImage VISIBLE_IMAGE = ImageUtil.loadImageResource(
		LootTrackerConfig.class, "visible_icon.png");
	private static final BufferedImage INVISIBLE_IMAGE = ImageUtil.loadImageResource(
		LootTrackerConfig.class, "invisible_icon.png");
	private static final BufferedImage COLLAPSE_ALL_IMAGE = ImageUtil.loadImageResource(
		LootTrackerConfig.class, "collapsed.png");
	private static final BufferedImage EXPAND_ALL_IMAGE = ImageUtil.loadImageResource(
		LootTrackerConfig.class, "expanded.png");
	private static final BufferedImage GROUPED_LOOT_IMAGE = ImageUtil.loadImageResource(
		LootTrackerConfig.class, "grouped_loot_icon.png");
	private static final BufferedImage SINGLE_LOOT_IMAGE = ImageUtil.loadImageResource(
		LootTrackerConfig.class, "single_loot_icon.png");
	private static final ImageIcon VISIBLE_ICON = new ImageIcon(VISIBLE_IMAGE);
	private static final ImageIcon VISIBLE_ICON_HOVER = new ImageIcon(ImageUtil.alphaOffset(VISIBLE_IMAGE, -220));
	private static final ImageIcon INVISIBLE_ICON = new ImageIcon(INVISIBLE_IMAGE);
	private static final ImageIcon INVISIBLE_ICON_HOVER = new ImageIcon(ImageUtil.alphaOffset(INVISIBLE_IMAGE, -220));
	private static final ImageIcon COLLAPSE_ALL_ICON = new ImageIcon(COLLAPSE_ALL_IMAGE);
	private static final ImageIcon EXPAND_ALL_ICON = new ImageIcon(EXPAND_ALL_IMAGE);
	private static final ImageIcon GROUPED_LOOT_ICON = new ImageIcon(GROUPED_LOOT_IMAGE);
	private static final ImageIcon GROUPED_LOOT_FADED_ICON = new ImageIcon(
		ImageUtil.alphaOffset(GROUPED_LOOT_IMAGE, -180));
	private static final ImageIcon GROUPED_LOOT_HOVER_ICON = new ImageIcon(
		ImageUtil.alphaOffset(GROUPED_LOOT_IMAGE, -220));
	private static final ImageIcon SINGLE_LOOT_ICON = new ImageIcon(SINGLE_LOOT_IMAGE);
	private static final ImageIcon SINGLE_LOOT_FADED_ICON = new ImageIcon(
		ImageUtil.alphaOffset(SINGLE_LOOT_IMAGE, -180));
	private static final ImageIcon SINGLE_LOOT_HOVER_ICON = new ImageIcon(
		ImageUtil.alphaOffset(SINGLE_LOOT_IMAGE, -220));

	private final ItemManager itemManager;
	private final SearchableLootTrackerConfig extendedConfig;
	private final LootTrackerConfig lootTrackerConfig;
	private final WikiDropRateService wikiDropRateService;
	private final Runnable resetSessionAction;
	private final Consumer<LootSourceId> resetSourceAction;
	private final Runnable resetAllHistoryAction;
	private final IconTextField searchField = new IconTextField();
	private final JCheckBox searchNpcs = new JCheckBox("NPC/Source", true);
	private final JCheckBox searchDrops = new JCheckBox("Item Name");
	private final JComboBox<LootSortOrder> sortOrder = new JComboBox<>(LootSortOrder.values());
	private final JComboBox<SourceTypeFilter> sourceType = new JComboBox<>(SourceTypeFilter.values());
	private final JCheckBox sessionOnly = new JCheckBox("This session only");
	private final JButton resetSession = new JButton("Reset");
	private final JLabel lootHeader = buildSectionHeader("All Loot");
	private final JRadioButton groupedLoot = new JRadioButton();
	private final JRadioButton individualLoot = new JRadioButton();
	private final JToggleButton showIgnored = new JToggleButton();
	private final JButton expansionToggle = new JButton();
	private final JLabel totalCountLabel = new JLabel();
	private final JLabel totalValueLabel = new JLabel();
	private final JLabel totalIcon = new JLabel();
	private final JPanel resultsPanel = new JPanel();
	private final Timer searchTimer;
	// These collections are EDT-owned mirrors of client-thread snapshots. Keys remain stable across
	// updates, allowing SourceCard instances and their expansion state to be reconciled in place.
	private final Map<LootSourceId, LootSource> historySources = new LinkedHashMap<>();
	private final Map<LootSourceId, LootSource> sessionSources = new LinkedHashMap<>();
	private final Deque<SessionLootRecord> sessionRecords = new ArrayDeque<>();
	private final Map<Object, SourceCard> cardCache = new LinkedHashMap<>();
	private final CardExpansionState historyExpansionState = new CardExpansionState();
	private final CardExpansionState sessionExpansionState = new CardExpansionState();
	private final CardExpansionState individualExpansionState = new CardExpansionState();
	private final Set<LootSourceId> detailedSources = new LinkedHashSet<>();
	private final Map<LootSource, SessionLootRecord> individualResults = new IdentityHashMap<>();
	private Set<String> ignoredItems = Collections.emptySet();
	private Set<String> ignoredSources = Collections.emptySet();
	private List<LootSearchResult> filteredResults = Collections.emptyList();
	private LootSearchCriteria currentCriteria;
	private int renderedSources;
	private boolean panelActive;
	private boolean rebuildPending;
	private boolean pendingBatchReset;
	private boolean pendingScrollPreservation;
	private boolean batchingCardUpdates;
	private boolean sessionRecordsTruncated;
	private long wikiTooltipGeneration;
	private Instant sessionStartedAt = Instant.now();

	SearchableLootTrackerPanel(ItemManager itemManager, SpriteManager spriteManager,
		SearchableLootTrackerConfig extendedConfig, LootTrackerConfig lootTrackerConfig,
		WikiDropRateService wikiDropRateService, Runnable resetSessionAction,
		Consumer<LootSourceId> resetSourceAction, Runnable resetAllHistoryAction)
	{
		this.itemManager = itemManager;
		this.extendedConfig = extendedConfig;
		this.lootTrackerConfig = lootTrackerConfig;
		this.wikiDropRateService = wikiDropRateService;
		this.resetSessionAction = resetSessionAction;
		this.resetSourceAction = resetSourceAction;
		this.resetAllHistoryAction = resetAllHistoryAction;
		searchTimer = new Timer(SEARCH_DEBOUNCE_MS, event -> rebuildSearch(true, false));
		searchTimer.setRepeats(false);
		sortOrder.setSelectedItem(LootSortOrder.MOST_RECENT);
		sourceType.setSelectedItem(SourceTypeFilter.ALL);

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(6, 6, 6, 6));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(content, BorderLayout.NORTH);

		content.add(buildSectionHeader("Search Loot"));
		content.add(Box.createRigidArea(new Dimension(0, 7)));
		content.add(buildSearchArea());
		content.add(Box.createRigidArea(new Dimension(0, 13)));
		content.add(buildLootHeader());
		content.add(Box.createRigidArea(new Dimension(0, 6)));
		content.add(buildOverallPanel());
		content.add(Box.createRigidArea(new Dimension(0, 7)));
		configureSessionToggle();
		configureCombo(sourceType, "Filter by Loot Tracker source type");
		configureCombo(sortOrder, "Sort matching loot sources");
		content.add(buildSessionControls());
		content.add(Box.createRigidArea(new Dimension(0, 4)));
		content.add(sourceType);
		content.add(Box.createRigidArea(new Dimension(0, 4)));
		content.add(sortOrder);
		content.add(Box.createRigidArea(new Dimension(0, 7)));

		resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
		resultsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		resultsPanel.setAlignmentX(LEFT_ALIGNMENT);
		content.add(resultsPanel);

		spriteManager.getSpriteAsync(LOOT_BAG_SPRITE_ID, 0, image -> SwingUtilities.invokeLater(() ->
		{
			totalIcon.setIcon(new ImageIcon(image));
			totalIcon.revalidate();
		}));

		refreshIgnoredLootCache();
		installListeners();
		rebuildSearch(true, false);
	}

	@Override
	public void onActivate()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::onActivate);
			return;
		}
		panelActive = true;
		// Live updates received while hidden are collapsed into one rebuild on activation.
		if (rebuildPending)
		{
			boolean resetBatch = pendingBatchReset;
			boolean preserveScroll = !resetBatch && pendingScrollPreservation;
			clearPendingRebuild();
			rebuildSearch(resetBatch, preserveScroll);
		}
		SwingUtilities.invokeLater(searchField::requestFocusInWindow);
	}

	@Override
	public void onDeactivate()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::onDeactivate);
			return;
		}
		panelActive = false;
		searchTimer.stop();
	}

	private static JLabel buildSectionHeader(String text)
	{
		JLabel header = new JLabel(text);
		header.setForeground(Color.WHITE);
		header.setFont(FontManager.getRunescapeBoldFont().deriveFont(Font.PLAIN, 16f));
		header.setBorder(new EmptyBorder(2, 0, 2, 0));
		header.setAlignmentX(LEFT_ALIGNMENT);
		return header;
	}

	private JPanel buildLootHeader()
	{
		configureViewActions();
		configureLootActions();

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		header.add(lootHeader);
		header.add(Box.createHorizontalGlue());
		header.add(groupedLoot);
		header.add(Box.createRigidArea(new Dimension(3, 0)));
		header.add(individualLoot);
		header.add(Box.createRigidArea(new Dimension(3, 0)));
		header.add(showIgnored);
		header.add(Box.createRigidArea(new Dimension(3, 0)));
		header.add(expansionToggle);
		return header;
	}

	private JPanel buildSessionControls()
	{
		resetSession.setToolTipText("Clear Loot Tracker Extended's current-session loot");
		resetSession.setFocusable(false);
		resetSession.setFont(FontManager.getRunescapeSmallFont());
		resetSession.setEnabled(false);
		resetSession.addActionListener(event ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"Clear all loot recorded for this session?", "Reset session",
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
			{
				resetSessionAction.run();
			}
		});
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(sessionOnly);
		row.add(Box.createHorizontalGlue());
		row.add(resetSession);
		return row;
	}

	private JPanel buildOverallPanel()
	{
		JPanel labels = new JPanel(new GridLayout(2, 1));
		labels.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		labels.setBorder(new EmptyBorder(2, 10, 2, 0));
		configureTotalLabel(totalCountLabel);
		configureTotalLabel(totalValueLabel);
		labels.add(totalCountLabel);
		labels.add(totalValueLabel);

		totalIcon.setVerticalAlignment(SwingConstants.CENTER);
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(5, 0, 0, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(8, 10, 8, 10)));
		panel.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(totalIcon, BorderLayout.WEST);
		panel.add(labels, BorderLayout.CENTER);

		// Mirror Loot Tracker's unobtrusive context-menu reset while making the ownership
		// boundary explicit in the confirmation shown before the callback crosses threads.
		JPopupMenu resetMenu = new JPopupMenu();
		JMenuItem resetAll = new JMenuItem("Reset All");
		resetAll.addActionListener(event ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"Permanently delete all Loot Tracker Extended history?\n"
					+ "RuneLite Loot Tracker history will not be changed.",
				"Reset all loot", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
			{
				resetAllHistoryAction.run();
			}
		});
		resetMenu.add(resetAll);
		panel.setComponentPopupMenu(resetMenu);
		labels.setComponentPopupMenu(resetMenu);
		totalCountLabel.setComponentPopupMenu(resetMenu);
		totalValueLabel.setComponentPopupMenu(resetMenu);
		totalIcon.setComponentPopupMenu(resetMenu);
		return panel;
	}

	private static void configureTotalLabel(JLabel label)
	{
		label.setForeground(MUTED_TEXT);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setBorder(new EmptyBorder(2, 0, 2, 0));
	}

	void setData(List<LootSource> newHistorySources, List<LootSource> newSessionSources,
		List<SessionLootRecord> newSessionRecords, boolean recordsTruncated)
	{
		// Full replacement is reserved for startup/profile changes. Normal loot and ConfigManager
		// activity use the incremental update paths below.
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> setData(newHistorySources, newSessionSources,
				newSessionRecords, recordsTruncated));
			return;
		}
		historySources.clear();
		sessionSources.clear();
		sessionRecords.clear();
		historyExpansionState.clear();
		sessionExpansionState.clear();
		individualExpansionState.clear();
		for (LootSource source : newHistorySources)
		{
			historySources.put(source.getId(), source);
			historyExpansionState.registerDefaultExpanded(source.getId());
		}
		for (LootSource source : newSessionSources)
		{
			sessionSources.put(source.getId(), source);
			sessionExpansionState.registerDefaultExpanded(source.getId());
		}
		for (SessionLootRecord record : newSessionRecords)
		{
			sessionRecords.addLast(record);
			individualExpansionState.registerDefaultExpanded(record.getSequence());
		}
		sessionRecordsTruncated = recordsTruncated;
		cardCache.clear();
		renderedSources = 0;
		rebuildSearch(true, false);
	}

	void upsertLoot(LootDataDelta update)
	{
		// The service has already updated exact aggregates; this method only reconciles their
		// immutable snapshots and enforces the same detail bound as SessionLootLedger.
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> upsertLoot(update));
			return;
		}
		upsertSource(historySources, update.getHistorySource(), historyExpansionState);
		upsertSource(sessionSources, update.getSessionSource(), sessionExpansionState);
		SessionLootRecord record = update.getSessionRecord();
		if (record != null)
		{
			sessionRecords.addLast(record);
			individualExpansionState.registerDefaultExpanded(record.getSequence());
			while (sessionRecords.size() > SessionLootLedger.MAX_RECORDS)
			{
				SessionLootRecord removed = sessionRecords.removeFirst();
				cardCache.remove(removed.getSequence());
				individualExpansionState.remove(removed.getSequence());
			}
		}
		sessionRecordsTruncated = update.areSessionRecordsTruncated();
		rebuildSearch(false, true);
	}

	private void upsertSource(Map<LootSourceId, LootSource> target, LootSource source,
		CardExpansionState targetExpansionState)
	{
		if (source == null)
		{
			return;
		}
		if (!target.containsKey(source.getId()))
		{
			targetExpansionState.registerDefaultExpanded(source.getId());
		}
		target.put(source.getId(), source);
	}

	void clearSession()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::clearSession);
			return;
		}
		sessionSources.clear();
		sessionRecords.clear();
		sessionExpansionState.clear();
		individualExpansionState.clear();
		sessionRecordsTruncated = false;
		detailedSources.clear();
		sessionStartedAt = Instant.now();
		individualResults.clear();
		if (sessionOnly.isSelected())
		{
			cardCache.clear();
			rebuildSearch(true, false);
		}
	}

	void removeSource(LootSourceId sourceId)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> removeSource(sourceId));
			return;
		}

		historySources.remove(sourceId);
		sessionSources.remove(sourceId);
		detailedSources.remove(sourceId);
		cardCache.remove(sourceId);
		historyExpansionState.remove(sourceId);
		sessionExpansionState.remove(sourceId);

		// A source reset also removes its retained per-kill session rows. Remove only their
		// sequence-keyed UI state so unrelated cards retain the user's expansion choices.
		Iterator<SessionLootRecord> records = sessionRecords.iterator();
		while (records.hasNext())
		{
			SessionLootRecord record = records.next();
			if (record.getLoot().getId().equals(sourceId))
			{
				records.remove();
				cardCache.remove(record.getSequence());
				individualExpansionState.remove(record.getSequence());
			}
		}
		individualResults.clear();
		rebuildSearch(true, true);
	}

	void refreshSettings()
	{
		refreshIgnoredLootCache();
		rebuildSearch(false, true);
	}

	void refreshWikiDropRates()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refreshWikiDropRates);
			return;
		}
		for (SourceCard card : cardCache.values())
		{
			card.invalidateItemGrid();
		}
		wikiTooltipGeneration++;
		reconcileResults();
	}

	private void refreshIgnoredLootCache()
	{
		ignoredItems = LootSearchCriteria.normalizeNames(Text.fromCSV(lootTrackerConfig.getIgnoredItems()));
		ignoredSources = LootSearchCriteria.normalizeNames(Text.fromCSV(lootTrackerConfig.getIgnoredEvents()));
	}

	private JPanel buildSearchArea()
	{
		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setToolTipText("Search an NPC, source, or item drop");
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setHoverBackgroundColor(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		searchField.setPreferredSize(new Dimension(PANEL_WIDTH, 30));
		searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		searchField.setAlignmentX(LEFT_ALIGNMENT);

		JLabel scopeLabel = new JLabel("Search:");
		scopeLabel.setForeground(MUTED_TEXT);
		scopeLabel.setFont(FontManager.getRunescapeSmallFont());
		scopeLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
		configureSearchScope(searchNpcs, "Search NPC and other Loot Tracker source names");
		configureSearchScope(searchDrops, "Search item names");

		JPanel scopeRow = new JPanel();
		scopeRow.setLayout(new BoxLayout(scopeRow, BoxLayout.X_AXIS));
		scopeRow.setOpaque(false);
		scopeRow.setAlignmentX(LEFT_ALIGNMENT);
		scopeRow.add(scopeLabel);
		scopeRow.add(Box.createRigidArea(new Dimension(5, 0)));
		scopeRow.add(searchNpcs);
		scopeRow.add(Box.createRigidArea(new Dimension(3, 0)));
		scopeRow.add(searchDrops);
		scopeRow.add(Box.createHorizontalGlue());

		JPanel area = new JPanel();
		area.setLayout(new BoxLayout(area, BoxLayout.Y_AXIS));
		area.setOpaque(false);
		area.setAlignmentX(LEFT_ALIGNMENT);
		area.add(searchField);
		area.add(Box.createRigidArea(new Dimension(0, 3)));
		area.add(scopeRow);
		return area;
	}

	private static void configureSearchScope(JCheckBox checkBox, String tooltip)
	{
		checkBox.setToolTipText(tooltip);
		checkBox.setOpaque(false);
		checkBox.setForeground(MUTED_TEXT);
		checkBox.setFont(FontManager.getRunescapeSmallFont());
		checkBox.setFocusable(false);
	}

	private void configureExpansionToggle()
	{
		SwingUtil.removeButtonDecorations(expansionToggle);
		expansionToggle.setIcon(EXPAND_ALL_ICON);
		expansionToggle.setSelectedIcon(COLLAPSE_ALL_ICON);
		SwingUtil.addModalTooltip(expansionToggle, "Expand all loot", "Collapse all loot");
		expansionToggle.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		expansionToggle.setUI(new BasicButtonUI());
		expansionToggle.setPreferredSize(new Dimension(30, 30));
		expansionToggle.setMaximumSize(expansionToggle.getPreferredSize());
		expansionToggle.setFocusable(false);
		expansionToggle.addActionListener(event -> setAllExpanded(areAllFilteredSourcesCollapsed()));
		updateExpansionToggle();
	}

	private void configureViewActions()
	{
		ButtonGroup viewGroup = new ButtonGroup();
		viewGroup.add(groupedLoot);
		viewGroup.add(individualLoot);
		configureViewButton(groupedLoot, GROUPED_LOOT_FADED_ICON, GROUPED_LOOT_ICON,
			GROUPED_LOOT_HOVER_ICON, "Group loot by source");
		configureViewButton(individualLoot, SINGLE_LOOT_FADED_ICON, SINGLE_LOOT_ICON,
			SINGLE_LOOT_HOVER_ICON, "Show individual loot from this session");
		groupedLoot.setSelected(true);
		individualLoot.setEnabled(false);
	}

	private static void configureViewButton(JRadioButton button, ImageIcon icon,
		ImageIcon selectedIcon, ImageIcon hoverIcon, String tooltip)
	{
		SwingUtil.removeButtonDecorations(button);
		button.setIcon(icon);
		button.setSelectedIcon(selectedIcon);
		button.setRolloverIcon(hoverIcon);
		button.setRolloverSelectedIcon(selectedIcon);
		button.setToolTipText(tooltip);
		button.setOpaque(false);
		button.setPreferredSize(new Dimension(30, 30));
		button.setMaximumSize(button.getPreferredSize());
		button.setFocusable(false);
	}

	private void configureSessionToggle()
	{
		sessionOnly.setToolTipText("Only show loot received during this RuneLite session");
		sessionOnly.setOpaque(false);
		sessionOnly.setForeground(MUTED_TEXT);
		sessionOnly.setFont(FontManager.getRunescapeSmallFont());
		sessionOnly.setFocusable(false);
		sessionOnly.setAlignmentX(LEFT_ALIGNMENT);
		sessionOnly.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			sessionOnly.getPreferredSize().height));
	}

	private void configureLootActions()
	{
		configureExpansionToggle();
		SwingUtil.removeButtonDecorations(showIgnored);
		showIgnored.setIcon(INVISIBLE_ICON);
		showIgnored.setRolloverIcon(INVISIBLE_ICON_HOVER);
		showIgnored.setSelectedIcon(VISIBLE_ICON);
		showIgnored.setRolloverSelectedIcon(VISIBLE_ICON_HOVER);
		showIgnored.setIconTextGap(0);
		showIgnored.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		showIgnored.setUI(new BasicToggleButtonUI());
		showIgnored.setPreferredSize(new Dimension(30, 30));
		showIgnored.setMaximumSize(showIgnored.getPreferredSize());
		showIgnored.setFocusable(false);
		updateHiddenLootTooltip();
	}

	private void updateHiddenLootTooltip()
	{
		showIgnored.setToolTipText(showIgnored.isSelected()
			? "Hide ignored items and sources"
			: "Show ignored items and sources");
	}

	private void configureCombo(JComboBox<?> comboBox, String tooltip)
	{
		comboBox.setToolTipText(tooltip);
		comboBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, comboBox.getPreferredSize().height));
		comboBox.setAlignmentX(LEFT_ALIGNMENT);
	}

	private void installListeners()
	{
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent event)
			{
				searchTimer.restart();
			}

			@Override
			public void removeUpdate(DocumentEvent event)
			{
				searchTimer.restart();
			}

			@Override
			public void changedUpdate(DocumentEvent event)
			{
				searchTimer.restart();
			}
		});
		searchNpcs.addActionListener(event -> searchScopeChanged(searchNpcs));
		searchDrops.addActionListener(event -> searchScopeChanged(searchDrops));
		sessionOnly.addActionListener(event ->
		{
			individualLoot.setEnabled(sessionOnly.isSelected());
			if (!sessionOnly.isSelected())
			{
				groupedLoot.setSelected(true);
			}
			viewChanged();
		});
		groupedLoot.addActionListener(event ->
		{
			if (groupedLoot.isSelected())
			{
				viewChanged();
			}
		});
		individualLoot.addActionListener(event ->
		{
			if (individualLoot.isSelected())
			{
				viewChanged();
			}
		});
		sortOrder.addActionListener(event -> rebuildSearch(true, false));
		sourceType.addActionListener(event -> rebuildSearch(true, false));
		showIgnored.addActionListener(event ->
		{
			updateHiddenLootTooltip();
			rebuildSearch(true, false);
		});

		getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "clearSearch");
		getActionMap().put("clearSearch", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent event)
			{
				clearSearch();
			}
		});
	}

	private void viewChanged()
	{
		cardCache.clear();
		detailedSources.clear();
		renderedSources = 0;
		rebuildSearch(true, false);
	}

	private void updateViewState()
	{
		if (isIndividualView())
		{
			lootHeader.setText("Individual Loot");
		}
		else if (sessionOnly.isSelected())
		{
			lootHeader.setText("Session Loot");
		}
		else
		{
			lootHeader.setText("All Loot");
		}
		resetSession.setEnabled(!sessionSources.isEmpty());
		sessionOnly.setToolTipText("Only show loot received during this RuneLite session. Session started "
			+ SESSION_TIME_TOOLTIP_FORMAT.format(sessionStartedAt.atZone(ZoneId.systemDefault())) + ".");
	}

	private void searchScopeChanged(JCheckBox changedScope)
	{
		JCheckBox otherScope = changedScope == searchNpcs ? searchDrops : searchNpcs;
		if (changedScope.isSelected())
		{
			otherScope.setSelected(false);
		}
		else
		{
			changedScope.setSelected(true);
		}
		rebuildSearch(true, false);
	}

	private void clearSearch()
	{
		if (!searchField.getText().isEmpty())
		{
			searchField.setText("");
		}
		searchField.requestFocusInWindow();
	}

	private LootSearchCriteria createCriteria()
	{
		return LootSearchCriteria.withNormalizedIgnoredLoot(searchField.getText(),
			getSearchMode(),
			(LootSortOrder) sortOrder.getSelectedItem(),
			(SourceTypeFilter) sourceType.getSelectedItem(),
			lootTrackerConfig.priceType(), showIgnored.isSelected(), ignoredItems, ignoredSources);
	}

	private SearchMode getSearchMode()
	{
		return searchDrops.isSelected() ? SearchMode.DROP : SearchMode.SOURCE;
	}

	private void rebuildSearch(boolean resetBatch, boolean preserveScroll)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> rebuildSearch(resetBatch, preserveScroll));
			return;
		}
		if (!panelActive)
		{
			// Avoid filtering and Swing reconciliation for a tab the user cannot currently see.
			rebuildPending = true;
			pendingBatchReset |= resetBatch;
			pendingScrollPreservation |= preserveScroll;
			searchTimer.stop();
			return;
		}
		clearPendingRebuild();
		searchTimer.stop();
		int scrollPosition = getScrollPane().getVerticalScrollBar().getValue();
		currentCriteria = createCriteria();
		if (isIndividualView())
		{
			individualResults.clear();
			List<LootSource> individualSources = new ArrayList<>(sessionRecords.size());
			for (SessionLootRecord record : sessionRecords)
			{
				LootSource loot = record.getLoot();
				individualSources.add(loot);
				individualResults.put(loot, record);
			}
			filteredResults = LootSearch.search(individualSources, currentCriteria);
			filteredResults.sort(individualComparator());
		}
		else
		{
			individualResults.clear();
			filteredResults = LootSearch.search(activeSources().values(), currentCriteria);
		}
		List<Object> matchingKeys = new ArrayList<>(filteredResults.size());
		for (LootSearchResult result : filteredResults)
		{
			matchingKeys.add(resultKey(result));
		}
		String searchIdentity = currentCriteria.getQuery().isEmpty() ? null
			: currentCriteria.getMode().name() + ':' + currentCriteria.getQuery();
		activeExpansionState().updateSearch(searchIdentity, matchingKeys);
		if (resetBatch)
		{
			renderedSources = 0;
		}
		if (!filteredResults.isEmpty())
		{
			renderedSources = Math.min(filteredResults.size(),
				Math.max(renderedSources, SOURCES_PER_BATCH));
		}
		else
		{
			renderedSources = 0;
		}
		reconcileResults();

		if (preserveScroll)
		{
			SwingUtilities.invokeLater(() -> getScrollPane().getVerticalScrollBar().setValue(scrollPosition));
		}
		else
		{
			SwingUtilities.invokeLater(() -> getScrollPane().getVerticalScrollBar().setValue(0));
		}
	}

	private Map<LootSourceId, LootSource> activeSources()
	{
		return sessionOnly.isSelected() ? sessionSources : historySources;
	}

	private boolean isIndividualView()
	{
		return sessionOnly.isSelected() && individualLoot.isSelected();
	}

	private CardExpansionState activeExpansionState()
	{
		if (isIndividualView())
		{
			return individualExpansionState;
		}
		return sessionOnly.isSelected() ? sessionExpansionState : historyExpansionState;
	}

	private Comparator<LootSearchResult> individualComparator()
	{
		Comparator<LootSearchResult> newestFirst = Comparator.comparingLong(this::individualSequence).reversed();
		LootSortOrder order = (LootSortOrder) sortOrder.getSelectedItem();
		if (order == LootSortOrder.LEAST_RECENT)
		{
			return Comparator.comparingLong(this::individualSequence);
		}
		if (order == LootSortOrder.ALPHABETICAL)
		{
			return Comparator.comparing(
				(LootSearchResult result) -> result.getSource().getName(), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(newestFirst);
		}
		return newestFirst;
	}

	private long individualSequence(LootSearchResult result)
	{
		SessionLootRecord record = individualResults.get(result.getSource());
		return record == null ? Long.MIN_VALUE : record.getSequence();
	}

	private void clearPendingRebuild()
	{
		rebuildPending = false;
		pendingBatchReset = false;
		pendingScrollPreservation = false;
	}

	private void reconcileResults()
	{
		// Source cards are deliberately cached and reused. removeAll only detaches their Swing
		// peers; it does not destroy them, so they can be added back in the new display order.
		resultsPanel.removeAll();
		boolean wasBatchingCardUpdates = batchingCardUpdates;
		batchingCardUpdates = true;
		try
		{
			if (isIndividualView() && sessionRecordsTruncated)
			{
				resultsPanel.add(spacedMessage("Showing the latest 1,024 individual loot records. "
					+ "Grouped session totals still include the entire session."));
			}
			if (isCurrentViewEmpty())
			{
				resultsPanel.add(spacedMessage(sessionOnly.isSelected()
					? "No loot has been received during this session."
					: "No tracked loot found. Log in and make sure Loot Tracker is enabled."));
			}
			else if (filteredResults.isEmpty())
			{
				resultsPanel.add(spacedMessage("No tracked loot matches these filters."));
			}
			else
			{
				Set<Object> renderedIds = new HashSet<>();
				for (int index = 0; index < renderedSources; index++)
				{
					LootSearchResult result = filteredResults.get(index);
					Object id = resultKey(result);
					renderedIds.add(id);
					resultsPanel.add(prepareSourceCard(result));
				}
				cardCache.keySet().retainAll(renderedIds);

				if (renderedSources < filteredResults.size())
				{
					resultsPanel.add(buildLoadMoreButton());
				}
			}
		}
		finally
		{
			batchingCardUpdates = wasBatchingCardUpdates;
		}

		updateOverallSummary();
		updateExpansionToggle();
		updateViewState();
		resultsPanel.revalidate();
		resultsPanel.repaint();
	}

	private boolean isCurrentViewEmpty()
	{
		return isIndividualView() ? sessionRecords.isEmpty() : activeSources().isEmpty();
	}

	private Object resultKey(LootSearchResult result)
	{
		if (isIndividualView())
		{
			SessionLootRecord record = individualResults.get(result.getSource());
			return record == null ? result.getSource() : record.getSequence();
		}
		return result.getSource().getId();
	}

	private SourceCard prepareSourceCard(LootSearchResult result)
	{
		Object id = resultKey(result);
		SourceCard card = cardCache.computeIfAbsent(id, SourceCard::new);
		CardExpansionState expansionState = activeExpansionState();
		expansionState.registerDefaultExpanded(id);
		card.update(result, expansionState.isExpanded(id));
		return card;
	}

	private JPanel buildLoadMoreButton()
	{
		int remaining = filteredResults.size() - renderedSources;
		JButton button = new JButton("Show more (" + remaining + ")");
		button.setFocusable(false);

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.setBorder(new EmptyBorder(7, 0, 0, 0));
		wrapper.setAlignmentX(LEFT_ALIGNMENT);
		wrapper.add(button, BorderLayout.CENTER);

		button.addActionListener(event ->
		{
			int scrollPosition = getScrollPane().getVerticalScrollBar().getValue();
			int previousRenderedSources = renderedSources;
			renderedSources = Math.min(renderedSources + SOURCES_PER_BATCH, filteredResults.size());
			resultsPanel.remove(wrapper);
			boolean wasBatchingCardUpdates = batchingCardUpdates;
			batchingCardUpdates = true;
			try
			{
				for (int index = previousRenderedSources; index < renderedSources; index++)
				{
					resultsPanel.add(prepareSourceCard(filteredResults.get(index)));
				}
			}
			finally
			{
				batchingCardUpdates = wasBatchingCardUpdates;
			}
			if (renderedSources < filteredResults.size())
			{
				resultsPanel.add(buildLoadMoreButton());
			}
			updateExpansionToggle();
			resultsPanel.revalidate();
			resultsPanel.repaint();
			SwingUtilities.invokeLater(() ->
				getScrollPane().getVerticalScrollBar().setValue(scrollPosition));
		});
		return wrapper;
	}

	private void updateOverallSummary()
	{
		long totalCount = isDropQuery()
			? LootSearch.totalQuantity(filteredResults)
			: LootSearch.totalSourceCount(filteredResults);
		long totalValue = LootSearch.totalValue(filteredResults, currentCriteria.getPriceType());

		totalCountLabel.setText(buildTotalLabel("Total count:", totalCount, ""));
		totalCountLabel.setToolTipText((isDropQuery() ? "Total matching drops: " : "Total tracked count: ")
			+ QuantityFormatter.formatNumber(totalCount));
		totalValueLabel.setText(buildTotalLabel("Total value:", totalValue, " gp"));
		String priceName = currentCriteria.getPriceType() == LootTrackerPriceType.HIGH_ALCHEMY ? "HA" : "GE";
		totalValueLabel.setToolTipText("Total " + priceName + " value: "
			+ QuantityFormatter.formatNumber(totalValue) + " gp");
	}

	private static String buildTotalLabel(String title, long value, String suffix)
	{
		return "<html>" + title + " <span style='color:white'>"
			+ QuantityFormatter.quantityToStackSize(value) + suffix + "</span></html>";
	}

	private boolean isDropQuery()
	{
		return currentCriteria != null
			&& currentCriteria.getMode() == SearchMode.DROP
			&& !currentCriteria.getQuery().isEmpty();
	}

	private void setAllExpanded(boolean expanded)
	{
		int scrollPosition = getScrollPane().getVerticalScrollBar().getValue();
		CardExpansionState expansionState = activeExpansionState();
		for (LootSearchResult result : filteredResults)
		{
			Object key = resultKey(result);
			if (expanded)
			{
				expansionState.setExpanded(key, true);
			}
			else
			{
				expansionState.setExpanded(key, false);
			}
		}
		reconcileResults();
		SwingUtilities.invokeLater(() ->
			getScrollPane().getVerticalScrollBar().setValue(scrollPosition));
	}

	private boolean areAllFilteredSourcesCollapsed()
	{
		CardExpansionState expansionState = activeExpansionState();
		return !filteredResults.isEmpty() && filteredResults.stream()
			.noneMatch(result -> expansionState.isExpanded(resultKey(result)));
	}

	private void updateExpansionToggle()
	{
		boolean allCollapsed = areAllFilteredSourcesCollapsed();
		expansionToggle.setEnabled(!filteredResults.isEmpty());
		expansionToggle.setSelected(allCollapsed);
	}

	private JPanel buildItemGrid(LootSource source, List<LootItem> items)
	{
		int rows = (items.size() + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW;
		JPanel grid = createGrid(rows);
		boolean wikiAvailable = extendedConfig.wikiDropRates()
			&& wikiDropRateService.canLookup(source.getType(), source.getName());
		JPopupMenu wikiMenu = wikiAvailable ? buildWikiMenu(source) : null;
		MouseAdapter wikiRateListener = wikiAvailable ? buildWikiRateListener(source) : null;
		for (int slot = 0; slot < rows * ITEMS_PER_ROW; slot++)
		{
			if (slot < items.size())
			{
				LootItem item = items.get(slot);
				grid.add(buildItemSlot(item, wikiMenu, wikiRateListener));
			}
			else
			{
				grid.add(buildItemSlot());
			}
		}
		return grid;
	}

	private static JPanel createGrid(int rows)
	{
		JPanel grid = new JPanel(new GridLayout(rows, ITEMS_PER_ROW, 1, 1));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return grid;
	}

	private JPopupMenu buildWikiMenu(LootSource source)
	{
		JPopupMenu wikiMenu = new JPopupMenu();
		JMenuItem openWiki = new JMenuItem("Open " + source.getName() + " drops on Wiki");
		openWiki.addActionListener(event -> LinkBrowser.browse(
			wikiDropRateService.getDropTableUrl(source.getType(), source.getName(), source.getNpcId())));
		wikiMenu.add(openWiki);
		return wikiMenu;
	}

	private MouseAdapter buildWikiRateListener(LootSource source)
	{
		// Each visible item label initiates at most one lookup. WikiDropRateService deduplicates
		// the request again at source-table level, so five items from one NPC still share one fetch.
		return new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent event)
			{
				JLabel imageLabel = (JLabel) event.getComponent();
				if (!extendedConfig.wikiDropRates())
				{
					return;
				}
				if (Boolean.TRUE.equals(imageLabel.getClientProperty(WIKI_REQUESTED_PROPERTY)))
				{
					return;
				}

				imageLabel.putClientProperty(WIKI_REQUESTED_PROPERTY, Boolean.TRUE);
				LootItem item = (LootItem) imageLabel.getClientProperty(LOOT_ITEM_PROPERTY);
				// Cache refresh/disable increments this generation. Late callbacks from an older UI
				// state are ignored rather than restoring stale tooltip content.
				long requestGeneration = wikiTooltipGeneration;
				imageLabel.setToolTipText(buildTooltip(item, Collections.singletonList("Loading...")));
				wikiDropRateService.lookup(source.getType(), source.getName(), source.getNpcId())
					.whenComplete((table, error) -> SwingUtilities.invokeLater(() ->
					{
						if (requestGeneration != wikiTooltipGeneration)
						{
							return;
						}
						List<String> rates = error == null
							? table.getTooltipLines(item.getName()) : Collections.emptyList();
						if (error != null)
						{
							rates = Collections.singletonList("Unavailable");
						}
						else if (rates.isEmpty())
						{
							rates = Collections.singletonList("Not listed");
						}
						imageLabel.setToolTipText(buildTooltip(item, rates));
					}));
			}
		};
	}

	private JPanel buildItemSlot(LootItem item, JPopupMenu wikiMenu, MouseAdapter wikiRateListener)
	{
		JPanel slot = buildItemSlot();
		JLabel imageLabel = new JLabel();
		imageLabel.putClientProperty(LOOT_ITEM_PROPERTY, item);
		imageLabel.setToolTipText(buildTooltip(item, Collections.emptyList()));
		imageLabel.setVerticalAlignment(SwingConstants.CENTER);
		imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
		AsyncBufferedImage image = itemManager.getImage(item.getId(), item.getQuantity(), item.getQuantity() > 1);
		image.addTo(imageLabel);
		if (wikiMenu != null)
		{
			slot.setComponentPopupMenu(wikiMenu);
			imageLabel.setComponentPopupMenu(wikiMenu);
		}
		if (wikiRateListener != null)
		{
			imageLabel.addMouseListener(wikiRateListener);
		}
		slot.add(imageLabel, BorderLayout.CENTER);
		return slot;
	}

	private static JPanel buildItemSlot()
	{
		JPanel slot = new JPanel(new BorderLayout());
		slot.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		slot.setPreferredSize(ITEM_SLOT_SIZE);
		slot.setMinimumSize(ITEM_SLOT_SIZE);
		return slot;
	}

	private String formatPrice(long value)
	{
		String prefix = "";
		if (lootTrackerConfig.showPriceType())
		{
			prefix = currentCriteria.getPriceType() == LootTrackerPriceType.HIGH_ALCHEMY ? "HA: " : "GE: ";
		}
		return prefix + QuantityFormatter.quantityToStackSize(value) + " gp";
	}

	private static String buildTooltip(LootItem item, List<String> wikiRates)
	{
		return buildTooltip(item.getName(), item.getQuantity(), item.getGePrice(), item.getHaPrice(), wikiRates);
	}

	private static String buildTooltip(String name, long quantity, int gePrice, int haPrice,
		List<String> wikiRates)
	{
		long totalGe = quantity > 0 && gePrice > 0 && quantity > Long.MAX_VALUE / gePrice
			? Long.MAX_VALUE : (long) gePrice * quantity;
		long totalHa = quantity > 0 && haPrice > 0 && quantity > Long.MAX_VALUE / haPrice
			? Long.MAX_VALUE : (long) haPrice * quantity;
		StringBuilder tooltip = new StringBuilder("<html>")
			.append(escapeHtml(name))
			.append(" x ")
			.append(QuantityFormatter.formatNumber(quantity))
			.append("<br>GE: ")
			.append(QuantityFormatter.quantityToStackSize(totalGe));
		if (quantity > 1)
		{
			tooltip.append(" (").append(QuantityFormatter.quantityToStackSize(gePrice)).append(" ea)");
		}
		tooltip.append("<br>HA: ")
			.append(QuantityFormatter.quantityToStackSize(totalHa));
		if (quantity > 1)
		{
			tooltip.append(" (").append(QuantityFormatter.quantityToStackSize(haPrice)).append(" ea)");
		}
		for (String wikiRate : wikiRates)
		{
			tooltip.append("<br>Wiki drop rate: ").append(escapeHtml(wikiRate));
		}
		return tooltip.append("</html>").toString();
	}

	private static String escapeHtml(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static JPanel spacedMessage(String text)
	{
		JPanel spacing = new JPanel(new BorderLayout());
		spacing.setOpaque(false);
		spacing.setBorder(new EmptyBorder(8, 0, 0, 0));
		spacing.setAlignmentX(LEFT_ALIGNMENT);
		JPanel message = new JPanel(new BorderLayout());
		message.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		message.setBorder(new EmptyBorder(12, 10, 12, 10));
		JLabel label = new JLabel("<html><body style='width:190px'>" + text + "</body></html>");
		label.setForeground(MUTED_TEXT);
		message.add(label, BorderLayout.CENTER);
		spacing.add(message, BorderLayout.CENTER);
		return spacing;
	}

	private List<InlineLootResult> findIndividualLoot(LootSourceId sourceId)
	{
		// Individual records reuse the same search criteria as grouped cards. Identity mapping is
		// intentional: multiple kill snapshots can have equal source IDs but distinct sequences.
		List<LootSource> lootSources = new ArrayList<>();
		Map<LootSource, SessionLootRecord> recordsBySource = new IdentityHashMap<>();
		for (SessionLootRecord record : sessionRecords)
		{
			LootSource loot = record.getLoot();
			if (sourceId.equals(loot.getId()))
			{
				lootSources.add(loot);
				recordsBySource.put(loot, record);
			}
		}
		List<LootSearchResult> matches = LootSearch.search(lootSources, currentCriteria);
		List<InlineLootResult> individualLoot = new ArrayList<>(matches.size());
		for (LootSearchResult match : matches)
		{
			SessionLootRecord record = recordsBySource.get(match.getSource());
			if (record != null)
			{
				individualLoot.add(new InlineLootResult(record, match));
			}
		}
		Comparator<InlineLootResult> recent = Comparator.comparingLong(
			entry -> entry.record.getSequence());
		individualLoot.sort(sortOrder.getSelectedItem() == LootSortOrder.LEAST_RECENT
			? recent : recent.reversed());
		return individualLoot;
	}

	private JPanel buildInlineLootRecord(InlineLootResult entry)
	{
		LootSource loot = entry.result.getSource();
		JPanel panel = new JPanel(new BorderLayout(0, 1));
		panel.setOpaque(false);
		panel.setBorder(new EmptyBorder(3, 8, 0, 0));

		JPanel heading = new JPanel();
		heading.setLayout(new BoxLayout(heading, BoxLayout.X_AXIS));
		heading.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		heading.setBorder(new EmptyBorder(5, 7, 5, 7));
		JLabel time = inlineLabel(SESSION_TIME_FORMAT.format(
			loot.getLastReceived().atZone(ZoneId.systemDefault())), Color.WHITE);
		time.setToolTipText(SESSION_TIME_TOOLTIP_FORMAT.format(
			loot.getLastReceived().atZone(ZoneId.systemDefault())));
		heading.add(time);
		if (entry.record.getCombatLevel() > 0)
		{
			heading.add(Box.createRigidArea(new Dimension(5, 0)));
			heading.add(inlineLabel("(level-" + entry.record.getCombatLevel() + ")", MUTED_TEXT));
		}
		if (loot.getCount() > 1)
		{
			heading.add(Box.createRigidArea(new Dimension(5, 0)));
			heading.add(inlineLabel("x " + QuantityFormatter.formatNumber(loot.getCount()), MUTED_TEXT));
		}
		heading.add(Box.createHorizontalGlue());
		heading.add(inlineLabel(formatPrice(entry.result.getTotalValue(currentCriteria.getPriceType())), MUTED_TEXT));

		panel.add(heading, BorderLayout.NORTH);
		panel.add(buildItemGrid(loot, entry.result.getVisibleItems()), BorderLayout.CENTER);
		return panel;
	}

	private static JLabel inlineLabel(String text, Color color)
	{
		JLabel label = new JLabel(text);
		label.setForeground(color);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setBorder(new EmptyBorder(2, 0, 2, 0));
		return label;
	}

	private static final class InlineLootResult
	{
		private final SessionLootRecord record;
		private final LootSearchResult result;

		private InlineLootResult(SessionLootRecord record, LootSearchResult result)
		{
			this.record = record;
			this.result = result;
		}
	}

	/**
	 * Reusable source row with a lazily-created aggregate grid and an independently paginated
	 * inline session-detail area. Per-source detail expansion never changes the global view mode.
	 */
	private final class SourceCard extends JPanel
	{
		private final Object id;
		private final JLabel titleLabel = new JLabel();
		private final JLabel countLabel = new JLabel();
		private final JLabel timestampLabel = new JLabel();
		private final JLabel valueLabel = new JLabel();
		private final JButton detailsButton = new JButton();
		private final JPanel itemHolder = new JPanel(new BorderLayout());
		private final JPanel individualHolder = new JPanel();
		private final JPanel contentHolder = new JPanel();
		private LootSearchResult result;
		private LootTrackerPriceType renderedPriceType;
		private boolean gridBuilt;
		private boolean expanded;
		private int renderedIndividualRecords = SOURCES_PER_BATCH;

		private SourceCard(Object id)
		{
			this.id = id;
			setLayout(new BorderLayout(0, 1));
			setBackground(ColorScheme.DARK_GRAY_COLOR);
			setBorder(new EmptyBorder(5, 0, 0, 0));
			setAlignmentX(LEFT_ALIGNMENT);

			JPanel heading = new JPanel();
			heading.setLayout(new BoxLayout(heading, BoxLayout.X_AXIS));
			heading.setBorder(new EmptyBorder(7, 7, 7, 7));
			heading.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
			heading.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			configureHeaderLabel(titleLabel, Color.WHITE);
			configureHeaderLabel(countLabel, MUTED_TEXT);
			configureHeaderLabel(timestampLabel, MUTED_TEXT);
			configureHeaderLabel(valueLabel, MUTED_TEXT);
			configureDetailsButton();
			heading.add(titleLabel);
			heading.add(Box.createRigidArea(new Dimension(5, 0)));
			heading.add(countLabel);
			heading.add(detailsButton);
			heading.add(Box.createHorizontalGlue());
			heading.add(timestampLabel);
			heading.add(Box.createRigidArea(new Dimension(5, 0)));
			heading.add(valueLabel);

			MouseAdapter listener = new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent event)
				{
					if (event.getButton() == MouseEvent.BUTTON1)
					{
						setExpanded(!activeExpansionState().isExpanded(id));
					}
				}
			};
			heading.addMouseListener(listener);
			for (java.awt.Component component : heading.getComponents())
			{
				if (component != detailsButton)
				{
					component.addMouseListener(listener);
				}
			}

			// Install the same menu on each heading child because Swing does not automatically
			// inherit a parent's component popup menu for labels and buttons.
			JPopupMenu resetMenu = new JPopupMenu();
			JMenuItem reset = new JMenuItem("Reset");
			reset.addActionListener(event -> resetSource());
			resetMenu.add(reset);
			setComponentPopupMenu(resetMenu);
			heading.setComponentPopupMenu(resetMenu);
			for (java.awt.Component component : heading.getComponents())
			{
				if (component instanceof javax.swing.JComponent)
				{
					((javax.swing.JComponent) component).setComponentPopupMenu(resetMenu);
				}
			}

			itemHolder.setOpaque(false);
			itemHolder.setVisible(false);
			individualHolder.setLayout(new BoxLayout(individualHolder, BoxLayout.Y_AXIS));
			individualHolder.setOpaque(false);
			individualHolder.setVisible(false);
			contentHolder.setLayout(new BoxLayout(contentHolder, BoxLayout.Y_AXIS));
			contentHolder.setOpaque(false);
			contentHolder.add(itemHolder);
			contentHolder.add(individualHolder);
			add(heading, BorderLayout.NORTH);
			add(contentHolder, BorderLayout.CENTER);
		}

		private void resetSource()
		{
			if (result == null)
			{
				return;
			}
			// Capture the immutable result before dispatch; reconciliation may replace this card's
			// result while the client-thread deletion is queued.
			LootSource source = result.getSource();
			int choice = JOptionPane.showConfirmDialog(SearchableLootTrackerPanel.this,
				"Permanently delete \"" + source.getName() + "\" from Loot Tracker Extended?\n"
					+ "RuneLite Loot Tracker history will not be changed.",
				"Reset loot", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
			{
				resetSourceAction.accept(source.getId());
			}
		}

		private void configureDetailsButton()
		{
			SwingUtil.removeButtonDecorations(detailsButton);
			detailsButton.setIcon(SINGLE_LOOT_FADED_ICON);
			detailsButton.setRolloverIcon(SINGLE_LOOT_HOVER_ICON);
			detailsButton.setToolTipText("View this source's individual session loot");
			detailsButton.setOpaque(false);
			detailsButton.setPreferredSize(new Dimension(20, 20));
			detailsButton.setMaximumSize(detailsButton.getPreferredSize());
			detailsButton.setFocusable(false);
			detailsButton.setVisible(false);
			detailsButton.addActionListener(event ->
			{
				if (result != null)
				{
					LootSourceId sourceId = result.getSource().getId();
					if (!detailedSources.remove(sourceId))
					{
						detailedSources.add(sourceId);
						renderedIndividualRecords = SOURCES_PER_BATCH;
					}
					detailsButton.setIcon(detailedSources.contains(sourceId)
						? SINGLE_LOOT_ICON : SINGLE_LOOT_FADED_ICON);
					rebuildIndividualLoot();
					revalidate();
					repaint();
				}
			});
		}

		private boolean hasResult()
		{
			return result != null;
		}

		private void update(LootSearchResult newResult, boolean expanded)
		{
			boolean itemsChanged = result == null
				|| result.getSource() != newResult.getSource()
				|| !result.getVisibleItems().equals(newResult.getVisibleItems())
				|| renderedPriceType != currentCriteria.getPriceType();
			result = newResult;
			renderedPriceType = currentCriteria.getPriceType();
			LootSource source = result.getSource();
			titleLabel.setText(source.getName());
			SessionLootRecord sessionRecord = individualResults.get(source);
			String titleTooltip = source.getType().toLowerCase();
			if (sessionRecord != null && sessionRecord.getCombatLevel() > 0)
			{
				titleTooltip += " | Combat level " + sessionRecord.getCombatLevel();
			}
			titleLabel.setToolTipText(titleTooltip);
			detailsButton.setVisible(sessionOnly.isSelected() && !isIndividualView());
			detailsButton.setIcon(detailedSources.contains(source.getId())
				? SINGLE_LOOT_ICON : SINGLE_LOOT_FADED_ICON);
			timestampLabel.setVisible(sessionRecord != null);
			if (sessionRecord != null)
			{
				timestampLabel.setText(SESSION_TIME_FORMAT.format(
					source.getLastReceived().atZone(ZoneId.systemDefault())));
				timestampLabel.setToolTipText(SESSION_TIME_TOOLTIP_FORMAT.format(
					source.getLastReceived().atZone(ZoneId.systemDefault())));
			}

			boolean dropQuery = isDropQuery();
			long displayedCount = dropQuery ? result.getTotalQuantity() : source.getCount();
			String combatLevel = sessionRecord != null && sessionRecord.getCombatLevel() > 0
				? "(level-" + sessionRecord.getCombatLevel() + ")" : "";
			String count = displayedCount > 1 || dropQuery
				? "x " + QuantityFormatter.formatNumber(displayedCount) : "";
			countLabel.setText(combatLevel + (!combatLevel.isEmpty() && !count.isEmpty() ? " " : "") + count);
			countLabel.setToolTipText(dropQuery
				? "Matching drops from this source: " + QuantityFormatter.formatNumber(displayedCount)
				: sessionRecord != null
					? "Kills or rolls represented: " + QuantityFormatter.formatNumber(displayedCount)
					: "Tracked count: " + QuantityFormatter.formatNumber(displayedCount));

			long visibleValue = result.getTotalValue(currentCriteria.getPriceType());
			valueLabel.setText(formatPrice(visibleValue));
			valueLabel.setToolTipText(sessionRecord != null
				? "Individual loot value: " + QuantityFormatter.formatNumber(visibleValue) + " gp"
				: "Visible: " + QuantityFormatter.formatNumber(visibleValue)
					+ " gp | All source loot: " + QuantityFormatter.formatNumber(
						source.getTotalValue(currentCriteria.getPriceType())) + " gp");

			if (itemsChanged)
			{
				itemHolder.removeAll();
				gridBuilt = false;
			}
			setExpanded(expanded);
			rebuildIndividualLoot();
		}

		private void rebuildIndividualLoot()
		{
			individualHolder.removeAll();
			if (result == null || !sessionOnly.isSelected() || isIndividualView()
				|| !detailedSources.contains(result.getSource().getId()))
			{
				individualHolder.setVisible(false);
				return;
			}

			List<InlineLootResult> records = findIndividualLoot(result.getSource().getId());
			int displayed = Math.min(renderedIndividualRecords, records.size());
			if (records.isEmpty())
			{
				individualHolder.add(spacedMessage("No retained individual loot matches these filters."));
			}
			for (int index = 0; index < displayed; index++)
			{
				individualHolder.add(buildInlineLootRecord(records.get(index)));
			}
			if (displayed < records.size())
			{
				JButton showMore = new JButton("Show more kills (" + (records.size() - displayed) + ")");
				showMore.setFocusable(false);
				showMore.addActionListener(event ->
				{
					renderedIndividualRecords += SOURCES_PER_BATCH;
					rebuildIndividualLoot();
					individualHolder.revalidate();
					individualHolder.repaint();
				});
				individualHolder.add(showMore);
			}
			individualHolder.setVisible(true);
		}

		private void setExpanded(boolean expanded)
		{
			boolean expansionChanged = this.expanded != expanded;
			boolean gridChanged = false;
			CardExpansionState expansionState = activeExpansionState();
			if (expanded)
			{
				expansionState.setExpanded(id, true);
				if (!gridBuilt && result != null)
				{
					itemHolder.add(buildItemGrid(result.getSource(), result.getVisibleItems()), BorderLayout.CENTER);
					gridBuilt = true;
					gridChanged = true;
				}
			}
			else
			{
				expansionState.setExpanded(id, false);
			}
			this.expanded = expanded;
			if (expansionChanged)
			{
				itemHolder.setVisible(expanded);
			}
			if ((expansionChanged || gridChanged) && !batchingCardUpdates)
			{
				updateExpansionToggle();
				itemHolder.revalidate();
				itemHolder.repaint();
				revalidate();
				repaint();
			}
		}

		private void invalidateItemGrid()
		{
			itemHolder.removeAll();
			gridBuilt = false;
			rebuildIndividualLoot();
		}

		private void configureHeaderLabel(JLabel label, Color color)
		{
			label.setForeground(color);
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setBorder(new EmptyBorder(3, 0, 2, 0));
			label.setVerticalAlignment(SwingConstants.CENTER);
			label.setMinimumSize(new Dimension(1, label.getPreferredSize().height));
		}
	}
}
