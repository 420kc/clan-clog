package com.clanclog;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Clan Clog",
	description = "Search a clan, see every member ranked",
	tags = {"clan", "hiscores", "420kc"}
)
public class ClanClogPlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ClanClogPanel panel;

	@Inject
	private ClanHiscoreBatch batch;

	@Inject
	private ClanClogBatch clogBatch;

	@Inject
	private LocalClogCache clogCache;

	@Inject
	private LocalHiscoreCache hiscoreCache;

	@Inject
	private InGameClanReader clanReader;

	@Inject
	private ChatScanner chatScanner;

	// Injected so Guice instantiates + loads the line library at startup.
	// Used by ChatScanner via constructor injection; this field keeps the
	// singleton alive for the plugin lifetime.
	@Inject
	@SuppressWarnings("unused")
	private ClogsworthDispatcher clogsworth;

	private NavigationButton navButton;

	@Provides
	ClanClogConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClanClogConfig.class);
	}

	@Override
	protected void startUp()
	{
		navButton = NavigationButton.builder()
			.tooltip("Clan Clog")
			.icon(loadIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		log.debug("clan clog: startUp");
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		batch.shutdown();
		clogBatch.shutdown();
		clogCache.shutdown();
		hiscoreCache.shutdown();
		log.debug("clan clog: shutDown");
	}

	/**
	 * Sync the in-game clan roster every time the user opens the clan sidepanel.
	 * Fires on the client thread, so {@link InGameClanReader#refresh()} can read
	 * {@code ClanSettings} directly.
	 */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		int groupId = event.getGroupId();
		if (groupId == InterfaceID.CLANS_SIDEPANEL
			|| groupId == InterfaceID.CLANS_GUEST_SIDEPANEL)
		{
			clanReader.refresh();
		}
	}

	/**
	 * Forward clan-system chat broadcasts to {@link ChatScanner} which parses
	 * joined / left / kicked events, dispatches Clogsworth narration, and
	 * (next sub-phase) POSTs roster mutations to killclog-api.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		chatScanner.handle(event);
	}

	/**
	 * Load the bundled icon from resources, or return a flat placeholder if the
	 * resource is missing. Lets the plugin compile + run before an icon asset
	 * lands; the slice is correct without it.
	 */
	private static BufferedImage loadIcon()
	{
		try (InputStream in = ClanClogPlugin.class.getResourceAsStream("icon.png"))
		{
			if (in != null)
			{
				BufferedImage img = ImageIO.read(in);
				if (img != null)
				{
					return img;
				}
			}
		}
		catch (Exception e)
		{
			log.debug("icon.png load failed, using placeholder: {}", e.getMessage());
		}
		BufferedImage placeholder = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = placeholder.createGraphics();
		g.setColor(new Color(0x4caf50));
		g.fillRect(0, 0, 16, 16);
		g.dispose();
		return placeholder;
	}
}
