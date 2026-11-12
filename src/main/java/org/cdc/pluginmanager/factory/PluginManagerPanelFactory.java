package org.cdc.pluginmanager.factory;

import net.mcreator.plugin.PluginLoader;
import org.cdc.pluginmanager.convert.PluginConverter;
import org.cdc.pluginmanager.impl.DefaultThemeColorProvider;
import org.cdc.pluginmanager.ui.PluginManagerPanel;

import javax.swing.*;

/**
 * &#064;Developer  user
 * &#064;CreatedIn  2026/11/13
 */
public class PluginManagerPanelFactory {
    private static PluginManagerPanel defaultInstance;

    public static void initDefaultPluginManagerPanel(){
        PluginManagerPanel panel = new PluginManagerPanel(new DefaultThemeColorProvider());
        panel.setPlugins(PluginConverter.convertAll(PluginLoader.INSTANCE.getPlugins(),
                PluginLoader.INSTANCE.getFailedPlugins()));
        defaultInstance = panel;
    }

    public static PluginManagerPanel createDefaultPluginManagerPanel() {
        if (defaultInstance == null){
            initDefaultPluginManagerPanel();
        }
        return defaultInstance;
    }
}
