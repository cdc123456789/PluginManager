package org.cdc.pluginmanager.impl;

import org.cdc.pluginmanager.interfaces.ThemeColorProvider;

import java.awt.*;

/**
 * 脱离 MCreator 运行时也能使用的颜色提供者，颜色取自 MCreator 亮色 / 暗色主题的近似值。
 * <p>
 * 主要用于 {@code PluginManagerPanel#main(String[])} 的独立预览，以及无 MCreator
 * 环境下的 UI 测试；生产环境请使用 {@link DefaultThemeColorProvider}。
 *
 * @Developer user
 * @CreatedIn 2026/11/12
 */
public final class StandaloneThemeColorProvider implements ThemeColorProvider {

    private final Color background;
    private final Color altBackground;
    private final Color secondAltBackground;
    private final Color foreground;
    private final Color altForeground;
    private final Color accent;

    private StandaloneThemeColorProvider(Color background, Color altBackground, Color secondAltBackground,
                                         Color foreground, Color altForeground, Color accent) {
        this.background = background;
        this.altBackground = altBackground;
        this.secondAltBackground = secondAltBackground;
        this.foreground = foreground;
        this.altForeground = altForeground;
        this.accent = accent;
    }

    /** MCreator 默认亮色主题的近似配色。 */
    public static StandaloneThemeColorProvider light() {
        return new StandaloneThemeColorProvider(
                new Color(0xF7F8FA),
                new Color(0xFFFFFF),
                new Color(0xEFF1F4),
                new Color(0x1F2329),
                new Color(0x8A9099),
                new Color(0x3574F0));
    }

    /** MCreator 默认暗色主题的近似配色。 */
    public static StandaloneThemeColorProvider dark() {
        return new StandaloneThemeColorProvider(
                new Color(0x1E1F22),
                new Color(0x2B2D30),
                new Color(0x35373B),
                new Color(0xDFE1E5),
                new Color(0x9DA0A8),
                new Color(0x4C8DF6));
    }

    @Override
    public Color getBackgroundColor() {
        return background;
    }

    @Override
    public Color getAltBackgroundColor() {
        return altBackground;
    }

    @Override
    public Color getSecondAltBackgroundColor() {
        return secondAltBackground;
    }

    @Override
    public Color getForegroundColor() {
        return foreground;
    }

    @Override
    public Color getAltForegroundColor() {
        return altForeground;
    }

    @Override
    public Color getInterfaceAccentColor() {
        return accent;
    }
}
