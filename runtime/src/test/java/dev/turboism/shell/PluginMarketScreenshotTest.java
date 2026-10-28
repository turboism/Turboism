package dev.turboism.shell;

import dev.turboism.internal.core.CorePluginManagement.PluginInfo;
import dev.turboism.pluginmanagement.PendingPluginOperations;
import dev.turboism.pluginmanagement.PluginCatalog;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogArtifact;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogLocalization;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPermission;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPlugin;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import dev.turboism.pluginmanagement.PluginCatalogClient;
import dev.turboism.pluginmanagement.PluginCatalogStateStore;
import dev.turboism.pluginmanagement.PluginCatalogVerifier;
import dev.turboism.pluginmanagement.PluginMarketService;
import dev.turboism.pluginmanagement.RuntimePluginManagementService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsTab;
import dev.turboism.ui.settings.SettingsContributionStore;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginMarketScreenshotTest {

    @TempDir
    Path home;

    @Test
    void captureMarketAndSettingsScreenshots() throws Exception {
        try {
            final List<PluginInfo> installed = List.of(
                    new PluginInfo(
                            "dev.turboism.plugin.mcp",
                            "Turboism MCP",
                            "0.67.0",
                            "Model Context Protocol integration",
                            "ENABLED",
                            "ENABLED",
                            false,
                            Optional.empty(),
                            "integration",
                            List.of("mcp", "agent")),
                    new PluginInfo(
                            "dev.turboism.plugin.acp",
                            "Turboism ACP",
                            "0.66.0",
                            "Agent Client Protocol",
                            "ENABLED",
                            "ENABLED",
                            false,
                            Optional.empty(),
                            "integration",
                            List.of("acp", "agent")));

            final RuntimePluginManagementService mgmt =
                    new RuntimePluginManagementService(home, Optional::empty, () -> installed);

            final PluginMarketService marketService = new PluginMarketService(
                    home,
                    new PluginCatalogClient(),
                    new PluginCatalogVerifier(),
                    new PluginCatalogStateStore(home),
                    new PendingPluginOperations(home),
                    mgmt,
                    () -> Optional.of("5.3.04"),
                    dev.turboism.core.FrameworkBuildInfo.current().apiVersion(),
                    ignored -> {});

            final CatalogPlugin mcpPlugin =
                    createPlugin("dev.turboism.plugin.mcp", "mcp", "Turboism MCP", "Turboism MCP 插件", "0.67.0", true);
            final CatalogPlugin acpPlugin =
                    createPlugin("dev.turboism.plugin.acp", "acp", "Turboism ACP", "Turboism ACP 插件", "0.67.0", true);
            final CatalogPlugin thirdPartyPlugin = createPlugin(
                    "com.community.magicdeformer",
                    "magic-deformer",
                    "Magic Deformer Pro",
                    "智能变形器自动布局工具",
                    "1.2.0",
                    false);

            final PluginCatalog catalog = new PluginCatalog(
                    PluginCatalog.FORMAT,
                    2,
                    1L,
                    Instant.now(),
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    "test-key",
                    List.of(mcpPlugin, acpPlugin, thirdPartyPlugin));

            final PluginLocalization i18n = stubLocalization();
            final PluginMarketWindow marketWindow =
                    new PluginMarketWindow(i18n, marketService, mgmt, Locale.SIMPLIFIED_CHINESE);

            final JDialog marketDialog = (JDialog) invokeMethod(marketWindow, "createDialog");
            if (marketDialog == null) {
                return;
            }
            marketDialog.pack();
            marketDialog.setSize(1050, 680);

            final Object tableModel = getField(marketWindow, "tableModel");
            tableModel
                    .getClass()
                    .getDeclaredMethod("setSnapshot", PluginMarketService.MarketSnapshot.class)
                    .invoke(
                            tableModel,
                            marketService
                                    .snapshot(catalog)
                                    .toCompletableFuture()
                                    .get(5, java.util.concurrent.TimeUnit.SECONDS));
            invokeMethod(marketWindow, "applyFilters");
            invokeMethod(marketWindow, "updateDetailPane", mcpPlugin);

            final javax.swing.JTable table = (javax.swing.JTable) getField(marketWindow, "table");
            final javax.swing.JLabel statsLabel = (javax.swing.JLabel) getField(marketWindow, "pluginStatsLabel");
            org.junit.jupiter.api.Assertions.assertEquals(3, table.getRowCount());
            org.junit.jupiter.api.Assertions.assertEquals("市场插件总数：3 | 当前本地安装：2", statsLabel.getText());

            // Test QuickStatusFilter: INSTALLED
            final Class<?> filterEnum = Class.forName("dev.turboism.shell.PluginMarketWindow$QuickStatusFilter");
            final Object filterInstalled = Enum.valueOf((Class<Enum>) filterEnum, "INSTALLED");
            final Object filterNotInstalled = Enum.valueOf((Class<Enum>) filterEnum, "NOT_INSTALLED");
            final Object filterAll = Enum.valueOf((Class<Enum>) filterEnum, "ALL");

            setField(marketWindow, "quickFilter", filterInstalled);
            invokeMethod(marketWindow, "applyFilters");
            org.junit.jupiter.api.Assertions.assertEquals(2, table.getRowCount());
            org.junit.jupiter.api.Assertions.assertEquals("市场插件总数：3（显示 2）| 当前本地安装：2", statsLabel.getText());

            // Test QuickStatusFilter: NOT_INSTALLED
            setField(marketWindow, "quickFilter", filterNotInstalled);
            invokeMethod(marketWindow, "applyFilters");
            org.junit.jupiter.api.Assertions.assertEquals(1, table.getRowCount());
            org.junit.jupiter.api.Assertions.assertEquals("市场插件总数：3（显示 1）| 当前本地安装：2", statsLabel.getText());

            // Reset back to ALL
            setField(marketWindow, "quickFilter", filterAll);
            invokeMethod(marketWindow, "applyFilters");
            org.junit.jupiter.api.Assertions.assertEquals(3, table.getRowCount());
            org.junit.jupiter.api.Assertions.assertEquals("市场插件总数：3 | 当前本地安装：2", statsLabel.getText());

            marketDialog.validate();
            marketDialog.repaint();

            final File buildDir = new File("build");
            buildDir.mkdirs();

            final BufferedImage marketImage = new BufferedImage(1050, 680, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g2m = marketImage.createGraphics();
            javax.swing.SwingUtilities.invokeAndWait(
                    () -> marketDialog.getRootPane().printAll(g2m));
            g2m.dispose();
            org.junit.jupiter.api.Assertions.assertNotEquals(
                    0, marketImage.getRGB(30, 30) >>> 24, "The hidden dialog capture must contain rendered pixels");
            ImageIO.write(marketImage, "PNG", new File(buildDir, "plugin-market.png"));
            final File rootBuildDir = new File("../build");
            if (rootBuildDir.exists()) {
                ImageIO.write(marketImage, "PNG", new File(rootBuildDir, "plugin-market.png"));
            }

            // Capture Plugin Settings Window screenshot
            final SettingsContributionStore settingsStore = new SettingsContributionStore();
            settingsStore.register(
                    "dev.turboism.plugin.mcp",
                    new SettingsContribution(
                            "mcp.port",
                            new SettingsTab("mcp", "MCP Configuration"),
                            new SettingsControl.Toggle(
                                    "mcp.enabled", "Enable MCP Server", SettingsBinding.of(() -> true, v -> {}))));
            settingsStore.register(
                    "dev.turboism.plugin.acp",
                    new SettingsContribution(
                            "acp.endpoint",
                            new SettingsTab("acp", "ACP Agent Setup"),
                            new SettingsControl.Text(
                                    "acp.host",
                                    "Agent Command / URL",
                                    30,
                                    SettingsBinding.of(() -> "http://127.0.0.1:8000", v -> {}))));

            final PluginSettingsWindow settingsWindow =
                    new PluginSettingsWindow(i18n, settingsStore, mgmt, (dlg, panel, row, control) -> () -> true);

            final JDialog settingsDialog =
                    (JDialog) invokeMethod(settingsWindow, "createDialog", Optional.of("dev.turboism.plugin.mcp"));
            if (settingsDialog == null) {
                return;
            }
            settingsDialog.pack();
            settingsDialog.setSize(840, 560);
            settingsDialog.validate();
            settingsDialog.repaint();

            final BufferedImage settingsImage = new BufferedImage(840, 560, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g2s = settingsImage.createGraphics();
            javax.swing.SwingUtilities.invokeAndWait(
                    () -> settingsDialog.getRootPane().printAll(g2s));
            g2s.dispose();
            ImageIO.write(settingsImage, "PNG", new File(buildDir, "plugin-settings.png"));
            if (rootBuildDir.exists()) {
                ImageIO.write(settingsImage, "PNG", new File(rootBuildDir, "plugin-settings.png"));
            }
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                marketDialog.dispose();
                settingsDialog.dispose();
            });
            marketService.close();
            mgmt.close();

        } catch (HeadlessException e) {
            // In pure headless environments without display, screenshot capture is safely skipped.
        }
    }

    private static Object invokeMethod(Object target, String name, Object... args) throws Exception {
        for (var m : target.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Object getField(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static CatalogPlugin createPlugin(
            String id, String slug, String name, String zhName, String version, boolean official) {
        return new CatalogPlugin(
                id,
                slug,
                name,
                "Summary description for " + name,
                official ? "official" : "reviewed-third-party",
                official ? "Turboism Contributors" : "Third Party Author",
                "MIT",
                "https://github.com/turboism/" + slug,
                "https://github.com/turboism/" + slug + "/issues",
                Map.of("zh-Hans", new CatalogLocalization(zhName, zhName + " 说明摘要")),
                List.of(new CatalogRelease(
                        version,
                        "stable",
                        "active",
                        Instant.now(),
                        "integration",
                        List.of("plugin", "cubism"),
                        "[0.67.0,1.0.0)",
                        true,
                        List.of("5.3.04"),
                        List.of("windows-x64"),
                        List.of(),
                        List.of(new CatalogPermission("turboism.file.read", "application", "读取文件权限")),
                        "https://turboism.dev/plugins/" + slug,
                        Optional.empty(),
                        new CatalogArtifact(
                                "application/java-archive",
                                slug + ".jar",
                                "https://github.com/turboism/" + slug,
                                "0000000000000000000000000000000000000000000000000000000000000000",
                                "0000000000000000000000000000000000000000000000000000000000000000",
                                1024L),
                        true)));
    }

    private static PluginLocalization stubLocalization() {
        return new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.SIMPLIFIED_CHINESE;
            }

            @Override
            public String text(String key) {
                return switch (key) {
                    case "market.title" -> "插件市场";
                    case "market.search" -> "搜索插件…";
                    case "market.filter.category" -> "分类:";
                    case "market.filter.tag" -> "标签:";
                    case "market.filter.author" -> "作者:";
                    case "market.filter.state" -> "状态:";
                    case "market.state.all" -> "全部";
                    case "market.state.installed" -> "已安装";
                    case "market.state.update-available" -> "有可用更新";
                    case "market.state.incompatible" -> "不兼容";
                    case "market.state.not-installed" -> "未安装";
                    case "market.official" -> "官方";
                    case "market.third-party" -> "第三方";
                    case "market.hot-reload.supported" -> "热更新: 支持";
                    case "market.hot-reload.restart-required" -> "热更新: 需重启";
                    case "market.action.install" -> "安装";
                    case "market.action.update" -> "更新";
                    case "market.action.uninstall" -> "卸载";
                    case "market.action.enable" -> "启用";
                    case "market.action.disable" -> "禁用";
                    case "market.action.open-browser" -> "在浏览器中打开";
                    case "plugins.details.author" -> "作者";
                    case "plugins.details.license" -> "许可证";
                    case "plugins.details.tags" -> "标签";
                    case "plugins.details.api" -> "Turboism API";
                    case "plugins.details.state" -> "状态";
                    case "plugin-settings.title" -> "插件设置";
                    case "plugin-settings.no-plugins" -> "当前没有插件提供设置项。";
                    case "market.filter.quick.all" -> "全部";
                    case "market.filter.quick.installed" -> "已安装";
                    case "market.filter.quick.not-installed" -> "未安装";
                    case "common.close" -> "关闭";
                    case "common.ok" -> "确定";
                    case "common.cancel" -> "取消";
                    case "common.apply" -> "应用";
                    case "common.refresh" -> "刷新";
                    default -> key;
                };
            }

            @Override
            public String format(String key, Object... arguments) {
                if ("market.stats".equals(key) && arguments.length >= 2) {
                    return "市场插件总数：" + arguments[0] + " | 当前本地安装：" + arguments[1];
                }
                if ("market.stats.filtered".equals(key) && arguments.length >= 3) {
                    return "市场插件总数：" + arguments[0] + "（显示 " + arguments[2] + "）| 当前本地安装：" + arguments[1];
                }
                if ("market.count".equals(key) && arguments.length >= 1) {
                    return "市场插件：" + arguments[0] + " 个";
                }
                return text(key);
            }

            @Override
            public boolean contains(String key) {
                return true;
            }
        };
    }
}
