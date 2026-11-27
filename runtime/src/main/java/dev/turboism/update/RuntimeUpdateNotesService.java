package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.internal.core.GuidePreferences;
import dev.turboism.internal.core.UpdateNotesService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runtime-owned tracking and note resolution for post-update release announcements.
 *
 * <p>Persists the last acknowledged framework version under {@code state/runtime/update-notes.json}.
 * Fresh installations initialize the baseline quietly alongside onboarding so the update card only
 * opens after subsequent upgrades; existing installations without a recorded baseline open once for
 * the current version.</p>
 */
public final class RuntimeUpdateNotesService implements UpdateNotesService {

    static final String RELATIVE_PATH = "state/runtime/update-notes.json";
    static final String FORMAT = "turboism.update-notes/v1";
    static final int SCHEMA_VERSION = 1;

    private static final Set<String> ALLOWED_FIELDS = Set.of("format", "schemaVersion", "lastSeenVersion");
    private static final String DIAGNOSTIC_CORRUPT = "UPDATE_NOTES_STATE_CORRUPT";
    private static final String DIAGNOSTIC_WRITE_FAILED = "UPDATE_NOTES_STATE_WRITE_FAILED";
    private static final String DIAGNOSTIC_GUIDE_UNAVAILABLE = "UPDATE_NOTES_GUIDE_UNAVAILABLE";

    private final Path home;
    private final Path path;
    private final Object lock;
    private final GuidePreferences guide;
    private final Supplier<FrameworkBuildInfo> buildInfo;
    private final BundledReleaseNotes notesLoader;
    private final Consumer<String> diagnostic;

    /** Creates the update-notes service for {@code home} using the current packaged build identity. */
    public RuntimeUpdateNotesService(final Path home, final GuidePreferences guide, final Consumer<String> diagnostic) {
        this(home, guide, FrameworkBuildInfo::current, new BundledReleaseNotes(), diagnostic);
    }

    RuntimeUpdateNotesService(
            final Path home,
            final GuidePreferences guide,
            final Supplier<FrameworkBuildInfo> buildInfo,
            final BundledReleaseNotes notesLoader,
            final Consumer<String> diagnostic) {
        this.home = UpdateFileSupport.normalizeHome(home);
        this.path = this.home.resolve(RELATIVE_PATH).normalize();
        if (!this.path.startsWith(this.home)) {
            throw new IllegalArgumentException("update-notes path escaped home");
        }
        this.lock = UpdateFileSupport.lockFor(this.path);
        this.guide = Objects.requireNonNull(guide, "guide");
        this.buildInfo = Objects.requireNonNull(buildInfo, "buildInfo");
        this.notesLoader = Objects.requireNonNull(notesLoader, "notesLoader");
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    @Override
    public Optional<ReleaseNotes> pendingReleaseNotes() {
        final FrameworkBuildInfo current = buildInfo.get();
        if (current == null
                || current.channel() == FrameworkBuildInfo.Channel.UNKNOWN
                || !UpdateVersionOrdering.isValid(current.version())) {
            return Optional.empty();
        }
        final String currentVersion = current.version();
        final Optional<String> previous;
        synchronized (lock) {
            final Optional<String> stored = readLastSeenVersionLocked(true);
            if (stored.isEmpty()) {
                if (isGuideOnboardingPending()) {
                    writeLastSeenVersionLocked(currentVersion);
                    return Optional.empty();
                }
                previous = Optional.empty();
            } else {
                final String lastSeen = stored.orElseThrow();
                if (UpdateVersionOrdering.compare(currentVersion, lastSeen) <= 0) {
                    return Optional.empty();
                }
                previous = Optional.of(lastSeen);
            }
        }
        final Map<String, String> localized = notesLoader.load(home, current, previous);
        return Optional.of(new ReleaseNotes(currentVersion, previous, localized));
    }

    @Override
    public void markReleaseNotesSeen(final String version) {
        if (!UpdateVersionOrdering.isValid(version)) {
            throw new IllegalArgumentException("invalid acknowledged version: " + version);
        }
        synchronized (lock) {
            final Optional<String> stored = readLastSeenVersionLocked(false);
            if (stored.isPresent() && stored.orElseThrow().equals(version)) {
                return;
            }
            writeLastSeenVersionLocked(version);
        }
    }

    private boolean isGuideOnboardingPending() {
        try {
            return guide.shouldShowGuide();
        } catch (RuntimeException unavailable) {
            UpdateFileSupport.report(diagnostic, DIAGNOSTIC_GUIDE_UNAVAILABLE);
            return true;
        }
    }

    private Optional<String> readLastSeenVersionLocked(final boolean reportCorruption) {
        try {
            final ObjectNode root = UpdateFileSupport.readObject(home, path);
            if (root == null) {
                return Optional.empty();
            }
            return Optional.of(parseLastSeenVersion(root));
        } catch (IOException | RuntimeException failure) {
            if (reportCorruption) {
                UpdateFileSupport.report(diagnostic, DIAGNOSTIC_CORRUPT);
            }
            return Optional.empty();
        }
    }

    private static String parseLastSeenVersion(final ObjectNode root) throws IOException {
        for (final Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            if (!ALLOWED_FIELDS.contains(names.next())) {
                throw new IOException("unknown update-notes field");
            }
        }
        final JsonNode format = root.get("format");
        final JsonNode schemaVersion = root.get("schemaVersion");
        final JsonNode lastSeen = root.get("lastSeenVersion");
        if (format == null
                || !FORMAT.equals(format.textValue())
                || schemaVersion == null
                || !schemaVersion.isIntegralNumber()
                || !schemaVersion.canConvertToInt()
                || schemaVersion.intValue() != SCHEMA_VERSION
                || lastSeen == null
                || !lastSeen.isTextual()
                || !UpdateVersionOrdering.isValid(lastSeen.textValue())) {
            throw new IOException("invalid update-notes state");
        }
        return lastSeen.textValue();
    }

    private void preserveUnreadableLocked() {
        try {
            final ObjectNode root = UpdateFileSupport.readObject(home, path);
            if (root == null) {
                return;
            }
            parseLastSeenVersion(root);
        } catch (IOException | RuntimeException unreadable) {
            UpdateFileSupport.preserveUnreadable(home, path);
        }
    }

    private void writeLastSeenVersionLocked(final String version) {
        final ObjectNode root = UpdateFileSupport.JSON.createObjectNode();
        root.put("format", FORMAT);
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("lastSeenVersion", version);
        try {
            preserveUnreadableLocked();
            UpdateFileSupport.writeAtomic(home, path, root);
        } catch (IOException | RuntimeException failure) {
            UpdateFileSupport.report(diagnostic, DIAGNOSTIC_WRITE_FAILED);
        }
    }
}
