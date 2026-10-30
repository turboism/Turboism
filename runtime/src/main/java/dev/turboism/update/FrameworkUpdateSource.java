package dev.turboism.update;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Objects;

/** Trusted endpoint configuration, never derived from discovery URLs. Disabled by default. */
public final class FrameworkUpdateSource {
    private final URI base;

    private FrameworkUpdateSource(final URI base) {
        this.base = base;
    }

    /** No production endpoint contract is deployed; the default cannot issue requests. */
    public static FrameworkUpdateSource disabled() {
        return new FrameworkUpdateSource(null);
    }

    // Package-owned trusted configuration only. No product call site enables this yet.
    static FrameworkUpdateSource configured(final URI base) {
        Objects.requireNonNull(base, "base");
        if (!"https".equals(base.getScheme())
                || !"api.turboism.dev".equals(base.getHost())
                || base.getPort() != -1
                || base.getRawUserInfo() != null
                || base.getRawQuery() != null
                || base.getRawFragment() != null
                || !base.getRawPath().matches("/(?:[a-z0-9-]+/)+")) {
            throw new IllegalArgumentException("Untrusted framework endpoint configuration");
        }
        return new FrameworkUpdateSource(base);
    }

    // Explicit loopback seam cannot be selected by production configuration or feed data.
    static FrameworkUpdateSource loopbackForTest(final URI base) {
        if (!"http".equals(base.getScheme())
                || !"127.0.0.1".equals(base.getHost())
                || base.getPort() <= 0
                || !"/".equals(base.getRawPath())
                || base.getRawUserInfo() != null
                || base.getRawQuery() != null
                || base.getRawFragment() != null) throw new IllegalArgumentException("Not a loopback fixture");
        return new FrameworkUpdateSource(base);
    }

    HttpURLConnection open(final String kind, final String hash) throws IOException {
        if (base == null) throw new IOException("Framework downloads disabled pending endpoint contract");
        if (!(kind.equals("document") || kind.equals("object")) || !hash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid resource identity");
        }
        // Relative names are a local adapter contract, not a claim that production serves these URLs.
        final String relative = kind.equals("document") ? "documents/" + hash : "objects/" + hash + "/payload";
        return (HttpURLConnection) base.resolve(relative).toURL().openConnection();
    }
}
