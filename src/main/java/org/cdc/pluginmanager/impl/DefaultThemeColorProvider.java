package org.cdc.pluginmanager.impl;

import net.mcreator.ui.laf.themes.Theme;
import org.cdc.pluginmanager.interfaces.ThemeColorProvider;

import java.awt.*;

/**
 * 生产环境使用的颜色提供者：每次调用都从当前 MCreator 主题取值，
 * 因此主题切换后只要面板重新读取一次颜色（{@code PluginManagerPanel#refreshTheme()}）
 * 即可让面板跟上新主题。脱离 MCreator 运行时（{@code Theme.current()} 为 null）时
 * 自动回退到亮色近似配色，保证面板仍可用于独立预览。
 *
 * @Developer user
 * @CreatedIn 2026/11/12
 */
public class DefaultThemeColorProvider implements ThemeColorProvider {

    private static final ThemeColorProvider FALLBACK = StandaloneThemeColorProvider.light();

    @Override
    public Color getBackgroundColor() {
        Theme theme = Theme.current();
        return theme != null ? theme.getBackgroundColor() : FALLBACK.getBackgroundColor();
    }

    @Override
    public Color getAltBackgroundColor() {
        Theme theme = Theme.current();
        return theme != null ? theme.getAltBackgroundColor() : FALLBACK.getAltBackgroundColor();
    }

    @Override
    public Color getSecondAltBackgroundColor() {
        Theme theme = Theme.current();
        return theme != null ? theme.getSecondAltBackgroundColor() : FALLBACK.getSecondAltBackgroundColor();
    }

    @Override
    public Color getForegroundColor() {
        Theme theme = Theme.current();
        return theme != null ? theme.getForegroundColor() : FALLBACK.getForegroundColor();
    }

    @Override
    public Color getAltForegroundColor() {
        Theme theme = Theme.current();
        return theme != null ? theme.getAltForegroundColor() : FALLBACK.getAltForegroundColor();
    }

    @Override
    public Color getInterfaceAccentColor() {
        Theme theme = Theme.current();
        return theme != null ? theme.getInterfaceAccentColor() : FALLBACK.getInterfaceAccentColor();
    }
}
