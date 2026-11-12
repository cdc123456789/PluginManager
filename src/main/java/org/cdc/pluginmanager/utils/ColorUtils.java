package org.cdc.pluginmanager.utils;

import java.awt.Color;

/**
 * 颜色计算工具：面板不直接写死颜色，而是从 {@code ThemeColorProvider} 的基础色
 * 派生出分隔线、悬停底色、选中底色、徽章色等语义色，从而同时适配亮色 / 暗色主题。
 *
 * @Developer user
 * @CreatedIn 2026/11/12
 */
public final class ColorUtils {

    private ColorUtils() {
    }

    /**
     * 按比例混合两个颜色。
     *
     * @param overlay 叠加色（ratio 越大占比越高）
     * @param base    底色
     * @param ratio   叠加比例，0 表示完全取 base，1 表示完全取 overlay
     */
    public static Color blend(Color overlay, Color base, float ratio) {
        float r = clamp(ratio);
        int red = Math.round(overlay.getRed() * r + base.getRed() * (1 - r));
        int green = Math.round(overlay.getGreen() * r + base.getGreen() * (1 - r));
        int blue = Math.round(overlay.getBlue() * r + base.getBlue() * (1 - r));
        return new Color(clampChannel(red), clampChannel(green), clampChannel(blue), 255);
    }

    /** 调整颜色的明度，{@code factor < 1} 变暗，{@code factor > 1} 变亮。 */
    public static Color shade(Color color, float factor) {
        int red = clampChannel(Math.round(color.getRed() * factor));
        int green = clampChannel(Math.round(color.getGreen() * factor));
        int blue = clampChannel(Math.round(color.getBlue() * factor));
        return new Color(red, green, blue, color.getAlpha());
    }

    /** 给颜色替换透明度。 */
    public static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampChannel(alpha));
    }

    /** WCAG 相对亮度，取值 0（纯黑）到 1（纯白）。 */
    public static double relativeLuminance(Color color) {
        return 0.2126 * linear(color.getRed())
                + 0.7152 * linear(color.getGreen())
                + 0.0722 * linear(color.getBlue());
    }

    /** 判断颜色是否属于"暗色"，用于在亮 / 暗主题下选择不同的派生色。 */
    public static boolean isDark(Color color) {
        return relativeLuminance(color) < 0.4;
    }

    /** 返回在给定底色上可读性更好的前景色（白或近黑）。 */
    public static Color contrasting(Color background) {
        return isDark(background) ? Color.WHITE : new Color(0x1F2329);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static float clamp(float ratio) {
        return Math.clamp(ratio, 0f, 1f);
    }

    private static int clampChannel(int value) {
        return Math.clamp(value, 0, 255);
    }
}
