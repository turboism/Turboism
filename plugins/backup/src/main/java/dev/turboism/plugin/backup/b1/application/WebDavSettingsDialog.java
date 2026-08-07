package dev.turboism.plugin.backup.b1.application;

import dev.turboism.plugin.backup.webdav.WebDavConfig;
import dev.turboism.plugin.backup.webdav.WebDavSyncTarget;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.net.URI;
import java.util.Objects;

/**
 * Swing settings dialog for the WebDAV backup endpoint (opened on the EDT).
 * Fields: enabled / url / username / password (password box) / remotePath /
 * verifyTls / retryMax / retryBaseDelayMs / timeoutSeconds.
 *
 * <p>Save persists through {@link WebDavSettingsBinding#update(WebDavConfig)}
 * (write path with readback confirmation); Cancel changes nothing; Test
 * Connection probes the endpoint through {@link WebDavSyncTarget#verify()}
 * against the current form values and shows the result inline. The password
 * is only ever held in the password box and the config store — never logged.
 * The form value assembly and validation live in static methods so they are
 * testable without a display.</p>
 */
public final class WebDavSettingsDialog {

    private WebDavSettingsDialog() {
    }

    /**
     * Opens the dialog on the EDT (headless-safe no-op). {@code onSaved} runs
     * after a successful save so the plugin can rebuild its sync target.
     */
    public static void open(
        final PluginContext context,
        final WebDavSettingsBinding binding,
        final Runnable onSaved
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(binding, "binding");
        if (GraphicsEnvironment.isHeadless()) {
            context.logger().warn("WebDAV settings dialog cannot open in a headless JVM");
            return;
        }
        SwingUtilities.invokeLater(() -> show(context, binding, onSaved));
    }

    /**
     * Assembles a validated {@link WebDavConfig} from raw form values. An
     * empty password keeps the current password (the password box never
     * pre-fills). Throws {@link IllegalArgumentException} with a user-facing
     * message when any value is invalid (url scheme/userinfo, remotePath
     * normalization, retry/timeout ranges).
     */
    public static WebDavConfig assemble(
        final WebDavConfig current,
        final boolean enabled,
        final String url,
        final String username,
        final char[] password,
        final String remotePath,
        final boolean verifyTls,
        final int retryMax,
        final long retryBaseDelayMs,
        final int timeoutSeconds
    ) {
        final String resolvedPassword = password == null || password.length == 0
            ? current == null ? "" : current.password()
            : new String(password);
        return new WebDavConfig(
            enabled,
            URI.create(url == null ? "" : url.trim()),
            username == null ? "" : username.trim(),
            resolvedPassword,
            remotePath == null ? "" : remotePath.trim(),
            verifyTls,
            retryMax,
            retryBaseDelayMs,
            timeoutSeconds
        );
    }

