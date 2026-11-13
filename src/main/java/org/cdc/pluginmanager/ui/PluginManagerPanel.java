package org.cdc.pluginmanager.ui;

import org.cdc.pluginmanager.data.PluginInfo;
import org.cdc.pluginmanager.impl.DefaultThemeColorProvider;
import org.cdc.pluginmanager.interfaces.ThemeColorProvider;
import org.cdc.pluginmanager.utils.ColorUtils;
import org.cdc.pluginmanager.utils.DesktopUtils;
import org.cdc.pluginmanager.utils.L10NHelper;
import org.cdc.pluginmanager.utils.VersionHelper;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 插件管理面板：左侧为可搜索的插件卡片列表，右侧为选中插件的详情。
 *
 * <h3>设计要点</h3>
 * <ul>
 *     <li><b>全局化</b>：所有面向用户的文本都通过 {@link L10NHelper#t(String, Object...)} 获取，
 *         参数使用 {@code %s} 占位符（例如 {@code plugin.manager.count}），不在界面中拼接自然语言。</li>
 *     <li><b>主题自适应</b>：不写死任何颜色，只使用 {@link ThemeColorProvider} 提供的语义色，
 *         因此亮色 / 暗色主题都能直接使用；主题变化后调用 {@link #refreshTheme()} 即可重建界面。</li>
 *     <li><b>只读展示</b>：MCreator 没有提供安装 / 卸载 / 启停插件的 API，因此面板只负责搜索、
 *         浏览和查看详情，不提供任何修改插件状态的操作。</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * PluginManagerPanel panel = new PluginManagerPanel();          // 生产环境：跟随 MCreator 主题
 * panel.setPlugins(PluginConverter.convertAll(plugins));        // 把 MCreator 插件转换为面板数据
 * }</pre>
 */
public class PluginManagerPanel extends JPanel {

    /* ==================== 布局常量 ==================== */

    private static final int DETAIL_WIDTH = 336;
    private static final int DETAIL_TEXT_WIDTH = 270;
    private static final int DETAIL_TAG_WIDTH = 270;
    private static final int CARD_TAG_WIDTH = 340;
    private static final int CARD_TAG_LIMIT = 5;
    /**
     * 面板的首选尺寸。MCreator 的 PreferencesDialog 会用所有 section 的首选尺寸做 pack，
     * 若让首选宽度随插件描述增长，整个设置窗口会被撑到屏幕之外；这里固定为紧凑尺寸，
     * 实际布局仍会填满父容器给出的空间。
     */
    private static final int PREFERRED_PANEL_WIDTH = 1040;
    private static final int PREFERRED_PANEL_HEIGHT = 620;

    /**
     * 按加载权重从高到低（与 MCreator 的插件加载顺序一致），其次按名称。
     */
    private static final Comparator<PluginInfo> PLUGIN_ORDER = Comparator.comparingInt((PluginInfo p) -> p.weight).reversed().thenComparing(p -> nullToEmpty(p.name), String.CASE_INSENSITIVE_ORDER).thenComparing(p -> nullToEmpty(p.id), String.CASE_INSENSITIVE_ORDER);

    /* ==================== 状态 ==================== */

    private ThemeColorProvider themeColors;
    private Palette palette;

    private final List<PluginInfo> plugins = new ArrayList<>();
    private final List<PluginInfo> visiblePlugins = new ArrayList<>();
    private final List<PluginCard> cards = new ArrayList<>();
    private final Map<PluginInfo, PluginCard> cardByPlugin = new IdentityHashMap<>();
    /**
     * 数据或主题变化时置为 true，下次刷新重建卡片；单纯过滤时复用已有卡片。
     */
    private boolean cardsDirty = true;

    private final ListPanel listContainer = new ListPanel();
    private final JPanel detailHost = new JPanel(new BorderLayout());
    private final SearchField searchField = new SearchField();
    private final JLabel countLabel = new JLabel();
    /**
     * 输入搜索词时延迟刷新，避免每敲一个字符就重建全部卡片。
     */
    private final Timer searchDebounce = new Timer(150, e -> refreshList());

    private PluginInfo selectedPlugin;

    /* ==================== 构造 ==================== */

    public PluginManagerPanel() {
        this(new DefaultThemeColorProvider());
    }

    public PluginManagerPanel(ThemeColorProvider themeColors) {
        this.themeColors = themeColors != null ? themeColors : new DefaultThemeColorProvider();
        this.palette = Palette.of(this.themeColors);

        setLayout(new BorderLayout());
        setOpaque(true);
        setBackground(palette.background);

        searchDebounce.setRepeats(false);
        initSearchField();
        installKeyBindings();
        rebuildUi();
        refresh();
    }

    @Override
    public Dimension getPreferredSize() {
        return isPreferredSizeSet() ? super.getPreferredSize() : new Dimension(PREFERRED_PANEL_WIDTH, PREFERRED_PANEL_HEIGHT);
    }

    /* ==================== 公开 API ==================== */

    /**
     * 设置要展示的插件列表（通常由 PluginConverter 转换得到）。相同 ID 的条目会合并。
     */
    public void setPlugins(List<? extends PluginInfo> newPlugins) {
        plugins.clear();
        if (newPlugins != null) {
            Map<String, PluginInfo> byId = new LinkedHashMap<>();
            for (PluginInfo plugin : newPlugins) {
                if (plugin == null) {
                    continue;
                }
                String key = isBlank(plugin.id) ? "@" + System.identityHashCode(plugin) : plugin.id;
                PluginInfo existing = byId.get(key);
                if (existing == null) {
                    byId.put(key, plugin);
                } else {
                    mergePlugin(existing, plugin);
                }
            }
            plugins.addAll(byId.values());
        }
        if (selectedPlugin != null && !plugins.contains(selectedPlugin)) {
            // 重新扫描数据后对象可能被替换，按插件 id 保持选中
            PluginInfo reselected = null;
            for (PluginInfo candidate : plugins) {
                if (selectedPlugin.id != null && selectedPlugin.id.equals(candidate.id)) {
                    reselected = candidate;
                    break;
                }
            }
            selectedPlugin = reselected;
        }
        refresh();
    }

    /**
     * 合并重复条目：保留已有对象，用另一条数据补齐缺失字段。
     */
    private static void mergePlugin(PluginInfo target, PluginInfo other) {
        if (isBlank(target.name)) {
            target.name = other.name;
        }
        if (isBlank(target.version)) {
            target.version = other.version;
        }
        if (isBlank(target.description)) {
            target.description = other.description;
        }
        if (isBlank(target.author)) {
            target.author = other.author;
        }
        if (isBlank(target.javaPlugin)) {
            target.javaPlugin = other.javaPlugin;
        }
        if (isBlank(target.homepageUrl)) {
            target.homepageUrl = other.homepageUrl;
        }
        if (isBlank(target.filePath)) {
            target.filePath = other.filePath;
        }
        if (isBlank(target.loadFailure)) {
            target.loadFailure = other.loadFailure;
        }
        target.builtin |= other.builtin;
        if (target.supportedVersions.isEmpty()) {
            target.supportedVersions.addAll(other.supportedVersions);
        }
        if (target.supportedGeneratorVersions.isEmpty()) {
            target.supportedGeneratorVersions.addAll(other.supportedGeneratorVersions);
        }
        if (target.dependencies.isEmpty()) {
            target.dependencies.addAll(other.dependencies);
        }
    }

    /**
     * 更换主题颜色来源并立即重建界面（主题切换后调用）。
     */
    public void setThemeColorProvider(ThemeColorProvider themeColors) {
        this.themeColors = themeColors != null ? themeColors : new DefaultThemeColorProvider();
        refreshTheme();
    }

    public ThemeColorProvider getThemeColorProvider() {
        return themeColors;
    }

    /**
     * 按当前主题重新读取颜色并重建界面。
     */
    public void refreshTheme() {
        this.palette = Palette.of(themeColors);
        setBackground(palette.background);
        rebuildUi();
        refresh();
    }

    /**
     * 数据变化后刷新列表与详情。
     */
    public void refresh() {
        // 外部可能修改了插件数据，标记卡片需要重建
        cardsDirty = true;
        refreshList();
        updateDetail(selectedPlugin);
    }

    /* ==================== 界面骨架 ==================== */

    private void rebuildUi() {
        removeAll();
        add(buildHeader(), BorderLayout.NORTH);
        add(buildListPane(), BorderLayout.CENTER);
        add(buildDetailPane(), BorderLayout.EAST);
        revalidate();
        repaint();
    }

    /* ==================== 顶部栏 ==================== */

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(18, 0));
        header.setOpaque(true);
        header.setBackground(palette.background);
        header.setBorder(BorderFactory.createCompoundBorder(new MatteBorder(0, 0, 1, 0, palette.line), new EmptyBorder(12, 18, 12, 18)));

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));

        JLabel title = new JLabel(L10NHelper.t("plugin.manager.title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setForeground(palette.text);
        title.setAlignmentX(LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel(L10NHelper.t("plugin.manager.subtitle"));
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 11f));
        subtitle.setForeground(palette.textMuted);
        subtitle.setAlignmentX(LEFT_ALIGNMENT);

        titles.add(title);
        titles.add(Box.createVerticalStrut(3));
        titles.add(subtitle);

        styleSearchField();
        JPanel searchHolder = new JPanel(new GridBagLayout());
        searchHolder.setOpaque(false);
        searchHolder.add(searchField, new GridBagConstraints());

        countLabel.setFont(countLabel.getFont().deriveFont(Font.PLAIN, 11f));
        countLabel.setForeground(palette.textMuted);
        countLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        header.add(titles, BorderLayout.WEST);
        header.add(searchHolder, BorderLayout.CENTER);
        header.add(countLabel, BorderLayout.EAST);
        return header;
    }

    private void initSearchField() {
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleSearchRefresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleSearchRefresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleSearchRefresh();
            }
        });
        searchField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                styleSearchField();
            }

            @Override
            public void focusLost(FocusEvent e) {
                styleSearchField();
                // 输入后立刻点击列表时，先把待执行的过滤刷新掉，避免点到过期结果
                flushSearchRefresh();
            }
        });
        // ESC 只作用于搜索框，避免与对话框自身的 ESC 关闭行为冲突
        searchField.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "pm.clearSearch");
        searchField.getActionMap().put("pm.clearSearch", on(e -> searchField.setText("")));
        // Enter 立即应用当前搜索词，不用等防抖计时器
        searchField.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "pm.flushSearch");
        searchField.getActionMap().put("pm.flushSearch", on(e -> flushSearchRefresh()));
    }

    private void flushSearchRefresh() {
        if (searchDebounce.isRunning()) {
            searchDebounce.stop();
            refreshList();
        }
    }

    /**
     * 搜索框内容变化：清空时立即刷新，输入过程中延迟刷新。
     */
    private void scheduleSearchRefresh() {
        if (searchField.getText().isEmpty()) {
            searchDebounce.stop();
            refreshList();
        } else {
            searchDebounce.restart();
        }
    }

    private void styleSearchField() {
        searchField.setFont(searchField.getFont().deriveFont(Font.PLAIN, 12f));
        searchField.setForeground(palette.text);
        searchField.setCaretColor(palette.text);
        searchField.setBackground(palette.field);
        searchField.setSelectionColor(palette.selection);
        searchField.setSelectedTextColor(palette.text);
        searchField.setMargin(new Insets(0, 0, 0, 0));
        searchField.setPreferredSize(new Dimension(360, 32));
        searchField.setToolTipText(L10NHelper.t("plugin.manager.search.tooltip"));
        searchField.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(searchField.isFocusOwner() ? palette.accent : palette.line, 1, true), new EmptyBorder(6, 10, 6, 10)));
        searchField.repaint();
    }

    /* ==================== 列表 ==================== */

    private JComponent buildListPane() {
        listContainer.removeAll();
        listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
        listContainer.setOpaque(true);
        listContainer.setBackground(palette.background);
        listContainer.setBorder(new EmptyBorder(14, 16, 18, 16));

        JScrollPane scroll = new JScrollPane(listContainer);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(true);
        scroll.getViewport().setBackground(palette.background);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    private void refreshList() {
        searchDebounce.stop();
        if (cardsDirty) {
            rebuildCards();
        }
        listContainer.removeAll();
        visiblePlugins.clear();

        String keyword = searchKeyword();
        for (PluginCard card : cards) {
            if (!keyword.isEmpty() && !matches(card.plugin, keyword)) {
                card.setHover(false);
                continue;
            }
            visiblePlugins.add(card.plugin);
            card.setSelectedState(card.plugin == selectedPlugin);
            card.setHover(false);
            listContainer.add(card);
            listContainer.add(Box.createVerticalStrut(10));
        }

        listContainer.add(Box.createVerticalGlue());
        if (visiblePlugins.isEmpty()) {
            listContainer.add(buildListEmptyState(keyword));
            listContainer.add(Box.createVerticalGlue());
        }

        countLabel.setText(L10NHelper.t("plugin.manager.count", visiblePlugins.size(), plugins.size()));

        listContainer.revalidate();
        listContainer.repaint();
    }

    /**
     * 按当前插件数据重建全部卡片（仅供数据/主题变化时调用）。
     */
    private void rebuildCards() {
        cards.clear();
        cardByPlugin.clear();
        List<PluginInfo> sorted = new ArrayList<>(plugins);
        sorted.sort(PLUGIN_ORDER);
        for (PluginInfo plugin : sorted) {
            PluginCard card = new PluginCard(plugin);
            cards.add(card);
            cardByPlugin.put(plugin, card);
        }
        cardsDirty = false;
    }

    private JComponent buildListEmptyState(String keyword) {
        boolean filtered = !keyword.isEmpty();
        String title = filtered ? L10NHelper.t("plugin.manager.list.noMatch", searchField.getText().trim()) : L10NHelper.t("plugin.manager.list.empty");
        String hint = filtered ? L10NHelper.t("plugin.manager.list.noMatch.hint") : L10NHelper.t("plugin.manager.list.empty.hint");

        ActionButton clear = null;
        if (filtered) {
            clear = new ActionButton(L10NHelper.t("plugin.manager.search.clear"));
            clear.addActionListener(e -> searchField.setText(""));
        }
        return buildEmptyState(title, hint, clear);
    }

    private static boolean matches(PluginInfo plugin, String keyword) {
        return containsIgnoreCase(plugin.name, keyword) || containsIgnoreCase(plugin.author, keyword) || containsIgnoreCase(plugin.description, keyword) || containsIgnoreCase(plugin.id, keyword) || containsIgnoreCase(plugin.javaPlugin, keyword) || containsIgnoreCase(plugin.filePath, keyword) || containsIgnoreCase(plugin.loadFailure, keyword) || containsVersion(plugin.supportedVersions, keyword) || containsIgnoreCase(plugin.supportedGeneratorVersions, keyword);
    }

    private String searchKeyword() {
        String text = searchField.getText();
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsIgnoreCase(String source, String keyword) {
        return source != null && source.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static boolean containsIgnoreCase(List<String> sources, String keyword) {
        if (sources == null) {
            return false;
        }
        for (String source : sources) {
            if (containsIgnoreCase(source, keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsVersion(List<Long> versions, String keyword) {
        if (versions == null) {
            return false;
        }
        for (Long version : versions) {
            if (String.valueOf(version).contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /* ==================== 详情 ==================== */

    private JComponent buildDetailPane() {
        detailHost.removeAll();
        detailHost.setLayout(new BorderLayout());
        detailHost.setOpaque(true);
        detailHost.setBackground(palette.card);
        detailHost.setPreferredSize(new Dimension(DETAIL_WIDTH, 0));
        detailHost.setBorder(new MatteBorder(0, 1, 0, 0, palette.line));
        return detailHost;
    }

    private void updateDetail(PluginInfo plugin) {
        detailHost.removeAll();
        if (plugin == null) {
            detailHost.add(buildEmptyState(L10NHelper.t("plugin.manager.detail.empty"), L10NHelper.t("plugin.manager.detail.empty.hint"), null), BorderLayout.CENTER);
        } else {
            // 用 BorderLayout.NORTH 包一层：既让内容保持自然高度（BoxLayout 不会把各段垂直拉长），
            // 又让内容宽度跟随滚动视口，避免长文本撑破详情栏。
            JPanel view = new JPanel(new BorderLayout());
            view.setOpaque(true);
            view.setBackground(palette.card);
            view.add(buildDetailBody(plugin), BorderLayout.NORTH);

            JScrollPane scroll = new JScrollPane(view);
            scroll.setBorder(null);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(true);
            scroll.getViewport().setBackground(palette.card);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            detailHost.add(scroll, BorderLayout.CENTER);
        }
        detailHost.revalidate();
        detailHost.repaint();
    }

    private JComponent buildDetailBody(PluginInfo plugin) {
        JPanel body = new JPanel();
        body.setOpaque(true);
        body.setBackground(palette.card);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(22, 22, 26, 22));

        JLabel icon = new JLabel(pluginIcon(plugin, 56));
        icon.setAlignmentX(LEFT_ALIGNMENT);
        body.add(icon);
        body.add(Box.createVerticalStrut(14));

        JLabel name = new JLabel(html(safe(plugin.name, "?"), DETAIL_TEXT_WIDTH));
        name.setFont(name.getFont().deriveFont(Font.BOLD, 15f));
        name.setForeground(palette.text);
        name.setAlignmentX(LEFT_ALIGNMENT);
        body.add(name);
        body.add(Box.createVerticalStrut(5));

        JLabel meta = new JLabel(html(detailMeta(plugin), DETAIL_TEXT_WIDTH));
        meta.setFont(meta.getFont().deriveFont(Font.PLAIN, 11f));
        meta.setForeground(palette.textMuted);
        meta.setAlignmentX(LEFT_ALIGNMENT);
        body.add(meta);
        body.add(Box.createVerticalStrut(10));

        List<JLabel> statusBadges = statusBadges(plugin);
        if (!statusBadges.isEmpty()) {
            JPanel badges = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            badges.setOpaque(false);
            badges.setAlignmentX(LEFT_ALIGNMENT);
            for (JLabel statusBadge : statusBadges) {
                badges.add(statusBadge);
            }
            body.add(badges);
            body.add(Box.createVerticalStrut(16));
        }
        JPanel actions = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 8, DETAIL_TEXT_WIDTH));
        actions.setOpaque(false);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        if (!isBlank(plugin.homepageUrl)) {
            ActionButton homepage = button(L10NHelper.t("plugin.manager.button.homepage"), _ -> openHomepage(plugin.homepageUrl));
            homepage.setToolTipText(plugin.homepageUrl);
            actions.add(homepage);
        }
        if (!isBlank(plugin.filePath) && new File(plugin.filePath).exists()) {
            ActionButton openInExplorer = button(L10NHelper.t("plugin.manager.button.openInExplorer"), _ -> DesktopUtils.selectOnlyFile(plugin.filePath));
            openInExplorer.setToolTipText(plugin.filePath);
            actions.add(openInExplorer);
        }
        if (actions.getComponentCount() > 0) {
            body.add(actions);
        }

        body.add(Box.createVerticalStrut(18));
        body.add(separator());
        body.add(Box.createVerticalStrut(14));

        if (!isBlank(plugin.loadFailure)) {
            body.add(sectionTitle(L10NHelper.t("plugin.manager.section.loadFailure")));
            body.add(Box.createVerticalStrut(6));
            JLabel failure = new JLabel(html(plugin.loadFailure, DETAIL_TEXT_WIDTH));
            failure.setFont(failure.getFont().deriveFont(Font.PLAIN, 12f));
            failure.setForeground(palette.errorFg);
            failure.setAlignmentX(LEFT_ALIGNMENT);
            body.add(failure);
            body.add(Box.createVerticalStrut(14));
        }

        body.add(sectionTitle(L10NHelper.t("plugin.manager.section.mcVersions")));
        body.add(Box.createVerticalStrut(6));
        body.add(tagRow(DETAIL_TAG_WIDTH, versionChips(plugin), Integer.MAX_VALUE));
        body.add(Box.createVerticalStrut(14));

        if (plugin.supportedGeneratorVersions != null && !plugin.supportedGeneratorVersions.isEmpty()) {
            body.add(sectionTitle(L10NHelper.t("plugin.manager.section.generatorVersions")));
            body.add(Box.createVerticalStrut(6));
            body.add(tagRow(new ArrayList<>(plugin.supportedGeneratorVersions), DETAIL_TAG_WIDTH, "plugin.manager.tag.generator", false, Integer.MAX_VALUE));
            body.add(Box.createVerticalStrut(14));
        }

        if (plugin.dependencies != null && !plugin.dependencies.isEmpty()) {
            body.add(sectionTitle(L10NHelper.t("plugin.manager.section.dependencies")));
            body.add(Box.createVerticalStrut(6));
            body.add(tagRow(new ArrayList<>(plugin.dependencies), DETAIL_TAG_WIDTH, "plugin.manager.tag.dependency", false, Integer.MAX_VALUE));
            body.add(Box.createVerticalStrut(14));
        }

        body.add(sectionTitle(L10NHelper.t("plugin.manager.section.tech")));
        body.add(Box.createVerticalStrut(6));
        body.add(techRow(L10NHelper.t("plugin.manager.detail.id"), safe(plugin.id, L10NHelper.t("plugin.manager.detail.none")), true));
        body.add(Box.createVerticalStrut(4));
        body.add(techRow(L10NHelper.t("plugin.manager.detail.weight"), String.valueOf(plugin.weight), false));
        body.add(Box.createVerticalStrut(4));
        body.add(techRow(L10NHelper.t("plugin.manager.detail.mainClass"), safe(plugin.javaPlugin, L10NHelper.t("plugin.manager.detail.none")), true));
        if (!isBlank(plugin.filePath)) {
            body.add(Box.createVerticalStrut(4));
            body.add(techRow(L10NHelper.t("plugin.manager.detail.file"), plugin.filePath, true));
        }
        if (!isBlank(plugin.credits)) {
            body.add(Box.createVerticalStrut(4));
            body.add(techRow(L10NHelper.t("plugin.manager.detail.credits"), plugin.credits, false));
        }

        body.add(Box.createVerticalStrut(14));
        body.add(separator());
        body.add(Box.createVerticalStrut(14));

        body.add(sectionTitle(L10NHelper.t("plugin.manager.section.about")));
        body.add(Box.createVerticalStrut(6));
        JLabel description = new JLabel(html(isBlank(plugin.description) ? L10NHelper.t("plugin.manager.detail.none") : plugin.description, DETAIL_TEXT_WIDTH));
        description.setFont(description.getFont().deriveFont(Font.PLAIN, 12f));
        description.setForeground(palette.text);
        description.setAlignmentX(LEFT_ALIGNMENT);
        body.add(description);

        return body;
    }

    private JComponent techRow(String label, String value, boolean monospace) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);

        JLabel key = new JLabel(label);
        key.setFont(key.getFont().deriveFont(Font.PLAIN, 11f));
        key.setForeground(palette.textMuted);
        key.setVerticalAlignment(SwingConstants.TOP);
        key.setPreferredSize(new Dimension(88, key.getPreferredSize().height));

        String content = monospace ? htmlRaw("<code>" + escapeHtml(breakable(value)) + "</code>", DETAIL_TEXT_WIDTH - 98) : html(value, DETAIL_TEXT_WIDTH - 98);
        JLabel contentLabel = new JLabel(content);
        contentLabel.setFont(contentLabel.getFont().deriveFont(Font.PLAIN, 11f));
        contentLabel.setForeground(palette.text);
        contentLabel.setVerticalAlignment(SwingConstants.TOP);
        contentLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                var content = new StringSelection(label + ": " + value);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(content, content);
            }
        });

        row.add(key, BorderLayout.WEST);
        row.add(contentLabel, BorderLayout.CENTER);
        Dimension preferred = row.getPreferredSize();
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
        return row;
    }

    /* ==================== 卡片 ==================== */

    private final class PluginCard extends JPanel {
        private final PluginInfo plugin;
        private boolean hover;
        private boolean selected;

        PluginCard(PluginInfo plugin) {
            this.plugin = plugin;
            setLayout(new BorderLayout(14, 0));
            setOpaque(false);
            setBorder(new EmptyBorder(14, 16, 14, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentX(LEFT_ALIGNMENT);
            setFocusable(true);

            JLabel icon = new JLabel(pluginIcon(plugin, 42));
            icon.setVerticalAlignment(SwingConstants.TOP);
            add(icon, BorderLayout.WEST);

            add(createInfo(plugin), BorderLayout.CENTER);

            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "pm.selectCard");
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "pm.selectCard");
            getActionMap().put("pm.selectCard", on(e -> selectPlugin(this.plugin)));

            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    repaint();
                }
            });

            installCardInteraction(this, this);
        }

        void setSelectedState(boolean selected) {
            this.selected = selected;
            repaint();
        }

        void setHover(boolean hover) {
            if (this.hover != hover) {
                this.hover = hover;
                repaint();
            }
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth() - 1;
            int height = getHeight() - 1;

            Color fill = selected ? palette.selection : (hover ? palette.hover : palette.background);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, width, height, 10, 10);

            g2.setColor(selected ? palette.accent : (hover ? ColorUtils.blend(palette.accent, palette.line, 0.5f) : palette.line));
            g2.drawRoundRect(0, 0, width, height, 10, 10);

            if (isFocusOwner() && width > 8 && height > 8) {
                g2.setColor(palette.accent);
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{3f, 3f}, 0f));
                g2.drawRoundRect(3, 3, width - 6, height - 6, 8, 8);
            }
            g2.dispose();

            super.paintComponent(g);
        }
    }

    /**
     * 卡片副标题：作者 · 版本 · 权重，缺失字段自动省略。
     */
    private String cardMeta(PluginInfo plugin) {
        if (plugin.loadFailure != null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (!isBlank(plugin.author)) {
            parts.add(L10NHelper.t("plugin.manager.card.author", plugin.author));
        }
        if (!isBlank(plugin.version)) {
            parts.add(L10NHelper.t("plugin.manager.card.version", plugin.version));
        }
        parts.add(L10NHelper.t("plugin.manager.card.weight", String.valueOf(plugin.weight)));
        return String.join(" · ", parts);
    }

    /**
     * 详情副标题：作者 · 版本，缺失字段自动省略。
     */
    private String detailMeta(PluginInfo plugin) {
        List<String> parts = new ArrayList<>();
        if (!isBlank(plugin.author)) {
            parts.add(L10NHelper.t("plugin.manager.detail.author", plugin.author));
        }
        if (!isBlank(plugin.version)) {
            parts.add(L10NHelper.t("plugin.manager.detail.version", plugin.version));
        }
        return parts.isEmpty() ? L10NHelper.t("plugin.manager.detail.none") : String.join(" · ", parts);
    }

    private JComponent createInfo(PluginInfo plugin) {
        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));

        // 第一行：名称 + 状态徽章
        Box titleRow = Box.createHorizontalBox();
        titleRow.setOpaque(false);
        titleRow.setAlignmentX(LEFT_ALIGNMENT);

        JLabel name = new EllipsisLabel(safe(plugin.name, "?"));
        name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
        name.setForeground(palette.text);
        name.setToolTipText(safe(plugin.name));
        titleRow.add(name);
        for (JLabel statusBadge : statusBadges(plugin)) {
            titleRow.add(Box.createHorizontalStrut(6));
            titleRow.add(statusBadge);
        }
        titleRow.add(Box.createHorizontalGlue());
        info.add(titleRow);
        info.add(Box.createVerticalStrut(5));

        // 第二行：作者 · 版本 · 权重（缺省字段自动省略）
        JLabel meta = new EllipsisLabel(cardMeta(plugin));
        meta.setFont(meta.getFont().deriveFont(Font.PLAIN, 11f));
        meta.setForeground(palette.textMuted);
        meta.setAlignmentX(LEFT_ALIGNMENT);
        meta.setMaximumSize(new Dimension(Integer.MAX_VALUE, meta.getPreferredSize().height));
        info.add(meta);

        // 第三行：描述（按可用像素省略，完整内容见 tooltip 与右侧详情）
        if (!isBlank(plugin.description)) {
            JLabel description = new EllipsisLabel(plugin.description);
            description.setFont(description.getFont().deriveFont(Font.PLAIN, 11f));
            description.setForeground(palette.textMuted);
            description.setToolTipText(plugin.description);
            description.setAlignmentX(LEFT_ALIGNMENT);
            description.setMaximumSize(new Dimension(Integer.MAX_VALUE, description.getPreferredSize().height));
            info.add(Box.createVerticalStrut(5));
            info.add(description);
        }

        // 第四行：支持的 MCreator 版本（当前版本高亮）+ 生成器版本（中性色）
        List<JLabel> chips = versionChips(plugin);
        if (plugin.supportedGeneratorVersions != null) {
            for (String generator : plugin.supportedGeneratorVersions) {
                chips.add(chip(L10NHelper.t("plugin.manager.tag.generator", generator), false));
            }
        }
        if (!chips.isEmpty()) {
            info.add(Box.createVerticalStrut(7));
            info.add(tagRow(CARD_TAG_WIDTH, chips, CARD_TAG_LIMIT));
        }

        return info;
    }

    /* ==================== 选中与键盘操作 ==================== */

    private void selectPlugin(PluginInfo plugin) {
        PluginCard previous = selectedPlugin == null ? null : cardByPlugin.get(selectedPlugin);
        if (previous != null) {
            previous.setSelectedState(false);
        }
        selectedPlugin = plugin;
        PluginCard current = plugin == null ? null : cardByPlugin.get(plugin);
        if (current != null) {
            current.setSelectedState(true);
        }
        updateDetail(plugin);
    }

    private void moveSelection(int delta) {
        if (visiblePlugins.isEmpty()) {
            return;
        }
        int index = selectedPlugin == null ? -1 : visiblePlugins.indexOf(selectedPlugin);
        int next;
        if (index < 0) {
            next = delta > 0 ? 0 : visiblePlugins.size() - 1;
        } else {
            next = Math.max(0, Math.min(visiblePlugins.size() - 1, index + delta));
        }
        selectVisible(next);
    }

    private void scrollCardIntoView(PluginInfo plugin) {
        PluginCard card = cardByPlugin.get(plugin);
        if (card == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            Rectangle bounds = card.getBounds();
            if (bounds.width <= 0 || bounds.height <= 0) {
                bounds = new Rectangle(card.getX(), card.getY(), listContainer.getWidth(), card.getPreferredSize().height);
            }
            listContainer.scrollRectToVisible(bounds);
        });
    }

    private void installKeyBindings() {
        // ANCESTOR 条件：只有焦点位于面板内部时才生效，不会抢走同一窗口里其它组件的按键
        InputMap input = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actions = getActionMap();

        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "pm.focusSearch");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK), "pm.focusSearch");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "pm.selectNext");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "pm.selectPrevious");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), "pm.selectFirst");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, 0), "pm.selectLast");

        actions.put("pm.focusSearch", on(e -> {
            searchField.requestFocusInWindow();
            searchField.selectAll();
        }));
        actions.put("pm.selectNext", on(_ -> moveSelection(1)));
        actions.put("pm.selectPrevious", on(_ -> moveSelection(-1)));
        actions.put("pm.selectFirst", on(_ -> selectVisible(0)));
        actions.put("pm.selectLast", on(_ -> selectVisible(visiblePlugins.size() - 1)));
    }

    /**
     * 选中列表中指定下标的插件（越界时取最近的合法下标）。
     */
    private void selectVisible(int index) {
        if (visiblePlugins.isEmpty()) {
            return;
        }
        int safe = Math.max(0, Math.min(visiblePlugins.size() - 1, index));
        PluginInfo plugin = visiblePlugins.get(safe);
        selectPlugin(plugin);
        scrollCardIntoView(plugin);
    }

    /**
     * 给卡片及其所有子组件安装悬停 / 点击处理：Swing 的鼠标事件只会派发给最深处组件，
     * 因此必须递归安装，否则点击文字、标签时卡片收不到事件。
     */
    private void installCardInteraction(PluginCard card, Component target) {
        if (target instanceof AbstractButton button) {
            // 按钮自己处理点击，只保留悬停联动
            button.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    card.setHover(true);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    updateHover(card, e);
                }
            });
            return;
        }

        target.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                card.setHover(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                updateHover(card, e);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    card.requestFocusInWindow();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    selectPlugin(card.plugin);
                }
            }
        });

        if (target instanceof Container container) {
            for (Component child : container.getComponents()) {
                installCardInteraction(card, child);
            }
        }
    }

    private void updateHover(PluginCard card, MouseEvent event) {
        Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), card);
        card.setHover(point.x >= 0 && point.y >= 0 && point.x < card.getWidth() && point.y < card.getHeight());
    }

    /* ==================== 打开主页 ==================== */

    private void openHomepage(String url) {
        if (isBlank(url)) {
            return;
        }
        DesktopUtils.browse(url);
    }

    /* ==================== 通用组件 ==================== */

    private ActionButton button(String text, ActionListener listener) {
        ActionButton button = new ActionButton(text);
        button.addActionListener(listener);
        return button;
    }

    /**
     * 插件状态徽章：内置 / 加载失败，按需组合。
     */
    private List<JLabel> statusBadges(PluginInfo plugin) {
        List<JLabel> badges = new ArrayList<>();
        if (plugin.javaPlugin != null) {
            badges.add(badge(L10NHelper.t("plugin.manager.badge.javaplugin"), palette.neutralBg, palette.neutralFg));
        }
        if (plugin.builtin) {
            badges.add(badge(L10NHelper.t("plugin.manager.badge.builtin"), palette.neutralBg, palette.neutralFg));
        }
        if (!isBlank(plugin.loadFailure)) {
            badges.add(badge(L10NHelper.t("plugin.manager.badge.loadFailed"), palette.errorBg, palette.errorFg));
        }
        return badges;
    }

    private JLabel badge(String text, Color background, Color foreground) {
        JLabel label = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(background);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        label.setOpaque(false);
        label.setForeground(foreground);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setBorder(new EmptyBorder(2, 8, 2, 8));
        return label;
    }

    private JComponent tagRow(List<String> values, int maxWidth, String formatKey, boolean accentStyle, int limit) {
        if (values == null || values.isEmpty()) {
            return noneLabel();
        }
        List<JLabel> chips = new ArrayList<>(values.size());
        for (String value : values) {
            chips.add(chip(L10NHelper.t(formatKey, value), accentStyle));
        }
        return tagRow(maxWidth, chips, limit);
    }

    /**
     * 把一组徽章按固定宽度换行排列，超出 limit 的部分折叠为 "+N"。
     */
    private JComponent tagRow(int maxWidth, List<JLabel> chips, int limit) {
        if (chips == null || chips.isEmpty()) {
            return noneLabel();
        }
        JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4, maxWidth));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);

        int shown = Math.min(chips.size(), limit);
        for (int i = 0; i < shown; i++) {
            row.add(chips.get(i));
        }
        if (chips.size() > shown) {
            row.add(badge(L10NHelper.t("plugin.manager.tag.more", chips.size() - shown), palette.neutralBg, palette.neutralFg));
        }
        return row;
    }

    private JLabel chip(String text, boolean accentStyle) {
        return accentStyle ? badge(text, palette.tagBg, palette.tagFg) : badge(text, palette.neutralBg, palette.neutralFg);
    }

    /**
     * 构建"支持的 MCreator 版本"标签，并高亮与当前 MCreator 匹配的版本。
     * 匹配规则与 MCreator 的 {@code Plugin#isCompatible()} 一致：
     * 优先完整版本号，其次非快照版的主版本号。
     */
    private List<JLabel> versionChips(PluginInfo plugin) {
        List<JLabel> chips = new ArrayList<>();
        if (plugin.supportedVersions == null || plugin.supportedVersions.isEmpty()) {
            return chips;
        }
        Long current = VersionHelper.currentSupportedVersion(plugin.supportedVersions);
        for (Long version : plugin.supportedVersions) {
            String text = L10NHelper.t("plugin.manager.tag.mcVersion", String.valueOf(version));
            if (current != null && current.equals(version)) {
                JLabel chip = badge(L10NHelper.t("plugin.manager.tag.currentVersion", text), palette.accent, palette.accentFg);
                chip.setFont(chip.getFont().deriveFont(Font.BOLD));
                chip.setToolTipText(L10NHelper.t("plugin.manager.tag.currentVersion.tooltip"));
                chips.add(chip);
            } else {
                chips.add(chip(text, true));
            }
        }
        return chips;
    }

    private JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        label.setForeground(palette.textMuted);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private JLabel noneLabel() {
        JLabel label = new JLabel(L10NHelper.t("plugin.manager.detail.none"));
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setForeground(palette.textMuted);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private JComponent separator() {
        JPanel line = new JPanel();
        line.setOpaque(true);
        line.setBackground(palette.line);
        line.setAlignmentX(LEFT_ALIGNMENT);
        line.setPreferredSize(new Dimension(0, 1));
        line.setMinimumSize(new Dimension(0, 1));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return line;
    }

    /**
     * 空状态：标题 + 提示 + 可选操作，整体在容器中居中。
     */
    private JComponent buildEmptyState(String titleText, String hintText, JComponent action) {
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        JLabel title = new JLabel(titleText, SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(palette.text);
        title.setAlignmentX(CENTER_ALIGNMENT);
        column.add(title);

        if (hintText != null && !hintText.isEmpty()) {
            JLabel hint = new JLabel(htmlCentered(hintText, 300), SwingConstants.CENTER);
            hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
            hint.setForeground(palette.textMuted);
            hint.setAlignmentX(CENTER_ALIGNMENT);
            column.add(Box.createVerticalStrut(6));
            column.add(hint);
        }

        if (action != null) {
            action.setAlignmentX(CENTER_ALIGNMENT);
            column.add(Box.createVerticalStrut(14));
            column.add(action);
        }

        JPanel holder = new JPanel(new GridBagLayout());
        holder.setOpaque(false);
        holder.setBorder(new EmptyBorder(56, 18, 18, 18));
        holder.setAlignmentX(LEFT_ALIGNMENT);
        holder.add(column);
        holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, holder.getPreferredSize().height));
        return holder;
    }

    /* ==================== 文本工具 ==================== */

    private static String html(String text, int widthPx) {
        return htmlRaw(escapeHtml(nullToEmpty(text)).replace("\n", "<br>"), widthPx);
    }

    private static String htmlRaw(String body, int widthPx) {
        // 注意：Swing 的 HTML/CSS 会把 style 里的 px 当作 pt 放大，必须用 body 的 width 属性
        return "<html><body width='" + widthPx + "'>" + body + "</body></html>";
    }

    private static String htmlCentered(String text, int widthPx) {
        return "<html><body width='" + widthPx + "'><div align='center'>" + escapeHtml(nullToEmpty(text)).replace("\n", "<br>") + "</div></body></html>";
    }

    private static String escapeHtml(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&' -> builder.append("&amp;");
                case '<' -> builder.append("&lt;");
                case '>' -> builder.append("&gt;");
                case '"' -> builder.append("&quot;");
                default -> builder.append(ch);
            }
        }
        return builder.toString();
    }

    /**
     * 在类名 / 路径的分隔符后插入零宽空格，让 Swing 的 HTML 排版可以在此换行，
     * 避免超长的 javaplugin 主类名把详情栏撑破。
     */
    private static String breakable(String text) {
        String value = nullToEmpty(text);
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            builder.append(ch);
            if (ch == '.' || ch == '/' || ch == '_' || ch == '\\' || ch == ':') {
                builder.append('\u200B');
            }
        }
        return builder.toString();
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static String safe(String text, String fallback) {
        return isBlank(text) ? fallback : text;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static AbstractAction on(Consumer<ActionEvent> body) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                body.accept(e);
            }
        };
    }

    /* ==================== 主题配色快照 ==================== */

    /**
     * 一次性把 {@link ThemeColorProvider} 解析成面板实际使用的颜色快照，
     * 避免在每次绘制时反复做颜色混合计算。
     */
    private static final class Palette {
        final Color background;
        final Color card;
        final Color field;
        final Color hover;
        final Color line;
        final Color text;
        final Color textMuted;
        final Color accent;
        final Color accentFg;
        final Color selection;
        final Color neutralBg;
        final Color neutralFg;
        final Color tagBg;
        final Color tagFg;
        final Color errorBg;
        final Color errorFg;
        final Color disabledText;

        private Palette(ThemeColorProvider colors) {
            background = colors.getBackgroundColor();
            card = colors.getAltBackgroundColor();
            field = colors.getInputBackgroundColor();
            hover = colors.getHoverBackgroundColor();
            line = colors.getBorderColor();
            text = colors.getForegroundColor();
            textMuted = colors.getAltForegroundColor();
            accent = colors.getInterfaceAccentColor();
            accentFg = colors.getAccentForegroundColor();
            selection = colors.getSelectionBackgroundColor();
            neutralBg = colors.getNeutralBackgroundColor();
            neutralFg = colors.getNeutralForegroundColor();
            tagBg = colors.getTagBackgroundColor();
            tagFg = colors.getTagColor();
            errorBg = colors.getErrorBackgroundColor();
            errorFg = colors.getErrorColor();
            disabledText = ColorUtils.blend(colors.getAltForegroundColor(), background, 0.55f);
        }

        static Palette of(ThemeColorProvider colors) {
            return new Palette(colors);
        }
    }

    /* ==================== 自定义控件 ==================== */

    /**
     * 带占位提示的搜索框（占位文本同样走 L10NHelper）。
     */
    private final class SearchField extends JTextField {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!getText().isEmpty() || isFocusOwner()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(palette.textMuted);
            g2.setFont(getFont().deriveFont(Font.ITALIC));
            FontMetrics metrics = g2.getFontMetrics();
            int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(L10NHelper.t("plugin.manager.search.placeholder"), getInsets().left, y);
            g2.dispose();
        }
    }

    /**
     * 单行标签：按可用像素宽度省略并加省略号，避免长文本把卡片撑宽。
     */
    private static final class EllipsisLabel extends JLabel {
        EllipsisLabel(String text) {
            super(text);
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            // JLabel 的首选宽度按字体度量取整，个别字形的最后一像素会被裁掉，这里补 6px
            size.width += 6;
            return size;
        }

        @Override
        protected void paintComponent(Graphics g) {
            String text = getText();
            // 宽度已足够（或拿不到文本）时交回默认绘制，避免高 DPI 缩放绘制下的度量误差导致误省略
            if (text == null || text.isEmpty() || getWidth() >= getPreferredSize().width) {
                super.paintComponent(g);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(getFont());
            // 使用组件级 FontMetrics：与布局、首选尺寸处于同一坐标空间
            FontMetrics metrics = getFontMetrics(getFont());
            Insets insets = getInsets();
            int available = Math.max(0, getWidth() - insets.left - insets.right);

            String drawn = text;
            if (metrics.stringWidth(text) > available) {
                int budget = Math.max(0, available - metrics.stringWidth("…"));
                int low = 0;
                int high = text.length();
                while (low < high) {
                    int mid = (low + high + 1) >>> 1;
                    if (metrics.stringWidth(text.substring(0, mid)) <= budget) {
                        low = mid;
                    } else {
                        high = mid - 1;
                    }
                }
                drawn = text.substring(0, low) + "…";
            }

            int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.setColor(getForeground());
            g2.drawString(drawn, insets.left, y);
            g2.dispose();
        }
    }

    private final class ActionButton extends JButton {

        ActionButton(String text) {
            super(text);
            setFont(getFont().deriveFont(Font.PLAIN, 12f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public Color getForeground() {
            return isEnabled() ? palette.text : palette.disabledText;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            size.width = Math.max(size.width, 64);
            return size;
        }
    }

    /**
     * 插件图标：优先使用插件根目录的 icon.png / ico.png，没有时回退到首字母图标。
     */
    private static Icon pluginIcon(PluginInfo plugin, int size) {
        if (plugin.image != null && plugin.image.getIconWidth() > 0 && plugin.image.getIconHeight() > 0) {
            return new ScaledPluginIcon(plugin.image, size);
        }
        return new LetterIcon(plugin.iconText(), plugin.iconColor(), size);
    }

    /**
     * 把插件图标等比缩放到固定尺寸并做圆角裁剪，绘制时不拉伸变形。
     */
    private static final class ScaledPluginIcon implements Icon {
        private final BufferedImage image;
        private final int size;

        ScaledPluginIcon(ImageIcon source, int size) {
            this.size = size;
            this.image = scale(source, size);
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.clip(new RoundRectangle2D.Float(x, y, size, size, size / 4f, size / 4f));
            g2.drawImage(image, x, y, null);
            g2.dispose();
        }

        private static BufferedImage scale(ImageIcon source, int size) {
            BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Image image = source.getImage();
            int sourceWidth = image.getWidth(null);
            int sourceHeight = image.getHeight(null);
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                return target;
            }
            double scale = Math.min((double) size / sourceWidth, (double) size / sourceHeight);
            int width = Math.max(1, (int) Math.round(sourceWidth * scale));
            int height = Math.max(1, (int) Math.round(sourceHeight * scale));
            int x = (size - width) / 2;
            int y = (size - height) / 2;

            Graphics2D g2 = target.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.drawImage(image, x, y, width, height, null);
            g2.dispose();
            return target;
        }
    }

    /**
     * 插件图标：名称首字母 + 按 id 稳定散列得到的底色。
     */
    private record LetterIcon(String text, Color color, int size) implements Icon {

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(x, y, size, size, size / 4, size / 4);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, size / 2)));
            FontMetrics metrics = g2.getFontMetrics();
            int textWidth = metrics.stringWidth(text);
            int textHeight = metrics.getAscent();
            g2.drawString(text, x + (size - textWidth) / 2, y + (size + textHeight) / 2 - 2);
            g2.dispose();
        }
    }

    /**
     * 列表容器：只纵向滚动，宽度始终跟随滚动视口，避免长内容把列表撑到视口之外。
     */
    private static final class ListPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(18, visibleRect.height - 18);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            // 内容不足一屏时撑满视口（空状态因此垂直居中）；内容超长时保持首选高度以允许滚动
            Container parent = getParent();
            return parent instanceof JViewport viewport && getPreferredSize().height < viewport.getHeight();
        }
    }

    /**
     * 支持在固定宽度内自动换行的 FlowLayout，用于版本标签行与详情按钮行。
     */
    private static final class WrapLayout extends FlowLayout {
        private final int maxWidth;

        WrapLayout(int align, int hgap, int vgap, int maxWidth) {
            super(align, hgap, vgap);
            this.maxWidth = maxWidth;
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            return layoutSize(target, false);
        }

        /**
         * 按固定宽度 maxWidth 换行，而不是按容器实际宽度换行，
         * 保证实际布局与 preferredLayoutSize 计算出的高度一致（否则会多出空白或裁切）。
         */
        @Override
        public void layoutContainer(Container target) {
            if (getAlignment() != LEFT) {
                super.layoutContainer(target);
                return;
            }
            synchronized (target.getTreeLock()) {
                Insets insets = target.getInsets();
                int width = maxWidth > 0 ? maxWidth : target.getWidth();
                int available = width - insets.left - insets.right - getHgap() * 2;
                int startX = insets.left + getHgap();
                int x = startX;
                int y = insets.top + getVgap();
                int rowHeight = 0;

                for (int i = 0; i < target.getComponentCount(); i++) {
                    Component component = target.getComponent(i);
                    if (!component.isVisible()) {
                        continue;
                    }
                    Dimension size = component.getPreferredSize();
                    if (x > startX && (x - startX) + size.width > available) {
                        x = startX;
                        y += rowHeight + getVgap();
                        rowHeight = 0;
                    }
                    component.setBounds(x, y, size.width, size.height);
                    x += size.width + getHgap();
                    rowHeight = Math.max(rowHeight, size.height);
                }
            }
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                Insets insets = target.getInsets();
                int availableWidth = maxWidth > 0 ? maxWidth : (target.getParent() != null ? target.getParent().getWidth() : Integer.MAX_VALUE);
                availableWidth -= insets.left + insets.right + getHgap() * 2;

                Dimension dimension = new Dimension(0, 0);
                int rowWidth = 0;
                int rowHeight = 0;

                for (int i = 0; i < target.getComponentCount(); i++) {
                    Component component = target.getComponent(i);
                    if (!component.isVisible()) {
                        continue;
                    }
                    Dimension size = preferred ? component.getPreferredSize() : component.getMinimumSize();
                    if (rowWidth > 0 && rowWidth + getHgap() + size.width > availableWidth) {
                        addRow(dimension, rowWidth, rowHeight);
                        rowWidth = 0;
                        rowHeight = 0;
                    }
                    if (rowWidth > 0) {
                        rowWidth += getHgap();
                    }
                    rowWidth += size.width;
                    rowHeight = Math.max(rowHeight, size.height);
                }
                addRow(dimension, rowWidth, rowHeight);

                dimension.width += insets.left + insets.right + getHgap() * 2;
                dimension.height += insets.top + insets.bottom + getVgap() * 2;
                return dimension;
            }
        }

        private void addRow(Dimension dimension, int rowWidth, int rowHeight) {
            dimension.width = Math.max(dimension.width, rowWidth);
            if (dimension.height > 0) {
                dimension.height += getVgap();
            }
            dimension.height += rowHeight;
        }
    }
}
