package org.cdc.pluginmanager.utils;

import org.cdc.pluginmanager.PluginMain;

import java.util.Collection;

/**
 * 插件版本匹配工具：与 MCreator 的兼容判断逻辑保持一致。
 * <p>
 * MCreator 的规则（{@code Plugin#isCompatible()}）：
 * <ol>
 *     <li>未声明 {@code supportedversions} → 兼容所有版本；</li>
 *     <li>声明了当前完整版本号 → 兼容；</li>
 *     <li>非快照版且声明了当前主版本号 → 兼容（补丁版本）。</li>
 * </ol>
 * 面板用该结果高亮"支持版本"中与当前 MCreator 对应的那一项。
 *
 * @Developer user
 * @CreatedIn 2026/11/13
 */
public final class VersionHelper {

    private VersionHelper() {
    }

    /**
     * 返回支持版本列表中应高亮的版本号。
     *
     * @param supportedVersions 插件声明的 supportedversions
     * @return 匹配到的版本号；无匹配（或脱离 MCreator 运行时）返回 {@code null}
     */
    public static Long currentSupportedVersion(Collection<Long> supportedVersions) {
        if (supportedVersions == null || supportedVersions.isEmpty()) {
            return null;
        }
        try {
            long current = PluginMain.getCurrentVersionLong();
            if (supportedVersions.contains(current)) {
                return current;
            }
            long major = PluginMain.getMajorVersionLong();
            if (!PluginMain.isSnapshot() && supportedVersions.contains(major)) {
                return major;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Launcher 未初始化（例如脱离 MCreator 的独立预览），不高亮
        }
        return null;
    }
}
