package org.cdc.pluginmanager.init;

import org.cdc.pluginmanager.accessor.PreferenceSectionDialogAccessor;
import org.cdc.pluginmanager.factory.PluginManagerPanelFactory;
import org.cdc.pluginmanager.utils.L10NHelper;

/**
 * &#064;Developer  user
 * &#064;CreatedIn  2026/11/12
 */
public class PreferenceSections {
    public static void registerSecions(PreferenceSectionDialogAccessor preferenceSectionDialogAccessor) {
        // 用本地化标题作为设置树中的节点名，避免显示内部的 plugin_manager_cdc 标识
        preferenceSectionDialogAccessor.addSectionPanel(L10NHelper.t("plugin.manager.title"),
                PluginManagerPanelFactory.createDefaultPluginManagerPanel());
    }
}
