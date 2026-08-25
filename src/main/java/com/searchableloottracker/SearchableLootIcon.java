package com.searchableloottracker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.client.util.ImageUtil;

import static java.awt.BasicStroke.CAP_ROUND;
import static java.awt.BasicStroke.JOIN_ROUND;

/** Builds the panel icon from RuneLite's own Loot Tracker asset at runtime. */
final class SearchableLootIcon
{
	private SearchableLootIcon()
	{
	}

	static BufferedImage create()
	{
		BufferedImage bag = ImageUtil.loadImageResource(LootTrackerPlugin.class, "panel_icon.png");
		BufferedImage image = new BufferedImage(bag.getWidth(), bag.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.drawImage(bag, 0, 0, null);

			// Preserve RuneLite's Loot Tracker bag and add only the search affordance.
			graphics.setColor(new Color(35, 39, 42));
			graphics.setStroke(new BasicStroke(3f, CAP_ROUND, JOIN_ROUND));
			graphics.drawOval(10, 9, 7, 7);
			graphics.drawLine(16, 15, 19, 18);
			graphics.setColor(new Color(224, 230, 234));
			graphics.setStroke(new BasicStroke(1.4f, CAP_ROUND, JOIN_ROUND));
			graphics.drawOval(10, 9, 7, 7);
			graphics.drawLine(16, 15, 19, 18);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}
}
