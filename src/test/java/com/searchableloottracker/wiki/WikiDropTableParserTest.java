package com.searchableloottracker.wiki;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class WikiDropTableParserTest
{
	@Test
	public void parsesRatesAcrossWikiDropTables()
	{
		String html = "<html><body>"
			+ "<table class='wikitable'><tr><td>Ignored item</td><td>1/2</td></tr></table>"
			+ "<div class='mw-heading'><h3>Runes and ammunition</h3></div>"
			+ "<table class='wikitable item-drops'><tr><th colspan='2'>Item</th>"
			+ "<th>Quantity</th><th>Rarity</th><th>Price</th></tr>"
			+ "<tr><td class='inventory-image'></td><td class='item-col'>Law rune</td><td>20</td>"
			+ "<td><span data-drop-fraction='5/128' data-drop-oneover='1/25.6'>5/128</span></td><td>0</td></tr>"
			+ "<tr><td></td><td class='item-col'>Coins</td><td>10</td><td>1/10</td><td>0</td></tr>"
			+ "<tr><td></td><td class='item-col'>Coins</td><td>20</td><td>1/20</td><td>0</td></tr>"
			+ "<tr><td></td><td class='item-col'>Coins</td><td>30</td><td>1/30</td><td>0</td></tr></table>"
			+ "<div class='mw-heading'><h3>Rare and Gem drop table</h3></div>"
			+ "<p>There is a chance of rolling this table.</p>"
			+ "<table class='item-drops'><tr><th colspan='2'>Item</th>"
			+ "<th>Quantity</th><th>Rarity</th><th>Price</th></tr>"
			+ "<tr><td></td><td class='item-col'>Law rune</td><td>45</td>"
			+ "<td><span data-drop-fraction='1/2,730.67' data-drop-oneover='1/2,731'>"
			+ "1/2,730.67</span></td><td>0</td></tr></table>"
			+ "</body></html>";

		WikiDropTable table = WikiDropTableParser.parse(html);

		assertFalse(table.isEmpty());
		assertEquals(Arrays.asList(
			"1/25.6 (x20)",
			"1/2,730.67 (x45) (Rare/Gem Table)"), table.getTooltipLines(" law RUNE "));
		assertEquals(Collections.singletonList("Multiple rates - right-click to open Wiki"),
			table.getTooltipLines("Coins"));
		assertEquals(Collections.emptyList(), table.getTooltipLines("Ignored item"));
	}

	@Test
	public void oneRateIncludesTheWikiQuantity()
	{
		WikiDropTable table = WikiDropTableParser.parse(
			"<table class='item-drops'><tr><th>Item</th><th>Quantity</th><th>Rarity</th></tr>"
				+ "<tr><td>Dragon dagger</td><td>4–6</td><td>1/32</td></tr></table>");

		assertEquals(Collections.singletonList("1/32 (x4-6)"), table.getTooltipLines("Dragon dagger"));
	}

	@Test
	public void parsesDropTableFromResponseStream() throws Exception
	{
		String html = "<table class='item-drops'><tr><th>Item</th><th>Quantity</th><th>Rarity</th></tr>"
			+ "<tr><td>Coins</td><td>10</td><td>1/2</td></tr></table>";

		WikiDropTable table = WikiDropTableParser.parse(
			new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));

		assertEquals(Collections.singletonList("1/2 (x10)"), table.getTooltipLines("Coins"));
	}

	@Test
	public void scrollBoxesShareClueScrollRatesAndIgnoreMembersMarker()
	{
		WikiDropTable table = WikiDropTableParser.parse(
			"<table class='item-drops'><tr><th colspan='2'>Item</th><th>Quantity</th><th>Rarity</th></tr>"
				+ "<tr><td></td><td class='item-col'><a href='/w/Clue_scroll_(medium)'>"
				+ "Clue scroll (medium)</a><sub>(m)</sub></td><td>1</td><td>"
				+ "<span data-drop-fraction='1/128' data-drop-oneover='1/128'>1/128</span>; "
				+ "<span data-drop-fraction='1/106' data-drop-oneover='1/106'>1/106</span>"
				+ "</td></tr></table>");

		assertEquals(Collections.singletonList("1/128; 1/106 (x1)"),
			table.getTooltipLines("Clue scroll (medium)"));
		assertEquals(table.getTooltipLines("Clue scroll (medium)"),
			table.getTooltipLines("Scroll box (medium)"));
	}

	@Test
	public void twoNormalRatesAreReportedAsMultiple()
	{
		WikiDropTable table = WikiDropTableParser.parse(
			"<table class='item-drops'><tr><th>Item</th><th>Quantity</th><th>Rarity</th></tr>"
				+ "<tr><td>Lava rune</td><td>15</td><td>1/32</td></tr>"
				+ "<tr><td>Lava rune</td><td>30</td><td>1/32</td></tr></table>");

		assertEquals(Collections.singletonList("Multiple rates - right-click to open Wiki"),
			table.getTooltipLines("Lava rune"));
	}

	@Test
	public void displaysIndependentNamedDropTablesAndCanSelectAVariant()
	{
		WikiDropTable table = WikiDropTableParser.parse(
			"<h2>Drops</h2><h3>Weapons</h3>"
				+ dropTable("Rune sword", "1", "1/10")
				+ "<h3>Rare drop table</h3>"
				+ dropTable("Rune sword", "2", "1/100")
				+ "<h2>Wilderness Slayer Cave drops</h2><h3>Weapons</h3>"
				+ dropTable("Rune sword", "3", "1/5"));

		assertEquals(Arrays.asList(
			"1/10 (x1)",
			"1/100 (x2) (Rare Drop Table)",
			"1/5 (x3) (Wilderness Slayer Cave)"), table.getTooltipLines("Rune sword"));
		assertEquals(Arrays.asList(
			"1/100 (x2) (Rare Drop Table)",
			"1/5 (x3) (Wilderness Slayer Cave)"),
			table.selectContext("Wilderness Slayer Cave").getTooltipLines("Rune sword"));
		assertEquals(Arrays.asList(
			"1/10 (x1)",
			"1/100 (x2) (Rare Drop Table)"),
			table.selectBaseContext().getTooltipLines("Rune sword"));
	}

	@Test
	public void preservesAbyssalDemonVariantAboveCategoryHeading()
	{
		WikiDropTable table = WikiDropTableParser.parse(
			"<h2>Drops</h2>"
				+ "<h3>Standard and Catacombs of Kourend</h3>"
				+ "<h4>Weapons and armour</h4>"
				+ dropTable("Steel battleaxe", "1", "3/128")
				+ "<h4>Rare drop table</h4>"
				+ dropTable("Rune 2h sword", "1", "1/1,000")
				+ "<h3>Wilderness Slayer Cave</h3>"
				+ "<h4>Weapons and armour</h4>"
				+ dropTable("Steel battleaxe", "1", "3/68")
				+ "<h4>Rare drop table</h4>"
				+ dropTable("Rune 2h sword", "1", "1/500"));

		assertEquals(Arrays.asList(
			"3/128 (x1) (Standard and Catacombs of Kourend)",
			"3/68 (x1) (Wilderness Slayer Cave)"),
			table.getTooltipLines("Steel battleaxe"));
		assertEquals(Collections.singletonList("3/68 (x1) (Wilderness Slayer Cave)"),
			table.selectContext("Wilderness Slayer Cave").getTooltipLines("Steel battleaxe"));
		assertEquals(Collections.singletonList(
			"3/128 (x1) (Standard and Catacombs of Kourend)"),
			table.selectContext("Standard").getTooltipLines("Steel battleaxe"));
		assertEquals(Collections.singletonList(
			"3/128 (x1) (Standard and Catacombs of Kourend)"),
			table.selectContext("Catacombs of Kourend").getTooltipLines("Steel battleaxe"));
		assertEquals(Collections.singletonList(
			"3/128 (x1) (Standard and Catacombs of Kourend)"),
			table.selectBaseContext().getTooltipLines("Steel battleaxe"));
		assertEquals(Arrays.asList(
			"1/1,000 (x1) (Rare Drop Table - Standard and Catacombs of Kourend)",
			"1/500 (x1) (Rare Drop Table - Wilderness Slayer Cave)"),
			table.getTooltipLines("Rune 2h sword"));
		assertEquals(Collections.singletonList(
			"1/500 (x1) (Rare Drop Table - Wilderness Slayer Cave)"),
			table.selectContext("Wilderness Slayer Cave").getTooltipLines("Rune 2h sword"));
	}

	@Test
	public void buildsNameOnlyAndNpcIdLookupUrls()
	{
		HttpUrl nameOnly = WikiDropRateService.buildUrl("Gnome woman", null);
		assertEquals("npc", nameOnly.queryParameter("type"));
		assertEquals("Gnome woman", nameOnly.queryParameter("name"));
		assertNull(nameOnly.queryParameter("id"));

		HttpUrl withId = WikiDropRateService.buildUrl("Gnome", 66);
		assertEquals("66", withId.queryParameter("id"));
		assertEquals("Gnome", withId.queryParameter("name"));

		WikiDropRateService service = new WikiDropRateService(new OkHttpClient());
		service.setEnabled(true);
		HttpUrl clueRewards = HttpUrl.parse(service.getDropTableUrl(
			"EVENT", "Clue Scroll (Medium)", null));
		assertEquals("Reward casket (medium)", clueRewards.queryParameter("name"));
		assertEquals("Rewards", clueRewards.fragment());
		assertNull(clueRewards.queryParameter("id"));

		HttpUrl grotesqueGuardians = HttpUrl.parse(service.getDropTableUrl("NPC", "Dusk", 7888));
		assertEquals("Grotesque Guardians", grotesqueGuardians.queryParameter("name"));
		assertEquals("Drops", grotesqueGuardians.fragment());
		assertNull(grotesqueGuardians.queryParameter("id"));
		assertEquals("Grotesque Guardians",
			WikiSourceResolver.resolve("NPC", " dusk ", 7888).getPageTitle());
		assertEquals("Vorkath", WikiSourceResolver.resolve("NPC", "Vorkath", 8061).getPageTitle());

		WikiSourceResolution wilderness = WikiSourceResolver.resolve(
			"NPC", "Greater demon", 7871);
		assertEquals("Greater demon", wilderness.getPageTitle());
		assertEquals(Integer.valueOf(7871), wilderness.getNpcId());
		assertEquals("Wilderness Slayer Cave", wilderness.getTableContext());

		WikiSourceResolution waterbirth = WikiSourceResolver.resolve("NPC", "Dagannoth", 2259);
		assertEquals("Dagannoth (Waterbirth Island)", waterbirth.getPageTitle());
		assertEquals("Level 88", waterbirth.getTableContext());
		assertNull(waterbirth.getNpcId());
		assertEquals(2, WikiSourceResolver.resolveNameCandidates(
			"NPC", "Dagannoth").size());

		HttpUrl mixedWaterbirth = HttpUrl.parse(service.getDropTableUrlForVariants(
			"NPC", "Dagannoth", new LinkedHashSet<>(Arrays.asList(2259, 3185))));
		assertEquals("Dagannoth (Waterbirth Island)", mixedWaterbirth.queryParameter("name"));
		assertNull(mixedWaterbirth.fragment());
	}

	private static String dropTable(String item, String quantity, String rarity)
	{
		return "<table class='item-drops'><tr><th>Item</th><th>Quantity</th><th>Rarity</th></tr>"
			+ "<tr><td>" + item + "</td><td>" + quantity + "</td><td>" + rarity
			+ "</td></tr></table>";
	}
}
