package com.clanclog;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Clan Clog",
	description = "Search a clan, see every member ranked",
	tags = {"clan", "hiscores", "420kc"}
)
public class ClanClogPlugin extends Plugin
{
	private static final int CLOG_INTERFACE = 621;
	private static final int CLOG_HEADER_CHILD = 20;
	private static final int CLOG_ITEMS_CHILD = 37;
	private static final int VARP_CLOG_OBTAINED = 2943;
	private static final int VARP_CLOG_TOTAL = 2944;
	private static final java.util.regex.Pattern OBTAINED_PATTERN =
		java.util.regex.Pattern.compile("(\\d+)/(\\d+)");

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Client client;

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
	private LocalClanProfileCache clanProfileCache;

	@Inject
	private InGameClanReader clanReader;

	@Inject
	private ChatScanner chatScanner;

	@Inject
	private TooltipController tooltipController;

	// Injected so Guice instantiates + loads the line library at startup.
	// Used by ChatScanner via constructor injection; this field keeps the
	// singleton alive for the plugin lifetime.
	@Inject
	@SuppressWarnings("unused")
	private ClogsworthDispatcher clogsworth;

	private NavigationButton navButton;
	private String lastVisibleClogSignature;

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
		tooltipController.captureDefaults();
		refreshInGameClanSoon();
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
		tooltipController.restoreDefaults();
		batch.shutdown();
		clogBatch.shutdown();
		clogCache.shutdown();
		hiscoreCache.shutdown();
		clanProfileCache.shutdown();
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
	 * Fill the Clan Clog profile from already-loaded runtime clan settings.
	 * This covers the common case where the player is logged in and the clan
	 * tab was opened before the plugin or dev client reloaded.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			refreshInGameClanSoon();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			clogCache.setActivePlayer(null);
			lastVisibleClogSignature = null;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		captureVisibleClogCategory();
	}

	private void refreshInGameClanSoon()
	{
		clientThread.invokeLater(() ->
		{
			refreshActivePlayer();
			try
			{
				clanReader.refresh();
			}
			catch (RuntimeException ex)
			{
				log.debug("clan reader refresh skipped: {}", ex.getMessage());
			}
		});
	}

	private void refreshActivePlayer()
	{
		Player local = client.getLocalPlayer();
		if (local != null && local.getName() != null)
		{
			clogCache.setActivePlayer(local.getName());
		}
	}

	private void captureVisibleClogCategory()
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return;
		}

		Widget header = client.getWidget(CLOG_INTERFACE, CLOG_HEADER_CHILD);
		if (header == null)
		{
			lastVisibleClogSignature = null;
			return;
		}
		Widget[] headerKids = header.getDynamicChildren();
		if (headerKids == null || headerKids.length < 2 || headerKids[0] == null)
		{
			return;
		}

		String headerText = headerKids[0].getText();
		if (headerText == null || headerText.isEmpty())
		{
			return;
		}
		String categoryName = Text.removeTags(headerText);
		String categoryKey = ClogHelper.bossToCategory(categoryName);

		int obtainedCount = -1;
		int totalCount = -1;
		if (headerKids[1] != null)
		{
			String obtainedText = Text.removeTags(headerKids[1].getText());
			java.util.regex.Matcher matcher = OBTAINED_PATTERN.matcher(obtainedText);
			if (matcher.find())
			{
				obtainedCount = Integer.parseInt(matcher.group(1));
				totalCount = Integer.parseInt(matcher.group(2));
			}
		}

		Widget items = client.getWidget(CLOG_INTERFACE, CLOG_ITEMS_CHILD);
		if (items == null)
		{
			return;
		}
		Widget[] children = items.getChildren();
		if (children == null || children.length == 0)
		{
			return;
		}

		List<Integer> allItemIds = new ArrayList<>();
		List<ClogResult.ClogItem> obtained = new ArrayList<>();
		for (Widget child : children)
		{
			if (child == null)
			{
				continue;
			}
			int itemId = child.getItemId();
			if (itemId <= 0)
			{
				continue;
			}
			allItemIds.add(itemId);
			if (child.getOpacity() == 0)
			{
				obtained.add(new ClogResult.ClogItem(itemId, Math.max(child.getItemQuantity(), 1), null));
			}
		}

		if (allItemIds.isEmpty())
		{
			return;
		}

		String signature = local.getName() + "|" + categoryKey + "|" + allItemIds.hashCode()
			+ "|" + itemSignature(obtained) + "|" + obtainedCount + "|" + totalCount;
		if (signature.equals(lastVisibleClogSignature))
		{
			return;
		}
		lastVisibleClogSignature = signature;

		clogCache.setActivePlayer(local.getName());
		clogCache.mergeCategory(local.getName(), categoryKey, allItemIds, obtained);

		int liveObtained = client.getVarpValue(VARP_CLOG_OBTAINED);
		int liveTotal = client.getVarpValue(VARP_CLOG_TOTAL);
		if (liveObtained > 0 || liveTotal > 0)
		{
			clogCache.updateTotals(local.getName(), liveObtained, liveTotal);
		}
		log.debug("Captured local clog category '{}': {}/{} for '{}'",
			categoryKey, obtained.size(), allItemIds.size(), local.getName());
	}

	private static int itemSignature(List<ClogResult.ClogItem> items)
	{
		int hash = 1;
		for (ClogResult.ClogItem item : items)
		{
			hash = 31 * hash + item.getId();
			hash = 31 * hash + item.getCount();
		}
		return hash;
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
