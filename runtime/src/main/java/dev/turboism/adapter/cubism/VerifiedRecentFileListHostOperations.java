package dev.turboism.adapter.cubism;

import dev.turboism.mapping.verification.RecentPreviewVerificationManifest;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import java.awt.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

/**
 * Recent Files menu projection over verified host roots: current project merged ahead
 * of the menu entries and deduplicated. Ids are SHA-256 hex of the normalized absolute
 * path, so they stay stable across sessions and never leak the path itself.
 */
public final class VerifiedRecentFileListHostOperations implements RecentFileAdapter.HostOperations {

    private final VerifiedMemberResolver projectResolver;
    private final VerifiedMemberResolver panelResolver;
    private final Consumer<String> diagnostics;
    private final Map<RecentFileId, Path> paths = new LinkedHashMap<>();

    public VerifiedRecentFileListHostOperations(
            final VerifiedMemberResolver projectResolver, final VerifiedMemberResolver panelResolver) {
        this(projectResolver, panelResolver, ignored -> {});
    }

    public VerifiedRecentFileListHostOperations(
            final VerifiedMemberResolver projectResolver,
            final VerifiedMemberResolver panelResolver,
            final Consumer<String> diagnostics) {
        this.projectResolver = Objects.requireNonNull(projectResolver, "projectResolver");
        this.panelResolver = Objects.requireNonNull(panelResolver, "panelResolver");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        RecentPreviewVerificationManifest.requireAuthorized(projectResolver, panelResolver);
        RecentMenuChain.PROJECT_ALIASES.forEach(projectResolver::verifiedSelector);
        RecentMenuChain.PANEL_ALIASES.forEach(panelResolver::verifiedSelector);
    }

    @Override
    public synchronized List<RecentFileSummary> list() {
        final List<Path> recentPaths;
        try {
            recentPaths = RecentMenuChain.recentPaths(panelResolver, RecentMenuChain.resolveWindow(panelResolver));
        } catch (RuntimeException ignored) {
            paths.clear();
            return List.of();
        }
        paths.clear();
        Path current = null;
        try {
            current = RecentMenuChain.currentProjectPath(projectResolver);
        } catch (RuntimeException ignored) {
            // current stays null; the list still reflects the recent menu (fail closed).
        }
        final LinkedHashMap<String, Path> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.put(RecentMenuChain.pathKey(current), current);
        }
        for (Path path : recentPaths) {
            merged.putIfAbsent(RecentMenuChain.pathKey(path), path);
        }
        final List<RecentFileSummary> summaries = new ArrayList<>(merged.size());
        for (Path path : merged.values()) {
            final RecentFileId id = idFor(path);
            paths.putIfAbsent(id, path);
            if (paths.get(id).equals(path)) {
                summaries.add(new RecentFileSummary(
                        id, path.getFileName().toString(), lastModified(path), Optional.of(path.toString())));
            }
        }
        return List.copyOf(summaries);
    }

    @Override
    public synchronized Optional<RecentFileId> current() {
        try {
            final Path current = RecentMenuChain.currentProjectPath(projectResolver);
            return current == null ? Optional.empty() : Optional.of(idFor(current));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public boolean open(final RecentFileId id) {
        Objects.requireNonNull(id, "id");
        if (SwingUtilities.isEventDispatchThread()) {
            return openOnEdt(id);
        }
        final boolean[] opened = {false};
        try {
            SwingUtilities.invokeAndWait(() -> opened[0] = openOnEdt(id));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.lang.reflect.InvocationTargetException | RuntimeException failure) {
            return false;
        }
        return opened[0];
    }

    private synchronized boolean openOnEdt(final RecentFileId id) {
        Path target = paths.get(id);
        if (target == null) {
            // The id may have entered the host menu since the last list(); re-resolve once.
            list();
            target = paths.get(id);
        }
        if (target == null) {
            return false;
        }
        final JMenu recent;
        try {
            recent = RecentMenuChain.recentMenu(panelResolver, RecentMenuChain.resolveWindow(panelResolver));
        } catch (RuntimeException ignored) {
            return false;
        }
        if (recent == null) {
            return false;
        }
        final String targetKey = RecentMenuChain.pathKey(target);
        for (Component component : recent.getMenuComponents()) {
            if (!(component instanceof JMenuItem item) || !item.isEnabled()) continue;
            final Path path =
                    RecentMenuChain.firstExistingPath(item.getActionCommand(), item.getToolTipText(), item.getText());
            if (path == null || !RecentMenuChain.pathKey(path).equals(targetKey)) continue;
            try {
                item.doClick(0);
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    /** Resolved path for an id from the last {@link #list()} call; used by the capture pipeline. */
    public synchronized Path pathFor(final RecentFileId id) {
        return paths.get(Objects.requireNonNull(id, "id"));
    }

    /** The verified project resolver; shared with the capture and popup pipelines. */
    public VerifiedMemberResolver projectResolver() {
        return projectResolver;
    }

    private static Optional<Instant> lastModified(final Path path) {
        try {
            final long millis = Files.getLastModifiedTime(path).toMillis();
            return Optional.of(Instant.ofEpochMilli(millis));
        } catch (Exception unavailable) {
            return Optional.empty();
        }
    }

    /** Opaque, cross-session-stable id: lowercase SHA-256 hex of the normalized absolute path. */
    public static RecentFileId idFor(final Path path) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(RecentMenuChain.pathKey(path).getBytes(StandardCharsets.UTF_8));
            final StringBuilder value = new StringBuilder(64);
            for (byte item : digest) value.append(String.format(Locale.ROOT, "%02x", item));
            return new RecentFileId(value.toString());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
