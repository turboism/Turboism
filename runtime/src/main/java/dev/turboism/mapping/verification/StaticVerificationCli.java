package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** CLI and programmatic entrypoint for exact-version static host verification. */
public final class StaticVerificationCli {

    private final StaticVerificationRecordLoader loader;
    private final StaticSelectorVerifier verifier;

    public StaticVerificationCli() {
        this(new StaticVerificationRecordLoader(), new StaticSelectorVerifier());
    }

    StaticVerificationCli(
        final StaticVerificationRecordLoader loader,
        final StaticSelectorVerifier verifier
    ) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public StaticVerificationReport verify(final Path recordPath, final Path artifactPath) throws IOException {
        final StaticVerificationRecord record = loader.load(recordPath).record();
        return verifier.verify(artifactPath, record.artifact(), record.selectors());
    }

    static StaticVerificationRecord loadRecord(final Path recordPath) throws IOException {
        return new StaticVerificationRecordLoader().load(recordPath).record();
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: StaticVerificationCli <verification-record.json> <host-artifact.jar>");
            System.exit(2);
        }
        final StaticVerificationReport report = new StaticVerificationCli().verify(
            Path.of(args[0]),
            Path.of(args[1])
        );
        for (StaticSelectorResult result : report.results()) {
            System.out.println(result.alias() + "\t" + result.status() + "\t" + result.message());
        }
        if (!report.allSelectorsVerified()) {
            System.exit(1);
        }
    }
}