    private static void show(
        final PluginContext context,
        final WebDavSettingsBinding binding,
        final Runnable onSaved
    ) {
        final PluginLogger logger = context.logger();
        final Window owner = null; // modeless top-level dialog; the host owns the frame hierarchy
        final JDialog dialog = new JDialog();
        dialog.setTitle("WebDAV 备份设置");
        dialog.setModal(false);
        final JCheckBox enabled = new JCheckBox("启用同步");
        final JTextField url = new JTextField(WebDavSettingsBinding.DEFAULT_URL, 32);
        final JTextField username = new JTextField(24);
        final JPasswordField password = new JPasswordField(24);
        final JTextField remotePath = new JTextField(WebDavSettingsBinding.DEFAULT_REMOTE_PATH, 32);
        final JCheckBox verifyTls = new JCheckBox("验证 TLS 证书");
        final JSpinner retryMax = new JSpinner(new SpinnerNumberModel(
            WebDavSettingsBinding.DEFAULT_RETRY_MAX, 0, 10, 1));
        final JSpinner retryBaseDelayMs = new JSpinner(new SpinnerNumberModel(
            (int) WebDavSettingsBinding.DEFAULT_RETRY_BASE_DELAY_MS, 0, 60_000, 100));
        final JSpinner timeoutSeconds = new JSpinner(new SpinnerNumberModel(
            WebDavSettingsBinding.DEFAULT_TIMEOUT_SECONDS, 1, 300, 1));
        final JLabel status = new JLabel(" ");
        final JButton save = new JButton("保存");
        final JButton cancel = new JButton("取消");
        final JButton test = new JButton("测试连接");

        final java.util.function.Supplier<WebDavConfig> formConfig = () -> assemble(
            binding.confirmed(), // empty password keeps the stored one
            enabled.isSelected(),
            url.getText(),
            username.getText(),
            password.getPassword(),
            remotePath.getText(),
            verifyTls.isSelected(),
            (Integer) retryMax.getValue(),
            (Integer) retryBaseDelayMs.getValue(),
            (Integer) timeoutSeconds.getValue()
        );

        binding.read().whenComplete((config, failure) -> {
            if (config == null) {
                logger.warn("WebDAV settings dialog: current config unavailable; defaults shown");
                return;
            }
            enabled.setSelected(config.enabled());
            url.setText(config.url().toString());
            username.setText(config.username());
            remotePath.setText(config.remotePath());
            verifyTls.setSelected(config.verifyTls());
            retryMax.setValue(config.retryMax());
            retryBaseDelayMs.setValue((int) config.retryBaseDelayMs());
            timeoutSeconds.setValue(config.timeoutSeconds());
            password.setToolTipText("留空则保持已保存的密码");
        });

        final JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        addRow(form, c, row++, new JLabel("启用"), enabled);
        addRow(form, c, row++, new JLabel("URL"), url);
        addRow(form, c, row++, new JLabel("用户名"), username);
        addRow(form, c, row++, new JLabel("密码"), password);
        addRow(form, c, row++, new JLabel("远程路径"), remotePath);
        addRow(form, c, row++, new JLabel("TLS"), verifyTls);
        addRow(form, c, row++, new JLabel("重试次数"), retryMax);
        addRow(form, c, row++, new JLabel("重试基延迟(ms)"), retryBaseDelayMs);
        addRow(form, c, row++, new JLabel("超时(秒)"), timeoutSeconds);
        c.gridx = 0;
        c.gridwidth = 2;
        c.gridy = row;
        form.add(status, c);

        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(test);
        buttons.add(save);
        buttons.add(cancel);

        final JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);

        test.addActionListener(ignored -> {
            status.setText("测试中…");
            final WebDavConfig probe;
            try {
                probe = formConfig.get();
            } catch (IllegalArgumentException invalid) {
                status.setText("配置无效: " + invalid.getMessage());
                return;
            }
            final Thread worker = new Thread(() -> {
                try {
                    new WebDavSyncTarget(probe, reason -> logger.warn("webdav-verify " + reason))
                        .verify();
                    SwingUtilities.invokeLater(() -> status.setText("连接成功"));
                } catch (RuntimeException | Error failure) {
                    final String message = sanitized(failure);
                    SwingUtilities.invokeLater(() -> status.setText("连接失败: " + message));
                }
            }, "turboism-webdav-verify");
            worker.setDaemon(true);
            worker.start();
        });

        save.addActionListener(ignored -> {
            final WebDavConfig target;
            try {
                target = formConfig.get();
            } catch (IllegalArgumentException invalid) {
                status.setText("配置无效: " + invalid.getMessage());
                return;
            }
            binding.update(target).whenCompleteAsync((result, failure) -> {
                final String message = failure != null
                    ? "保存失败: " + failure.getClass().getSimpleName()
                    : switch (result) {
                        case APPLIED, UNCHANGED -> null;
                        case DISABLED -> "保存失败: 绑定未启用";
                        case PARTIAL_PERSISTENCE -> "保存失败: 写入未获读回确认";
                        case REVISION_CONFLICT -> "保存失败: 配置版本冲突，请重试";
                        case PERMISSION_DENIED -> "保存失败: 缺少配置写入权限";
                        case INVALID_VALUE -> "保存失败: 值非法";
                        default -> "保存失败: 配置服务不可用";
                    };
                if (message != null) {
                    status.setText(message);
                    return;
                }
                try {
                    onSaved.run();
                } catch (RuntimeException | Error saveFailure) {
                    logger.warn("webdav settings saved but target rebuild failed: "
                        + saveFailure.getClass().getSimpleName());
                }
                dialog.dispose();
            }, SwingUtilities::invokeLater);
        });

        cancel.addActionListener(ignored -> dialog.dispose());

        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setVisible(true);
    }

    private static void addRow(
        final JPanel form,
        final GridBagConstraints c,
        final int row,
        final JLabel label,
        final java.awt.Component field
    ) {
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);
        c.fill = GridBagConstraints.NONE;
    }

    private static String sanitized(final Throwable failure) {
        // verify() already emits sanitized messages (status codes / method
        // names, never credentials); keep the message short for the inline label.
        final String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : message.length() > 160 ? message.substring(0, 160) : message;
    }
}
