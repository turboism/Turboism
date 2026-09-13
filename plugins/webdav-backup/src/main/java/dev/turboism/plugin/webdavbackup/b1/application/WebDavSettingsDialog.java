package dev.turboism.plugin.webdavbackup.b1.application;

import dev.turboism.plugin.webdavbackup.webdav.WebDavConfig;
import dev.turboism.plugin.webdavbackup.webdav.WebDavSyncTarget;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.net.URI;
import java.util.Objects;

/**
 * Swing settings dialog for the WebDAV backup endpoint (opened on the EDT).
 * Fields: enabled / url / username / password (password box) / remotePath /
 * verifyTls / retryMax / retryBaseDelayMs / timeoutSeconds / remoteTrigger
 * (SAVE_TRIGGERED default, AUTO_BACKUP_SYNC alternative).
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

    /** Placeholder shown in the password box when a password is already stored. */
    static final String PASSWORD_PLACEHOLDER = "********";

    /** Echo char used by the password box while masked. */
    static final char PASSWORD_ECHO = '*';

    /** Localization key for the dialog title. */
    static final String DIALOG_TITLE_KEY = "backup.dialog.title";

    /** English fallback title, used when the catalog has no entry for the key. */
    static final String TITLE_FALLBACK = "WebDAV Backup Settings";

    /** Localized text for {@code key}; falls back to the English literal when absent. */
    static String text(final PluginLocalization localization, final String key, final String fallback) {
        return localization != null && localization.contains(key) ? localization.text(key) : fallback;
    }

    /** Localized {@link java.text.MessageFormat} text for {@code key}, with an English fallback pattern. */
    static String format(
        final PluginLocalization localization,
        final String key,
        final String fallback,
        final Object... arguments
    ) {
        return localization != null && localization.contains(key)
            ? localization.format(key, arguments)
            : java.text.MessageFormat.format(fallback, arguments);
    }

    private WebDavSettingsDialog() {
    }

    /**
     * Opens the dialog on the EDT (headless-safe no-op). {@code onSaved}
     * receives the exact assembled config after a successful save so the plugin
     * can build its sync target deterministically.
     */
    public static void open(
        final PluginContext context,
        final WebDavSettingsBinding binding,
        final java.util.function.Consumer<WebDavConfig> onSaved
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
     * empty or placeholder-only password keeps the current password (the password box
     * shows {@link #PASSWORD_PLACEHOLDER} when a password is stored). Throws {@link IllegalArgumentException} with a user-facing
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
        final int timeoutSeconds,
        final WebDavConfig.RemoteTrigger remoteTrigger
    ) {
        final String resolvedPassword = isUnchangedPassword(password)
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
            timeoutSeconds,
            remoteTrigger
        );
    }

    /** True when the password box carries no new value (empty or placeholder). */
    static boolean isUnchangedPassword(final char[] password) {
        return password == null || password.length == 0
            || PASSWORD_PLACEHOLDER.equals(new String(password));
    }

    /** Initial password-box text: the placeholder when a password is stored, else empty. */
    static String initialPasswordText(final WebDavConfig config) {
        return config != null && config.password() != null && !config.password().isEmpty()
            ? PASSWORD_PLACEHOLDER
            : "";
    }

    /** Eye-toggle echo char: plain (0) when masked, masked when plain. */
    static char toggleEchoChar(final char current) {
        return current == PASSWORD_ECHO ? 0 : PASSWORD_ECHO;
    }
    /** Localized combo label for a trigger mode; falls back to the enum name. */
    static String remoteTriggerText(final PluginLocalization localization, final WebDavConfig.RemoteTrigger trigger) {
        final String key = switch (trigger) {
            case SAVE_TRIGGERED -> "backup.remote-trigger.save-triggered";
            case AUTO_BACKUP_SYNC -> "backup.remote-trigger.auto-backup-sync";
        };
        return localization != null && localization.contains(key)
            ? localization.text(key)
            : trigger.name();
    }

    /** Localized dialog row label for the trigger selector. */
    static String remoteTriggerLabel(final PluginLocalization localization) {
        final String key = "backup.dialog.remote-trigger-label";
        return localization != null && localization.contains(key)
            ? localization.text(key)
            : "Remote trigger";
    }

    private static void show(
        final PluginContext context,
        final WebDavSettingsBinding binding,
        final java.util.function.Consumer<WebDavConfig> onSaved
    ) {
        final PluginLogger logger = context.logger();
        final PluginLocalization localization = context.localization();
        final Window owner = null; // modeless top-level dialog; the host owns the frame hierarchy
        final JDialog dialog = TurboismWindowFactory.dialog(
            null, text(localization, DIALOG_TITLE_KEY, TITLE_FALLBACK), false);
        final JCheckBox enabled = new JCheckBox(
            text(localization, "backup.dialog.enabled-checkbox", "Enable sync"));
        final JTextField url = new JTextField(WebDavSettingsBinding.DEFAULT_URL, 32);
        final JTextField username = new JTextField(24);
        final JPasswordField password = new JPasswordField(24);
        final JTextField remotePath = new JTextField(WebDavSettingsBinding.DEFAULT_REMOTE_PATH, 32);
        final JCheckBox verifyTls = new JCheckBox(
            text(localization, "backup.dialog.verify-tls-checkbox", "Verify TLS certificate"));
        final JSpinner retryMax = new JSpinner(new SpinnerNumberModel(
            WebDavSettingsBinding.DEFAULT_RETRY_MAX, 0, 10, 1));
        final JSpinner retryBaseDelayMs = new JSpinner(new SpinnerNumberModel(
            (int) WebDavSettingsBinding.DEFAULT_RETRY_BASE_DELAY_MS, 0, 60_000, 100));
        final JSpinner timeoutSeconds = new JSpinner(new SpinnerNumberModel(
            WebDavSettingsBinding.DEFAULT_TIMEOUT_SECONDS, 1, 300, 1));
        final JComboBox<WebDavConfig.RemoteTrigger> remoteTrigger = new JComboBox<>(
            WebDavConfig.RemoteTrigger.values());
        remoteTrigger.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                final JList<?> list,
                final Object value,
                final int index,
                final boolean isSelected,
                final boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof WebDavConfig.RemoteTrigger trigger) {
                    setText(remoteTriggerText(localization, trigger));
                }
                return this;
            }
        });
        final JLabel status = new JLabel(" ");
        final JButton save = new JButton(text(localization, "backup.dialog.button.save", "Save"));
        final JButton cancel = new JButton(text(localization, "backup.dialog.button.cancel", "Cancel"));
        final JButton test = new JButton(
            text(localization, "backup.dialog.button.test", "Test connection"));

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
            (Integer) timeoutSeconds.getValue(),
            (WebDavConfig.RemoteTrigger) remoteTrigger.getSelectedItem()
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
            remoteTrigger.setSelectedItem(config.remoteTrigger());
            password.setToolTipText(text(localization, "backup.dialog.password-tooltip",
                "Leave empty or keep the placeholder to reuse the stored password"));
            final boolean cached = config.password() != null && !config.password().isEmpty();
            if (cached) {
                password.setText(initialPasswordText(config));
                password.addFocusListener(new FocusAdapter() {
                    @Override
                    public void focusGained(final FocusEvent ignored) {
                        if (isUnchangedPassword(password.getPassword())) {
                            password.setText("");
                        }
                    }

                    @Override
                    public void focusLost(final FocusEvent ignored) {
                        if (password.getPassword().length == 0) {
                            password.setText(PASSWORD_PLACEHOLDER);
                        }
                    }
                });
            }
        });

        final JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        addRow(form, c, row++, new JLabel(
            text(localization, "backup.dialog.enabled-label", "Enabled")), enabled);
        addRow(form, c, row++, new JLabel("URL"), url); // technical token: identical in every catalog
        addRow(form, c, row++, new JLabel(
            text(localization, "backup.dialog.username-label", "Username")), username);
        final JButton eye = new JButton(text(localization, "backup.dialog.password-show", "Show"));
        eye.setToolTipText(text(localization, "backup.dialog.password-toggle-tooltip",
            "Show or hide the password"));
        eye.setMargin(new Insets(0, 6, 0, 6));
        eye.addActionListener(ignored -> {
            password.setEchoChar(toggleEchoChar(password.getEchoChar()));
            eye.setText(password.getEchoChar() == 0
                ? text(localization, "backup.dialog.password-hide", "Hide")
                : text(localization, "backup.dialog.password-show", "Show"));
        });
        addRowWithTrailing(form, c, row++, new JLabel(
            text(localization, "backup.dialog.password-label", "Password")), password, eye);
        addRow(form, c, row++, new JLabel(
            text(localization, "backup.dialog.remote-path-label", "Remote path")), remotePath);
        addRow(form, c, row++, new JLabel("TLS"), verifyTls); // technical token: identical in every catalog
        addRow(form, c, row++, new JLabel(
            text(localization, "backup.dialog.retry-max-label", "Retry attempts")), retryMax);
        addRow(form, c, row++, new JLabel(
            text(localization, "backup.dialog.retry-base-delay-label", "Retry base delay (ms)")),
            retryBaseDelayMs);
        addRow(form, c, row++, new JLabel(
            text(localization, "backup.dialog.timeout-label", "Timeout (seconds)")), timeoutSeconds);
        addRow(form, c, row++, new JLabel(remoteTriggerLabel(localization)), remoteTrigger);
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
            status.setText(text(localization, "backup.dialog.status.testing", "Testing…"));
            final WebDavConfig probe;
            try {
                probe = formConfig.get();
            } catch (IllegalArgumentException invalid) {
                status.setText(format(localization, "backup.dialog.status.invalid-config",
                    "Invalid configuration: {0}", invalid.getMessage()));
                return;
            }
            final Thread worker = new Thread(() -> {
                try {
                    new WebDavSyncTarget(probe, reason -> logger.warn("webdav-verify " + reason))
                        .verify();
                    SwingUtilities.invokeLater(() -> status.setText(
                        text(localization, "backup.dialog.status.connected", "Connected")));
                } catch (RuntimeException | Error failure) {
                    final String message = sanitized(failure);
                    SwingUtilities.invokeLater(() -> status.setText(format(localization,
                        "backup.dialog.status.connection-failed", "Connection failed: {0}", message)));
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
                status.setText(format(localization, "backup.dialog.status.invalid-config",
                    "Invalid configuration: {0}", invalid.getMessage()));
                return;
            }
            binding.update(target).whenCompleteAsync((result, failure) -> {
                final String message = failure != null
                    ? saveFailure(localization, null, failure.getClass().getSimpleName())
                    : switch (result) {
                        case APPLIED, UNCHANGED -> null;
                        case DISABLED -> saveFailure(localization, "backup.dialog.error.binding-disabled",
                            "the binding is not enabled");
                        case PARTIAL_PERSISTENCE -> saveFailure(localization,
                            "backup.dialog.error.partial-persistence",
                            "the write was not confirmed by a readback");
                        case REVISION_CONFLICT -> saveFailure(localization,
                            "backup.dialog.error.revision-conflict",
                            "the configuration revision changed; retry");
                        case PERMISSION_DENIED -> saveFailure(localization,
                            "backup.dialog.error.permission-denied",
                            "configuration write permission is missing");
                        case INVALID_VALUE -> saveFailure(localization, "backup.dialog.error.invalid-value",
                            "the value is invalid");
                        default -> saveFailure(localization, "backup.dialog.error.runtime-unavailable",
                            "the configuration service is unavailable");
                    };
                if (message != null) {
                    status.setText(message);
                    return;
                }
                try {
                    onSaved.accept(target);
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

    private static void addRowWithTrailing(
        final JPanel form,
        final GridBagConstraints c,
        final int row,
        final JLabel label,
        final java.awt.Component field,
        final java.awt.Component trailing
    ) {
        addRow(form, c, row, label, field);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        form.add(trailing, c);
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

    /**
     * Renders "Save failed: <reason>" through the catalog. A null {@code reasonKey} uses the
     * fallback text as the reason (used for the exception class name of an unexpected failure).
     */
    private static String saveFailure(
        final PluginLocalization localization,
        final String reasonKey,
        final String reasonFallback
    ) {
        final String reason = reasonKey == null
            ? reasonFallback
            : text(localization, reasonKey, reasonFallback);
        return format(localization, "backup.dialog.status.save-failed", "Save failed: {0}", reason);
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
