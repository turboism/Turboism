package dev.turboism.mapping.draft;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Local command-line entry point for the draft mapping update review workflow.
 *
 * <p>Apply is always a dry run unless {@code --write} is supplied explicitly.
 * Generated {@code diff.json} files are presentation only and are never read by apply.</p>
 */
public final class MappingReviewCli {
    private MappingReviewCli() { }

    /**
     * Runs the CLI and terminates the JVM with the resulting status code.
     *
     * <p>Exit codes: {@code 0} on success or {@code --help}, {@code 1} for a pipeline failure
     * (printed as {@code mapping-review: CODE: message} on standard error), {@code 2} for a usage
     * error or no arguments at all. This method never returns normally.
     *
     * @param args {@code generate} or {@code apply} followed by that command's options; note that
     *     {@code apply} is a dry run unless {@code --write} is passed
     */
    public static void main(final String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(final String[] args, final PrintStream output, final PrintStream errors) {
        try {
            if (args.length == 0 || hasHelp(args)) {
                usage(output);
                return args.length == 0 ? 2 : 0;
            }
            final String command = args[0];
            final Arguments options = Arguments.parse(args, 1);
            options.requireAllowed(command);
            final Path root = Path.of(options.optional("root", Path.of(System.getProperty("user.dir")).toString()));
            final MappingReviewService service = new MappingReviewService(root, JarScanPolicy.defaults(), AtomicMover.system());
            return switch (command) {
                case "generate" -> generate(options, service, root.toAbsolutePath().normalize(), output);
                case "apply" -> apply(options, service, output);
                default -> throw new IllegalArgumentException("unknown command: " + command);
            };
        } catch (DraftMappingException exception) {
            errors.println("mapping-review: " + exception.code() + ": " + exception.getMessage());
            return 1;
        } catch (IllegalArgumentException exception) {
            errors.println("mapping-review: " + exception.getMessage());
            usage(errors);
            return 2;
        }
    }

    private static int generate(
        final Arguments options,
        final MappingReviewService service,
        final Path root,
        final PrintStream output
    ) {
        options.rejectWrite();
        final GeneratedBundle bundle = service.generate(new GenerateRequest(
            Path.of(options.required("artifact")),
            options.required("pack"),
            options.required("semantic-name"),
            options.required("expected-old-runtime"),
            options.required("caller-owner"),
            options.required("caller-name"),
            options.required("caller-descriptor"),
            options.required("target-method-name"),
            options.required("target-method-descriptor"),
            InvocationConstraint.valueOf(options.optional("invocation", "ANY")),
            options.optionalPath("output"),
            worktreeId(options)
        ));
        output.println("candidate=" + relativeOutput(root, bundle.candidatePath()));
        output.println("review=" + relativeOutput(root, bundle.reviewPath()));
        output.println("diff=" + relativeOutput(root, bundle.diffPath()));
        output.println("diagnostic=" + relativeOutput(root, bundle.diagnosticPath()));
        output.println("mode=GENERATED");
        return 0;
    }

    private static int apply(final Arguments options, final MappingReviewService service, final PrintStream output) {
        final ApplyResult result = service.apply(new ApplyRequest(
            Path.of(options.required("candidate")),
            Path.of(options.required("review")),
            Path.of(options.required("artifact")),
            options.write()
        ));
        output.println("mode=" + (result.written() ? "WRITTEN" : "DRY_RUN"));
        output.println("resultPackSha256=" + result.resultPackSha256());
        return 0;
    }

    private static String relativeOutput(final Path root, final Path output) {
        final Path normalized = output.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new DraftMappingException("OUTPUT_PATH_INVALID", "generated output escaped the worktree root");
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    private static String worktreeId(final Arguments options) {
        final String configured = options.optional("worktree-id", System.getProperty("turboism.worktree.id", ""));
        if (configured.isBlank()) {
            throw new DraftMappingException("WORKTREE_ID_MISSING", "a validated worktree ID is required for generated output");
        }
        if (!Pattern.matches("[a-z][a-z0-9-]{2,63}", configured)) {
            throw new DraftMappingException("WORKTREE_ID_INVALID", "worktree ID must match [a-z][a-z0-9-]{2,63}");
        }
        return configured;
    }

    private static boolean hasHelp(final String[] args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) return true;
        }
        return false;
    }

    private static void usage(final PrintStream output) {
        output.println("usage:");
        output.println("  MappingReviewCli generate --artifact <jar> --pack <worktree-relative-pack.json>");
        output.println("      --semantic-name <id> --expected-old-runtime <internal-name>");
        output.println("      --caller-owner <internal-name> --caller-name <name> --caller-descriptor <descriptor>");
        output.println("      --target-method-name <name> --target-method-descriptor <descriptor>");
        output.println("      [--invocation ANY|STATIC|INSTANCE] [--root <worktree>] [--output <directory>] [--worktree-id <id>]");
        output.println("  MappingReviewCli apply --candidate <candidate.json> --review <review.json> --artifact <jar>");
        output.println("      [--root <worktree>] [--write]");
        output.println("apply defaults to dry-run; --write is required for an atomic mapping-pack replacement.");
    }

    private static final class Arguments {
        private static final Set<String> GENERATE_OPTIONS = Set.of(
            "root", "artifact", "pack", "semantic-name", "expected-old-runtime", "caller-owner",
            "caller-name", "caller-descriptor", "target-method-name", "target-method-descriptor",
            "invocation", "output", "worktree-id"
        );
        private static final Set<String> APPLY_OPTIONS = Set.of("root", "candidate", "review", "artifact");
        private final Map<String, String> values;
        private final boolean write;

        private Arguments(final Map<String, String> values, final boolean write) {
            this.values = values;
            this.write = write;
        }

        static Arguments parse(final String[] args, final int start) {
            final Map<String, String> values = new LinkedHashMap<>();
            boolean write = false;
            for (int index = start; index < args.length; index++) {
                final String argument = args[index];
                if ("--write".equals(argument)) {
                    if (write) throw new IllegalArgumentException("--write may appear only once");
                    write = true;
                    continue;
                }
                if (!argument.startsWith("--")) throw new IllegalArgumentException("unexpected argument: " + argument);
                final String key = argument.substring(2);
                if (key.isBlank() || index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for " + argument);
                }
                if (values.put(key, args[++index]) != null) {
                    throw new IllegalArgumentException("duplicate option: " + argument);
                }
            }
            return new Arguments(Map.copyOf(values), write);
        }

        void requireAllowed(final String command) {
            final Set<String> allowed = switch (command) {
                case "generate" -> GENERATE_OPTIONS;
                case "apply" -> APPLY_OPTIONS;
                default -> throw new IllegalArgumentException("unknown command: " + command);
            };
            for (String key : values.keySet()) {
                if (!allowed.contains(key)) throw new IllegalArgumentException("unknown option for " + command + ": --" + key);
            }
            if (write && !"apply".equals(command)) throw new IllegalArgumentException("--write is valid only for apply");
        }

        String required(final String key) {
            final String value = values.get(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("missing required --" + key);
            return value;
        }

        String optional(final String key, final String defaultValue) {
            final String value = values.get(key);
            return value == null ? defaultValue : value;
        }

        Path optionalPath(final String key) {
            final String value = values.get(key);
            return value == null ? null : Path.of(value);
        }

        boolean write() {
            return write;
        }

        void rejectWrite() {
            if (write) throw new IllegalArgumentException("--write is valid only for apply");
        }
    }
}
