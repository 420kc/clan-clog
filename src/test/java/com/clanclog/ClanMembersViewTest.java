package com.clanclog;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClanMembersViewTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void profileSearchRowsRenderStoredClanLabelsAndOpenSlug() throws Exception
	{
		KillclogApiClient.ClanSearchResponse response = GSON.fromJson("{"
			+ "\"matches\":[{"
			+ "\"slug\":\"clannabis\","
			+ "\"display_name\":\"Clannabis\","
			+ "\"source_tier\":\"game_verified\","
			+ "\"build_status\":\"ready\","
			+ "\"member_count\":37"
			+ "}]"
			+ "}", KillclogApiClient.ClanSearchResponse.class);

		AtomicReference<String> pickedSlug = new AtomicReference<>();
		AtomicReference<ClanMembersView> viewRef = new AtomicReference<>();

		SwingUtilities.invokeAndWait(() ->
		{
			ClanMembersView view = new ClanMembersView();
			view.renderProfileSearchResults(response.getMatches(), pickedSlug::set);
			viewRef.set(view);
		});

		List<String> labels = labelTexts(viewRef.get());
		assertTrue(labels.contains("Clannabis"));
		assertTrue(labels.contains("37 · game verified"));

		SwingUtilities.invokeAndWait(() -> clickFirstRow(viewRef.get()));
		assertEquals("clannabis", pickedSlug.get());
	}

	private static List<String> labelTexts(Container root)
	{
		List<String> labels = new ArrayList<>();
		collectLabels(root, labels);
		return labels;
	}

	private static void collectLabels(Component component, List<String> labels)
	{
		if (component instanceof JLabel)
		{
			labels.add(((JLabel) component).getText());
		}
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				collectLabels(child, labels);
			}
		}
	}

	private static void clickFirstRow(Container root)
	{
		for (Component component : root.getComponents())
		{
			if (component instanceof Container)
			{
				Container child = (Container) component;
				if (child.getMouseListeners().length > 0)
				{
					MouseEvent click = new MouseEvent(child, MouseEvent.MOUSE_CLICKED,
						System.currentTimeMillis(), 0, 4, 4, 1, false);
					for (MouseListener listener : child.getMouseListeners())
					{
						listener.mouseClicked(click);
					}
					return;
				}
				clickFirstRow(child);
				if (root instanceof ClanMembersView)
				{
					return;
				}
			}
		}
	}
}
