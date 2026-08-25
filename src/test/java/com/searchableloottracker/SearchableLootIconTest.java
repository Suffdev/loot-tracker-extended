package com.searchableloottracker;

import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SearchableLootIconTest
{
	@Test
	public void createsTransparentSidebarSizedIcon()
	{
		BufferedImage icon = SearchableLootIcon.create();

		assertEquals(20, icon.getWidth());
		assertEquals(20, icon.getHeight());
		assertEquals(0, icon.getRGB(0, 0) >>> 24);

		int visiblePixels = 0;
		for (int y = 0; y < icon.getHeight(); y++)
		{
			for (int x = 0; x < icon.getWidth(); x++)
			{
				if ((icon.getRGB(x, y) >>> 24) != 0)
				{
					visiblePixels++;
				}
			}
		}
		assertTrue(visiblePixels > 80);
	}
}
