package org.cdc.pluginmanager.utils;

import java.io.File;

/**
 * &#064;Developer  user
 * &#064;CreatedIn  2026/11/13
 */
public class DesktopUtils {
    public static void selectOnlyFile(String file){
        net.mcreator.util.DesktopUtils.openSafe(new File(file),true);
    }

    public static void browse(String url){
        net.mcreator.util.DesktopUtils.browseSafe(url);
    }
}
