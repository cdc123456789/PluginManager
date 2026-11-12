package org.cdc.pluginmanager.utils;

import net.mcreator.ui.init.L10N;

import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局化文本入口。
 * <p>
 * 查找顺序：
 * <ol>
 *     <li>MCreator 的 {@link L10N}（生产环境，会包含插件 lang 目录里的翻译）；</li>
 *     <li>插件自带的 {@code lang/texts[_locale].properties}（独立预览 / MCreator 未加载插件语言文件时）；</li>
 *     <li>最后回退为 key 本身，便于发现漏翻的文本。</li>
 * </ol>
 * 参数使用 {@link String#format(String, Object...)} 的 {@code %s} / {@code %d} 占位符，
 * 与 MCreator 语言文件的写法保持一致。
 * <p>
 * 定位到的文本模板会缓存，避免列表滚动 / 反复重绘时重复查表；参数在每次调用时格式化。
 * <p>
 * 独立运行预览时如果只想看 key（方便定位漏翻），可加 JVM 参数
 * {@code -Dpluginmanager.i18n.keyOnly=true}。
 *
 * @Developer user
 * @CreatedIn 2026/11/12
 */
public final class L10NHelper {

    private static final String BUNDLE_NAME = "lang.texts";
    private static final boolean KEY_ONLY = Boolean.getBoolean("pluginmanager.i18n.keyOnly");

    private static final Map<String, String> PATTERN_CACHE = new ConcurrentHashMap<>();

    private static volatile ResourceBundle bundle;
    private static volatile boolean bundleResolved;

    private L10NHelper() {
    }

    public static String t(String key, Object... args) {
        if (key == null) {
            return "";
        }
        String pattern = PATTERN_CACHE.computeIfAbsent(key, L10NHelper::loadPattern);
        if (pattern == null || pattern.isEmpty()) {
            return "";
        }
        if (args != null && args.length > 0 && pattern.indexOf('%') >= 0) {
            try {
                return String.format(pattern, args);
            } catch (IllegalArgumentException ignored) {
                // 文本里的 % 不是合法的格式占位符，按原文返回
            }
        }
        return pattern;
    }

    private static String loadPattern(String key) {
        if (KEY_ONLY) {
            return key;
        }
        String localized = fromMCreator(key);
        if (localized != null && !localized.isEmpty() && !key.equals(localized)) {
            return localized;
        }
        String bundled = fromBundle(key);
        return bundled != null ? bundled : key;
    }

    private static String fromMCreator(String key) {
        try {
            return L10N.t(key);
        } catch (LinkageError | RuntimeException ex) {
            // 脱离 MCreator 运行时预览时 L10N 可能未初始化，交给插件自带语言文件
            return null;
        }
    }

    private static String fromBundle(String key) {
        ResourceBundle resolved = bundle();
        if (resolved == null || !resolved.containsKey(key)) {
            return null;
        }
        try {
            return resolved.getString(key);
        } catch (MissingResourceException ex) {
            return null;
        }
    }

    private static ResourceBundle bundle() {
        if (!bundleResolved) {
            synchronized (L10NHelper.class) {
                if (!bundleResolved) {
                    try {
                        bundle = ResourceBundle.getBundle(BUNDLE_NAME, Locale.getDefault());
                    } catch (MissingResourceException ex) {
                        bundle = null;
                    }
                    bundleResolved = true;
                }
            }
        }
        return bundle;
    }
}
