package dev.turboism.plugin.turboismwithfx;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Closed manifest of exact fx v0.0.5 executables distributed by Turboism. */
final class FxRuntimeManifest {

    static final String VERSION = FxAcpClient.SUPPORTED_FX_VERSION;
    static final String SOURCE_COMMIT = "df7e6245e1992758d4060c97477ceafa27770551";
    static final LegalFile LICENSE = new LegalFile(
        "LICENSE",
        10_764L,
        "f1932dabb4856eb6f6cba683be864338e654012492642aeb59e5e48483f836c4"
    );
    static final LegalFile THIRD_PARTY_NOTICES = new LegalFile(
        "THIRD_PARTY_NOTICES.md",
        3_402L,
        "e0b9786cae8e238ea08eeb078807d21910789276a9f440d7675c149dfa133437"
    );
    private static final String RELEASE_ASSET_ROOT =
        "/github-production-release-asset/1330702515/";
    private static final Map<String, Entry> ENTRIES = entries();

    private FxRuntimeManifest() {
    }

    static Optional<Entry> entry(final FxRuntimePlatform platform) {
        Objects.requireNonNull(platform, "platform");
        return Optional.ofNullable(ENTRIES.get(platform.id()));
    }

    static Map<String, Entry> allEntries() {
        return ENTRIES;
    }

    private static Map<String, Entry> entries() {
        final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        add(entries, Entry.upstreamArchive(
            "linux-x86_64",
            "27a5e9474fd749d6ca2503ab93765176a93ffbd0f0e7173e8f2e3e4c6b51876f",
            11_870_712L,
            "fx-linux-x86_64.tar.gz",
            "d5639d173267774aa8228a474baf619a7076ac41a91023915007c865143429b1",
            5_133_392L,
            RELEASE_ASSET_ROOT + "268f7872-098f-462c-a154-76f644e6ec3d",
            "Vercel upstream release asset"
        ));
        add(entries, Entry.upstreamArchive(
            "linux-aarch64",
            "35e972dc8be31b736a0d7fd733157f9d77a6a46dee33e0172ee51cd27915577d",
            10_133_856L,
            "fx-linux-aarch64.tar.gz",
            "8bbcde6a41256c4fac4e0a022291cf02740419e27afabde3b8f45e7a4e393edb",
            5_053_942L,
            RELEASE_ASSET_ROOT + "78f55715-c95f-4501-a52f-bb6bfa0fa978",
            "Vercel upstream release asset"
        ));
        add(entries, Entry.upstreamArchive(
            "macos-x86_64",
            "3170e25c2238b73971d992936b482d058282cb19d7beb34098e808d71c244428",
            12_307_081L,
            "fx-macos-x86_64.tar.gz",
            "0da4a90034c1afcd251a1a2cb237ea3a0013c965ad8c2a45b7713694b530ad8a",
            5_461_498L,
            RELEASE_ASSET_ROOT + "e22f68e7-7a92-461b-85a2-96c04f2bf3bd",
            "Vercel upstream release asset"
        ));
        add(entries, Entry.upstreamArchive(
            "macos-aarch64",
            "caad628680cd2af24d79063f109965b71c24f69c7b06318b50178c76cc40d0c9",
            6_431_792L,
            "fx-macos-aarch64.tar.gz",
            "2b98cc1a85c1cf5ea213f1df71cca79f7cbff65793d2a87282c04ca019cbd1c1",
            3_933_429L,
            RELEASE_ASSET_ROOT + "2c411afe-992e-4a18-b2d8-c8f2a2b837e2",
            "Vercel upstream release asset"
        ));
        add(entries, Entry.productPayload(
            "windows-x86_64",
            "a36b0b209d933e4757d7e1a961d259d39a8d370b68cbde8e9cba227603ac63c2",
            11_144_192L,
            "Turboism build of upstream fx v0.0.5"
        ));
        return Map.copyOf(entries);
    }

    private static void add(final Map<String, Entry> entries, final Entry entry) {
        if (entries.put(entry.platformId(), entry) != null) {
            throw new IllegalStateException("duplicate managed fx platform " + entry.platformId());
        }
    }

    enum Delivery {
        UPSTREAM_ARCHIVE("upstream-archive"),
        PRODUCT_PAYLOAD("product-payload");

        private final String manifestValue;

        Delivery(final String manifestValue) {
            this.manifestValue = manifestValue;
        }

        String manifestValue() {
            return manifestValue;
        }
    }

    record Entry(
        String platformId,
        String executableSha256,
        long executableSize,
        Delivery delivery,
        String archiveName,
        String archiveSha256,
        long archiveSize,
        String releaseAssetPath,
        String provenance
    ) {
        Entry {
            platformId = requireText(platformId, "platformId");
            executableSha256 = requireSha256(executableSha256, "executableSha256");
            if (executableSize <= 0L) {
                throw new IllegalArgumentException("executableSize must be positive");
            }
            delivery = Objects.requireNonNull(delivery, "delivery");
            provenance = requireText(provenance, "provenance");
            if (delivery == Delivery.UPSTREAM_ARCHIVE) {
                archiveName = requireText(archiveName, "archiveName");
                if (!archiveName.matches("fx-(?:linux|macos)-(?:x86_64|aarch64)\\.tar\\.gz")) {
                    throw new IllegalArgumentException("archiveName is invalid");
                }
                archiveSha256 = requireSha256(archiveSha256, "archiveSha256");
                if (archiveSize <= 0L) {
                    throw new IllegalArgumentException("archiveSize must be positive");
                }
                releaseAssetPath = requireText(releaseAssetPath, "releaseAssetPath");
                if (!releaseAssetPath.matches(
                    "/github-production-release-asset/1330702515/[0-9a-f-]{36}"
                )) {
                    throw new IllegalArgumentException("releaseAssetPath is invalid");
                }
            } else if (archiveName != null || archiveSha256 != null || archiveSize != 0L
                || releaseAssetPath != null) {
                throw new IllegalArgumentException("product payload cannot declare an archive");
            }
        }

        static Entry upstreamArchive(
            final String platformId,
            final String executableSha256,
            final long executableSize,
            final String archiveName,
            final String archiveSha256,
            final long archiveSize,
            final String releaseAssetPath,
            final String provenance
        ) {
            return new Entry(
                platformId, executableSha256, executableSize, Delivery.UPSTREAM_ARCHIVE,
                archiveName, archiveSha256, archiveSize, releaseAssetPath, provenance
            );
        }

        static Entry productPayload(
            final String platformId,
            final String executableSha256,
            final long executableSize,
            final String provenance
        ) {
            return new Entry(
                platformId, executableSha256, executableSize, Delivery.PRODUCT_PAYLOAD,
                null, null, 0L, null, provenance
            );
        }

        Optional<URI> sourceUri() {
            if (delivery != Delivery.UPSTREAM_ARCHIVE) return Optional.empty();
            return Optional.of(URI.create(
                "https://github.com/vercel-labs/fx/releases/download/v"
                    + VERSION + "/" + archiveName
            ));
        }
    }

    record LegalFile(String name, long size, String sha256) {
        LegalFile {
            name = requireText(name, "name");
            if (size <= 0L) throw new IllegalArgumentException("size must be positive");
            sha256 = requireSha256(sha256, "sha256");
        }
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name).strip();
        if (text.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return text;
    }

    private static String requireSha256(final String value, final String name) {
        final String digest = requireText(value, name);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return digest;
    }
}
