package com.clanclog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Expandable tray with an eased height animation. Ported from kill clog's
 * ActivitiesTray (kcpdev e8b8418). Clan clog uses it to hide the member list
 * behind a slide-out so the main panel stays focused on the collective
 * hiscore view.
 *
 * <p>Construction wires a content panel into a clip whose preferred-size
 * depends on the tray's expanded state; {@link #getHeader()} is the
 * separator + label row that doubles as the click target.
 */
public class SlidePanel
{
	private static final long SLIDE_MS = 150;
	private static final int SLIDE_TICK_MS = 12;

	private final JPanel content;
	private final JPanel clip;
	private final JPanel header;
	private final JLabel chevron;
	private boolean expanded;
	private Timer slideTimer;

	public SlidePanel(String label, JPanel content, boolean initiallyExpanded)
	{
		this.content = content;
		this.expanded = initiallyExpanded;

		this.clip = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getPreferredSize()
			{
				int h;
				if (slideTimer != null && slideTimer.isRunning())
				{
					h = super.getPreferredSize().height;
				}
				else if (expanded)
				{
					h = content.getPreferredSize().height;
				}
				else
				{
					h = 0;
				}
				return new Dimension(content.getPreferredSize().width, h);
			}
		};
		clip.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		clip.add(content, BorderLayout.NORTH);
		clip.setPreferredSize(new Dimension(0, expanded ? content.getPreferredSize().height : 0));
		clip.setVisible(expanded);

		this.header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		this.chevron = new JLabel(expanded ? "v" : ">");
		chevron.setFont(FontManager.getRunescapeSmallFont());
		chevron.setForeground(new Color(160, 160, 160));
		chevron.putClientProperty(
			java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		JLabel labelLabel = new JLabel(label);
		labelLabel.setFont(FontManager.getRunescapeSmallFont());
		labelLabel.setForeground(new Color(215, 215, 215));
		labelLabel.putClientProperty(
			java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		header.add(chevron);
		header.add(labelLabel);
		Color hdrNormal = ColorScheme.DARK_GRAY_COLOR;
		Color hdrHover = new Color(46, 46, 46);
		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				toggle();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				header.setBackground(hdrHover);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				header.setBackground(hdrNormal);
			}
		});
	}

	public Component getHeader()
	{
		return header;
	}

	public Component getClip()
	{
		return clip;
	}

	public boolean isExpanded()
	{
		return expanded;
	}

	public void toggle()
	{
		if (slideTimer != null && slideTimer.isRunning())
		{
			slideTimer.stop();
		}

		int startHeight = clip.getPreferredSize().height;
		expanded = !expanded;
		chevron.setText(expanded ? "v" : ">");

		int targetHeight = expanded ? content.getPreferredSize().height : 0;
		if (expanded)
		{
			clip.setVisible(true);
		}

		long startTime = System.currentTimeMillis();
		slideTimer = new Timer(SLIDE_TICK_MS, null);
		slideTimer.addActionListener(e ->
		{
			long elapsed = System.currentTimeMillis() - startTime;
			float progress = Math.min(1f, (float) elapsed / SLIDE_MS);
			float eased = 1f - (1f - progress) * (1f - progress);
			int height = startHeight + Math.round((targetHeight - startHeight) * eased);
			clip.setPreferredSize(new Dimension(0, height));
			if (clip.getParent() != null)
			{
				clip.getParent().revalidate();
			}

			if (progress >= 1f)
			{
				slideTimer.stop();
				if (!expanded)
				{
					clip.setVisible(false);
				}
			}
		});
		slideTimer.start();
	}
}
