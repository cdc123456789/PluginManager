package org.cdc.pluginmanager.interfaces;

import org.cdc.pluginmanager.utils.ColorUtils;

import java.awt.*;

/**
 * 主题颜色提供者。
 * <p>
 * 前 6 个方法是 MCreator 主题必然提供的基础色，由具体实现（如
 * {@code DefaultThemeColorProvider}）转发给 {@code net.mcreator.ui.laf.themes.Theme}；
 * 其余方法都是带默认实现的<b>语义色</b>，面板只使用语义色，因此新增配色需求时
 * 实现类无需修改，需要精调时再覆盖对应方法即可。
 * <p>
 * 所有默认派生都基于基础色之间的混合，因此同一个面板可以同时适配亮色与暗色主题。
 *
 * @Developer user
 * @CreatedIn 2026/11/12
 */
public interface ThemeColorProvider {

    /* ==================== 基础色（由主题实现提供） ==================== */

    /** 面板整体背景色。 */
    Color getBackgroundColor();

    /** 卡片 / 详情面板等"浮起"区域的背景色。 */
    Color getAltBackgroundColor();

    /** 第二层背景色，用于输入框、中性徽章等。 */
    Color getSecondAltBackgroundColor();

    /** 主文字颜色。 */
    Color getForegroundColor();

    /** 次要文字颜色。 */
    Color getAltForegroundColor();

    /** 主题强调色（主按钮、选中态、链接）。 */
    Color getInterfaceAccentColor();

    /* ==================== 派生语义色（可按需覆盖） ==================== */

    /** 分隔线 / 边框颜色。 */
    default Color getBorderColor() {
        return ColorUtils.blend(getForegroundColor(), getBackgroundColor(), 0.14f);
    }

    /** 鼠标悬停时的底色。 */
    default Color getHoverBackgroundColor() {
        return ColorUtils.blend(getInterfaceAccentColor(), getAltBackgroundColor(), 0.06f);
    }

    /** 选中项的底色。 */
    default Color getSelectionBackgroundColor() {
        return ColorUtils.blend(getInterfaceAccentColor(), getBackgroundColor(), 0.14f);
    }

    /** 强调色按下 / 悬停时的深色。 */
    default Color getInterfaceAccentColorPressed() {
        return ColorUtils.shade(getInterfaceAccentColor(), 0.85f);
    }

    /** 强调色之上的文字颜色（主按钮文字）。 */
    default Color getAccentForegroundColor() {
        return ColorUtils.contrasting(getInterfaceAccentColor());
    }

    /** 输入框背景色。 */
    default Color getInputBackgroundColor() {
        return getSecondAltBackgroundColor();
    }

    /** 成功 / 已启用状态色。 */
    default Color getSuccessColor() {
        return isDark() ? new Color(0x6FD69B) : new Color(0x1E7B3C);
    }

    /** 成功 / 已启用状态的背景色。 */
    default Color getSuccessBackgroundColor() {
        return ColorUtils.blend(getSuccessColor(), getBackgroundColor(), 0.14f);
    }

    /** 中性 / 停用状态的背景色。 */
    default Color getNeutralBackgroundColor() {
        return getSecondAltBackgroundColor();
    }

    /** 中性 / 停用状态的前景色。 */
    default Color getNeutralForegroundColor() {
        return ColorUtils.blend(getForegroundColor(), getAltForegroundColor(), 0.35f);
    }

    /** 标签（芯片）的前景色。 */
    default Color getTagColor() {
        return getInterfaceAccentColor();
    }

    /** 标签（芯片）的背景色。 */
    default Color getTagBackgroundColor() {
        return ColorUtils.blend(getInterfaceAccentColor(), getAltBackgroundColor(), 0.12f);
    }

    /** 错误 / 加载失败状态色。 */
    default Color getErrorColor() {
        return isDark() ? new Color(0xF08A7A) : new Color(0xB3261E);
    }

    /** 错误 / 加载失败状态的背景色。 */
    default Color getErrorBackgroundColor() {
        return ColorUtils.blend(getErrorColor(), getBackgroundColor(), 0.14f);
    }

    /** 当前主题是否为暗色主题。 */
    default boolean isDark() {
        return ColorUtils.isDark(getBackgroundColor());
    }
}
