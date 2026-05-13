package com.clanclog;

import java.awt.BorderLayout;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JPanel;
import net.runelite.client.ui.PluginPanel;

@Singleton
public class ClanClogPanel extends PluginPanel
{
	@Inject
	ClanClogPanel(ClanClogConfig config)
	{
		super(false);
		setLayout(new BorderLayout());
	}
}
