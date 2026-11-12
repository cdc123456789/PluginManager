package org.cdc.pluginmanager.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 插件资源扫描工具。
 * <p>
 * 插件的生成器支持情况由插件根目录下的同名文件夹决定（例如 zip 内的
 * {@code neoforge-1.21.1/...}）。这里只读取一次条目列表，得到根目录下的一级文件夹名，
 * 由调用方与 MCreator 的生成器列表求交集，避免对每个生成器都重新打开一次 zip。
 *
 * @Developer user
 * @CreatedIn 2026/11/13
 */
public final class ZipUtils {

    /** 读取根目录文件时的最大字节数，避免加载异常巨大的图标 / 资源。 */
    private static final int MAX_ROOT_FILE_BYTES = 4 * 1024 * 1024;

    private ZipUtils() {
    }

    /**
     * 读取插件根目录下的一级文件夹名。
     * <p>
     * 支持两种插件形态：
     * <ul>
     *     <li>zip / jar 包：取所有条目的第一段路径；</li>
     *     <li>目录形式（内置插件）：取直接子目录的名字。</li>
     * </ul>
     *
     * @param path 插件文件或目录路径，可为 {@code null}
     * @return 一级文件夹名集合；路径无效或读取失败时返回空集合，不抛异常
     */
    public static Set<String> rootFolderNames(String path) {
        if (path == null || path.isBlank()) {
            return Collections.emptySet();
        }
        return rootFolderNames(new File(path));
    }

    /**
     * 读取插件根目录下的一级文件夹名，读取失败时返回空集合。
     */
    public static Set<String> rootFolderNames(File file) {
        if (file == null || !file.exists()) {
            return Collections.emptySet();
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles(File::isDirectory);
            if (children == null || children.length == 0) {
                return Collections.emptySet();
            }
            Set<String> names = new LinkedHashSet<>();
            for (File child : children) {
                names.add(child.getName());
            }
            return names;
        }
        if (!file.isFile()) {
            return Collections.emptySet();
        }
        try (ZipFile zipFile = new ZipFile(file, StandardCharsets.UTF_8)) {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = normalize(entry.getName());
                if (name.isEmpty()) {
                    continue;
                }
                int slash = name.indexOf('/');
                if (slash > 0) {
                    names.add(name.substring(0, slash));
                } else if (slash < 0 && entry.isDirectory()) {
                    names.add(name);
                }
            }
            return names;
        } catch (IOException | RuntimeException ex) {
            // 损坏的 zip / 不可读文件：视为没有根目录信息，由调用方降级处理
            return Collections.emptySet();
        }
    }

    /**
     * 读取插件根目录下的文件（支持 zip 与目录两种插件形态）。
     * <p>
     * 按 {@code candidateNames} 的顺序优先：若根目录同时存在多个候选文件，
     * 返回排在最前面那个；文件不存在、读取失败或超过 {@value #MAX_ROOT_FILE_BYTES} 字节时返回 {@code null}。
     *
     * @param path           插件文件或目录路径
     * @param candidateNames 根目录下的候选文件名（大小写不敏感）
     * @return 文件内容；不存在或读取失败时返回 {@code null}
     */
    public static byte[] readRootFile(String path, String... candidateNames) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return readRootFile(new File(path), candidateNames);
    }

    /**
     * 读取插件根目录下的文件，读取失败时返回 {@code null}。
     */
    public static byte[] readRootFile(File file, String... candidateNames) {
        if (file == null || !file.exists() || candidateNames == null || candidateNames.length == 0) {
            return null;
        }
        if (file.isDirectory()) {
            return readRootFileFromDirectory(file, candidateNames);
        }
        if (!file.isFile()) {
            return null;
        }
        try (ZipFile zipFile = new ZipFile(file, StandardCharsets.UTF_8)) {
            int bestIndex = Integer.MAX_VALUE;
            byte[] bestData = null;
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalize(entry.getName());
                // 只接受根目录下的文件：icon.png，而不是 assets/icon.png
                if (name.isEmpty() || name.indexOf('/') >= 0) {
                    continue;
                }
                for (int i = 0; i < candidateNames.length; i++) {
                    String candidate = candidateNames[i];
                    if (candidate == null || candidate.isBlank() || i >= bestIndex) {
                        continue;
                    }
                    if (name.equalsIgnoreCase(candidate)) {
                        byte[] data = readZipEntry(zipFile, entry);
                        if (data != null && data.length > 0) {
                            bestIndex = i;
                            bestData = data;
                        }
                    }
                }
                if (bestIndex == 0) {
                    break;
                }
            }
            return bestData;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static byte[] readRootFileFromDirectory(File directory, String... candidateNames) {
        File[] children = directory.listFiles(File::isFile);
        if (children == null || children.length == 0) {
            return null;
        }
        for (String candidate : candidateNames) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            for (File child : children) {
                if (child.getName().equalsIgnoreCase(candidate)) {
                    return readFileBytes(child);
                }
            }
        }
        return null;
    }

    private static byte[] readFileBytes(File file) {
        try {
            long length = file.length();
            if (length <= 0 || length > MAX_ROOT_FILE_BYTES) {
                return null;
            }
            return Files.readAllBytes(file.toPath());
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static byte[] readZipEntry(ZipFile zipFile, ZipEntry entry) {
        try {
            long size = entry.getSize();
            if (size > MAX_ROOT_FILE_BYTES) {
                return null;
            }
            try (InputStream input = zipFile.getInputStream(entry)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(size > 0 ? (int) size : 8192);
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_ROOT_FILE_BYTES) {
                        return null;
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static String normalize(String name) {
        String value = name.replace('\\', '/');
        while (value.startsWith("/") || value.startsWith("./")) {
            value = value.startsWith("/") ? value.substring(1) : value.substring(2);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
