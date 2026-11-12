package org.cdc.pluginmanager;

import net.mcreator.Launcher;
import net.mcreator.plugin.JavaPlugin;
import net.mcreator.plugin.Plugin;
import net.mcreator.plugin.events.ui.PreferencesDialogEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdc.pluginmanager.accessor.PreferenceSectionDialogAccessor;
import org.cdc.pluginmanager.init.PreferenceSections;

public class PluginMain extends JavaPlugin {

    private static final Logger LOG = LogManager.getLogger(PluginMain.class);

    public PluginMain(Plugin plugin) {
        super(plugin);

        addListener(PreferencesDialogEvent.SectionsLoaded.class, event ->
                PreferenceSections.registerSecions(new PreferenceSectionDialogAccessor(event.getPreferencesDialog())));

        LOG.info("Plugin Manager was loaded");
    }

    /** 当前运行的 MCreator 完整版本号（用于高亮插件支持的版本）。 */
    public static long getCurrentVersionLong() {
        return Launcher.version.versionlong;
    }

    /** 当前运行的 MCreator 主版本号（补丁版本兼容判断用）。 */
    public static long getMajorVersionLong() {
        return Launcher.version.majorlong;
    }

    /** 当前运行的 MCreator 是否为快照版（快照版不做主版本兼容判断）。 */
    public static boolean isSnapshot() {
        return Launcher.version.isSnapshot();
    }
}
