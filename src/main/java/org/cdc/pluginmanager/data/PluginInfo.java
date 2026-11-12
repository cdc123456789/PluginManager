package org.cdc.pluginmanager.data;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class PluginInfo {

    /** 图标底色候选（按插件 id 稳定选取），避免每次取色都重新分配数组。 */
    private static final Color[] ICON_PALETTE = {
            new Color(0x3574F0), new Color(0x8B5CF6), new Color(0x10B981),
            new Color(0xF97316), new Color(0xEF4444), new Color(0x0EA5E9),
            new Color(0x22C55E), new Color(0x64748B)
    };

    /** 插件唯一标识 */
    public String id;
    /** 支持的版本号列表（示例中数值超出 int 范围，使用 long） */
    public List<Long> supportedVersions = new ArrayList<>();
    /** 加载权重（MCreator 中数值越大加载越早） */
    public int weight;
    /** Java 主类全限定名 */
    public String javaPlugin;
    /** 显示名称 */
    public String name;
    /** 插件版本 */
    public String version;
    /** 描述 */
    public String description;
    /** 作者 */
    public String author;

    /* 运行时状态（不来自 plugin.json） */
    /** 是否为 MCreator 内置插件（随 MCreator 发行） */
    public boolean builtin;
    /** 加载失败原因；为 null 表示加载成功（由 MCreator 的 Plugin#getLoadFailure 转换而来） */
    public String loadFailure;
    /** 插件文件（目录或 zip）的绝对路径 */
    public String filePath;
    /** 依赖的其他插件 ID，来自 MCreator 的 info.dependencies */
    public List<String> dependencies = new ArrayList<>();
    /**
     * 支持的生成器版本，由 PluginConverter 通过插件根目录与已加载生成器求交集得到。
     * 例如 ["neoforge-1.21.1", "fabric-1.20.1"]，UI 仅负责展示。
     */
    public List<String> supportedGeneratorVersions = new ArrayList<>();

    /* 可选扩展：插件主页，plugin.json 未提供时不展示按钮 */
    public String homepageUrl;

    /**
     * 插件自带图标：由 PluginConverter 读取插件根目录的 {@code icon.png} / {@code ico.png} 并缓存；
     * 为 {@code null} 时面板回退到按名称首字母绘制的图标。
     */
    public ImageIcon image;

    /** 图标文字：取名称首字母大写 */
    public String iconText() {
        if (name == null || name.isEmpty()) return "?";
        return name.substring(0, 1).toUpperCase();
    }

    /** 图标颜色：按 id 稳定散列到一组预设色 */
    public Color iconColor() {
        int hash = id == null ? 0 : id.hashCode();
        // floorMod：避免 Math.abs(Integer.MIN_VALUE) 仍为负数导致数组越界
        return ICON_PALETTE[Math.floorMod(hash, ICON_PALETTE.length)];
    }
}
