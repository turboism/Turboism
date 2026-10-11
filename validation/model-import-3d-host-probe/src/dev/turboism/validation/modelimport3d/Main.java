package dev.turboism.validation.modelimport3d;

import dev.turboism.validation.modelimport3d.Model.Fixture;
import dev.turboism.validation.modelimport3d.Report.BuildInfo;
import dev.turboism.validation.modelimport3d.Report.Check;
import dev.turboism.validation.modelimport3d.Report.Status;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline entrypoint. Usage:
 *   Main --out <fresh child of this build's run> [--fault <name>]
 * Writes material.png then report.json under <dir> (both CREATE_NEW — existing
 * leaves or symlinked path components are refused). Exit codes:
 *   0 = all offline checks as expected   2 = at least one FAIL
 *   3 = usage / io / path-safety error
 * Build fingerprint (HEAD, dirty hash, class hashes, java version, argv) is
 * collected by this process; caller-supplied identity strings are not trusted.
 * Never starts or connects to a host; native entries stay NOT_RUN.
 */
final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Path out = null;
        String fault = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--out" -> out = Path.of(require(args, ++i, "--out"));
                    case "--fault" -> fault = require(args, ++i, "--fault");
                    default -> {
                        System.err.println("unknown argument: " + args[i]);
                        return 3;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 3;
        }
        if (out == null) {
            System.err.println(
                    "usage: Main --out <fresh child of this build's run> [--fault <name>]");
            return 3;
        }
        if (fault != null && !Fixtures.FAULTS.contains(fault)) {
            System.err.println("unknown fault: " + fault + " (known: "
                    + Fixtures.FAULTS + ")");
            return 3;
        }

        // Class-output location fixes ownership; CLI cannot enlarge the root.
        Path orig;
        try {
            orig = OutputPaths.freshOutput(out);
        } catch (IOException e) {
            System.err.println(e.getMessage());
            return 3;
        }
        Path report = orig.resolve("report.json");
        Path material = orig.resolve("material.png");

        Fixture f;
        try {
            f = Fixtures.fixture(fault);
        } catch (Model.FixtureRejected e) {
            System.err.println("fixture construction rejected: " + e.reason
                    + " " + e.getMessage());
            return 3;
        }

        List<String> samples = new ArrayList<>();
        List<Check> offline = Checks.offline(f, samples);
        List<Check> rejections = Checks.rejections(f);
        List<Check> natives = Checks.nativeNotRun();
        List<Check> blocked = Checks.seamBlocked();

        byte[] png = Png.pngBytes(Png.generate());
        String materialSha = Report.sha256(png);
        BuildInfo build;
        try {
            build = BuildProvenance.collect(args, orig);
        } catch (IOException e) {
            System.err.println("refused: build provenance: " + e.getMessage());
            return 3;
        }

        String json = Report.toJson(offline, rejections, natives, blocked,
                f.scope(), Checks.domainDigests(f), samples, fault, build,
                materialSha);
        try {
            Files.createDirectory(OutputPaths.freshOutput(orig));
            // material first: a failed report write never leaves a stale PASS
            Files.write(material, png,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            Report.write(report, json);
        } catch (FileAlreadyExistsException e) {
            System.err.println("refused: output leaf already exists: "
                    + e.getMessage());
            return 3;
        } catch (IOException | UncheckedIOException e) {
            System.err.println("io error: " + e.getMessage());
            return 3;
        }

        long fail = java.util.stream.Stream.concat(offline.stream(),
                        rejections.stream())
                .filter(c -> c.status() == Status.FAIL).count();
        long pass = java.util.stream.Stream.concat(offline.stream(),
                        rejections.stream())
                .filter(c -> c.status() == Status.PASS).count();
        System.out.println("offline checks: pass=" + pass + " fail=" + fail
                + " native NOT_RUN=" + natives.size()
                + " seam BLOCKED=" + blocked.size());
        System.out.println("report: " + report);
        System.out.println("material: " + material);
        return fail == 0 ? 0 : 2;
    }

    private static String require(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return args[i];
    }

}
