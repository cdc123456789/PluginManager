package org.cdc.pluginmanager.convert;

import net.mcreator.generator.Generator;
import net.mcreator.plugin.Plugin;
import net.mcreator.plugin.PluginLoadFailure;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdc.pluginmanager.data.PluginInfo;
import org.cdc.pluginmanager.utils.ZipUtils;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 MCreator 的插件模型转换为插件管理面板使用的 {@link PluginInfo}。
 * <p>
 * 对应关系：
 * <table border="1">
 *     <caption>字段映射</caption>
 *     <tr><th>PluginInfo</th><th>MCreator</th></tr>
 *     <tr><td>id</td><td>{@link net.mcreator.plugin.Plugin#getID()}</td></tr>
 *     <tr><td>name / description / author / dependencies</td>
 *         <td>{@link net.mcreator.plugin.Plugin#getInfo()} 中对应字段</td></tr>
 *     <tr><td>version</td><td>{@link net.mcreator.plugin.Plugin#getPluginVersion()}
 *         （内置插件为 MCreator 版本），缺省时回退到 info.version</td></tr>
 *     <tr><td>weight / javaPlugin / supportedVersions</td>
 *         <td>{@link net.mcreator.plugin.Plugin} 对应 getter</td></tr>
 *     <tr><td>homepageUrl</td><td>由 {@code info.pluginPageID} 拼接 MCreator 官网地址</td></tr>
 *     <tr><td>builtin</td><td>{@link net.mcreator.plugin.Plugin#isBuiltin()}</td></tr>
 *     <tr><td>loadFailure</td><td>{@link net.mcreator.plugin.Plugin#getLoadFailure()}</td></tr>
 *     <tr><td>filePath</td><td>{@link net.mcreator.plugin.Plugin#getFile()}</td></tr>
 *     <tr><td>supportedGeneratorVersions</td>
 *         <td>插件根目录下与 {@link Generator#GENERATOR_CACHE} 同名的文件夹</td></tr>
 *     <tr><td>image</td><td>插件根目录下的 {@code icon.png} / {@code ico.png}（解码并缓存为 {@link ImageIcon}）</td></tr>
 * </table>
 * <p>
 * MCreator 没有提供安装 / 卸载 / 启停插件的 API，因此转换结果只描述插件的静态信息与加载状态，
 * 面板不会提供这些操作。
 * <p>
 * 插件列表由调用方传入，本类不依赖 {@code PluginLoader}，因此可以在任意时机、
 * 用任意来源的插件集合做转换（例如扫描结果、测试数据等）。典型接入方式：
 * <pre>{@code
 * List<PluginInfo> plugins = PluginConverter.convertAll(
 *         PluginLoader.INSTANCE.getPlugins(), PluginLoader.INSTANCE.getFailedPlugins());
 * panel.setPlugins(plugins);
 * }</pre>
 *
 * @Developer user
 * @CreatedIn 2026/11/12
 */
public final class PluginConverter {

    private static final Logger LOG = LogManager.getLogger(PluginConverter.class);

    /**
     * MCreator 插件详情页前缀，pluginPageID 对应 https://mcreator.net/plugin/{id}
     */
    private static final String MCREATOR_PLUGIN_PAGE_URL = "https://mcreator.net/plugin/";

    /**
     * 插件文件 → 根目录文件夹集合的缓存；文件被替换（大小/时间变化）时自动失效。
     */
    private static final Map<String, CachedRoots> ROOT_CACHE = new ConcurrentHashMap<>();

    /** 插件文件 → 图标缓存（没有图标时缓存 null 值，避免重复扫描）。 */
    private static final Map<String, CachedIcon> ICON_CACHE = new ConcurrentHashMap<>();

    /** 图标解码后保留的最大边长；面板最大显示 56px，保留 128px 足够清晰且省内存。 */
    private static final int MAX_ICON_DIMENSION = 128;

    private PluginConverter() {
    }

    /**
     * 转换单个 MCreator 插件。
     *
     * @param plugin MCreator 的插件定义，可为 {@code null}
     * @return 转换结果；入参为 {@code null} 时返回 {@code null}
     */
    public static PluginInfo convert(net.mcreator.plugin.Plugin plugin) {
        if (plugin == null) {
            return null;
        }
        net.mcreator.plugin.PluginInfo source = plugin.getInfo();

        PluginInfo target = new PluginInfo();
        target.id = plugin.getID();
        target.name = resolveName(plugin, source);
        target.version = resolveVersion(plugin, source);
        target.description = source != null ? source.getDescription() : null;
        target.author = source != null ? source.getAuthor() : null;
        target.homepageUrl = resolveHomepage(source);
        target.weight = plugin.getWeight();
        target.javaPlugin = plugin.getJavaPlugin();

        if (plugin.getSupportedVersions() != null) {
            target.supportedVersions.addAll(plugin.getSupportedVersions());
        }
        if (source != null && source.getDependencies() != null) {
            target.dependencies.addAll(source.getDependencies());
        }

        target.builtin = plugin.isBuiltin();
        target.loadFailure = plugin.getLoadFailure();
        target.filePath = plugin.getFile() != null ? plugin.getFile().getAbsolutePath() : null;
        target.supportedGeneratorVersions = resolveGeneratorVersions(target.filePath);
        target.image = resolveIcon(plugin.getFile());
        return target;
    }


    /**
     * 批量转换插件集合（跳过 {@code null} 元素），保持原有顺序。
     * <p>
     * 单个插件转换失败不会中断整批转换。
     */
    public static List<PluginInfo> convertAll(Collection<? extends net.mcreator.plugin.Plugin> plugins) {
        List<PluginInfo> result = new ArrayList<>();
        if (plugins == null) {
            return result;
        }
        for (net.mcreator.plugin.Plugin plugin : plugins) {
            try {
                PluginInfo converted = convert(plugin);
                if (converted != null) {
                    result.add(converted);
                }
            } catch (RuntimeException | LinkageError ex) {
                LOG.warn("Failed to convert plugin {}", plugin.getID(), ex);
            }
        }
        return result;
    }

    /**
     * 同时转换插件列表与加载失败记录，并按插件 ID 去重。
     * <p>
     * {@link net.mcreator.plugin.PluginLoader#getPlugins()} 中已经包含加载失败但仍在列表里的插件，
     * {@link net.mcreator.plugin.PluginLoader#getFailedPlugins()} 又会为它们生成失败记录，
     * 因此两个集合直接拼接会出现重复条目；该方法会优先保留信息完整的插件条目。
     */
    public static List<PluginInfo> convertAll(Collection<? extends Plugin> plugins,
                                              Collection<PluginLoadFailure> failures) {
        List<PluginInfo> result = convertAll(plugins);
        if (failures == null || failures.isEmpty()) {
            return result;
        }
        HashMap<String, PluginInfo> pluginInfoHashMap = new HashMap<>();
        for (PluginInfo info : result) {
            pluginInfoHashMap.put(info.id, info);
        }
        for (PluginLoadFailure failure : failures) {
            if (failure == null) {
                continue;
            }
            String id = failure.pluginID();
            if (id != null && pluginInfoHashMap.containsKey(id)) {
                var plugin = pluginInfoHashMap.get(id);
                if (plugin.filePath.equals(failure.pluginFile().getAbsolutePath())) {
                    plugin.loadFailure = failure.message();
                }
                continue;
            }
            result.add(convert(failure));
        }
        return result;
    }

    /**
     * 把加载失败的插件记录转换为只读条目，便于面板展示失败原因。
     */
    public static PluginInfo convert(PluginLoadFailure failure) {
        if (failure == null) {
            return null;
        }
        PluginInfo target = new PluginInfo();
        target.id = failure.pluginID();
        target.name = failure.pluginID();
        target.loadFailure = failure.message();
        target.filePath = failure.pluginFile() != null ? failure.pluginFile().getAbsolutePath() : null;
        // 加载失败的插件也尽量显示自带图标，便于在列表中辨认
        target.image = resolveIcon(failure.pluginFile());
        return target;
    }

    /**
     * 读取插件根目录下的 {@code icon.png}（其次 {@code ico.png}）并缓存。
     * <p>
     * 图标只在文件变化（路径 + 修改时间 + 大小）时重新解码；没有图标或解码失败时返回 {@code null}，
     * 面板会回退到首字母图标。
     */
    private static ImageIcon resolveIcon(File plugin) {
        if (plugin == null || !plugin.exists()) {
            return null;
        }
        String key = plugin.getAbsolutePath();
        long modified = plugin.lastModified();
        long size = plugin.length();
        CachedIcon cached = ICON_CACHE.get(key);
        if (cached != null && cached.matches(modified, size)) {
            return cached.icon();
        }
        ImageIcon icon = loadIcon(plugin);
        if (ICON_CACHE.size() > 512) {
            ICON_CACHE.clear();
        }
        ICON_CACHE.put(key, new CachedIcon(modified, size, icon));
        return icon;
    }

    private static ImageIcon loadIcon(File plugin) {
        byte[] data = ZipUtils.readRootFile(plugin, "icon.png", "ico.png");
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            return image == null ? null : new ImageIcon(downscaleIcon(image));
        } catch (IOException | RuntimeException ex) {
            LOG.debug("Failed to read plugin icon from {}", plugin, ex);
            return null;
        }
    }

    /** 过大的图标先等比缩小，避免把原始分辨率的大图常驻内存。 */
    private static BufferedImage downscaleIcon(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0 || (width <= MAX_ICON_DIMENSION && height <= MAX_ICON_DIMENSION)) {
            return image;
        }
        double scale = Math.min((double) MAX_ICON_DIMENSION / width, (double) MAX_ICON_DIMENSION / height);
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(image, 0, 0, targetWidth, targetHeight, null);
        g2.dispose();
        return scaled;
    }

    /* ==================== 生成器支持检测 ==================== */

    /**
     * 检测插件支持的生成器：取插件根目录下的一级文件夹，与当前已加载的生成器名求交集。
     * <p>
     * 每个插件文件只会被扫描一次（按路径 + 修改时间 + 大小缓存），
     * 目录型插件（内置插件）与损坏的 zip 都会安全降级为空列表。
     */
    private static List<String> resolveGeneratorVersions(String filePath) {
        Set<String> roots = rootFolders(filePath);
        if (roots.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            Set<String> supported = new LinkedHashSet<>();
            List<String> loadedGenerators;
            synchronized (Generator.GENERATOR_CACHE) {
                loadedGenerators = new ArrayList<>(Generator.GENERATOR_CACHE.keySet());
            }
            for (String generator : loadedGenerators) {
                if (roots.contains(rootOf(generator))) {
                    supported.add(generator);
                }
            }
            if (supported.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>(supported);
            result.sort(String.CASE_INSENSITIVE_ORDER);
            return Collections.unmodifiableList(result);
        } catch (RuntimeException | LinkageError ex) {
            // 独立运行 / 生成器尚未初始化时，降级为不显示生成器支持
            LOG.debug("Generator support detection unavailable for {}", filePath, ex);
            return Collections.emptyList();
        }
    }

    private static Set<String> rootFolders(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Collections.emptySet();
        }
        File file = new File(filePath);
        if (!file.exists()) {
            return Collections.emptySet();
        }
        String key = file.getAbsolutePath();
        CachedRoots cached = ROOT_CACHE.get(key);
        long modified = file.lastModified();
        long size = file.length();
        if (cached != null && cached.matches(modified, size)) {
            return cached.roots();
        }
        Set<String> roots = ZipUtils.rootFolderNames(file);
        if (ROOT_CACHE.size() > 512) {
            ROOT_CACHE.clear();
        }
        ROOT_CACHE.put(key, new CachedRoots(modified, size, roots));
        return roots;
    }

    private static String rootOf(String generatorName) {
        int slash = generatorName.indexOf('/');
        return slash > 0 ? generatorName.substring(0, slash) : generatorName;
    }

    /* ==================== 字段解析 ==================== */

    private static String resolveName(net.mcreator.plugin.Plugin plugin,
                                      net.mcreator.plugin.PluginInfo source) {
        if (source != null && !isBlank(source.getName())) {
            return source.getName();
        }
        return plugin.getID();
    }

    private static String resolveVersion(net.mcreator.plugin.Plugin plugin,
                                         net.mcreator.plugin.PluginInfo source) {
        try {
            String version = plugin.getPluginVersion();
            if (!isBlank(version) && !net.mcreator.plugin.PluginInfo.VERSION_NOT_SPECIFIED.equals(version)) {
                return version;
            }
        } catch (RuntimeException ignored) {
            // 内置插件依赖 MCreator 版本信息，独立运行时可能不可用
        }
        if (source != null && !isBlank(source.getVersion())
                && !net.mcreator.plugin.PluginInfo.VERSION_NOT_SPECIFIED.equals(source.getVersion())) {
            return source.getVersion();
        }
        return null;
    }

    private static String resolveHomepage(net.mcreator.plugin.PluginInfo source) {
        if (source != null && source.getPluginPageID() > 0) {
            return MCREATOR_PLUGIN_PAGE_URL + source.getPluginPageID();
        }
        return null;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /**
     * 插件文件对应的根目录扫描结果缓存，文件大小或修改时间变化时失效。
     */
    private record CachedRoots(long modified, long size, Set<String> roots) {
        boolean matches(long modified, long size) {
            return this.modified == modified && this.size == size;
        }
    }

    /** 图标缓存；{@code icon} 为 {@code null} 表示该插件没有可用图标。 */
    private record CachedIcon(long modified, long size, ImageIcon icon) {
        boolean matches(long modified, long size) {
            return this.modified == modified && this.size == size;
        }
    }
}
