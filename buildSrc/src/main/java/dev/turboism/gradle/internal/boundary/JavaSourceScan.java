package dev.turboism.gradle.internal.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The source-level half of the SDK/plugin boundary: what a Java source file may not say.
 *
 * <p>Pure: a file already read from disk in, messages out. The caller owns the walk, the project
 * paths and the decision of which families apply to a module; this class owns the rule tables, the
 * lexer that decides what counts as code, and the message vocabulary. Each method returns its
 * violations in source order, so the gate's output order is the scan order.
 *
 * <p>Comment and string handling is load-bearing and is preserved verbatim: text inside a comment or
 * a string literal is not code, and {@code SwingUtilities.getWindowAncestor(} in a comment must stay
 * legal while {@code SwingUtilities . getWindowAncestor(} at a call site must not. The lexer keeps
 * newlines so line numbers in messages are the reader's line numbers.
 */
public final class JavaSourceScan {

    private static final String INTERNAL_CONTRACT_NOTE = "internal management contracts are not a plugin API";

    private static final List<Rule> PACKAGE_DECLARATIONS = List.of(new Rule(
            Pattern.compile("^\\s*package\\s+dev\\.turboism\\.sdk\\.event\\.cubism\\s*;"),
            "Retired package dev.turboism.sdk.event.cubism must not be reintroduced "
                    + "(Cubism events live in dev.turboism.sdk.cubism.event)"));

    /*
     * Import rules are matched against the whole trimmed line, so a trailing comment changes the
     * outcome of the line as written; the patterns themselves keep their historical shape, where
     * `*` is a regex quantifier rather than a package wildcard.
     */
    private static final Pattern DISTRIBUTION_IMPORT =
            Pattern.compile("import (?:static\\s+)?dev\\.turboism\\.distribution(?:\\..*)?;");

    private static final List<Rule> RESTRICTED_IMPORTS = List.of(
            importRule(
                    "dev.turboism.*.internal.*",
                    "SDK/public modules must not import runtime internal packages"),
            importRule("com.live2d.*", "SDK/plugins must not import Cubism internal packages (com.live2d)"),
            importRule("dev.turboism.core.parameter.*", "SDK/plugins must not import runtime parameter internals"),
            importRule("dev.turboism.core.mesh.*", "SDK/plugins must not import runtime mesh internals"),
            importRule("dev.turboism.core.psd.*", "SDK/plugins must not import runtime PSD internals"),
            importRule("dev.turboism.core.mirror.*", "SDK/plugins must not import runtime mirror internals"),
            importRule(
                    "dev.turboism.persistence.*",
                    "SDK/plugins must not import the runtime persistence kernel (:persistence-core)"),
            importRule(
                    "dev.turboism.compatibility.*",
                    "SDK/plugins must not import the compatibility admission policy (:compatibility-policy)"),
            importRule(
                    "dev.turboism.sdk.event.cubism.*",
                    "SDK/plugins must not import the retired dev.turboism.sdk.event.cubism package "
                            + "(Cubism events live in dev.turboism.sdk.cubism.event)"));

    private static final List<Rule> QUALIFIED_REFERENCES = List.of(
            new Rule(
                    Pattern.compile(
                            "(?<![\\w$])dev\\.turboism\\.[A-Za-z_$][\\w$]*\\.internal"
                                    + "(?:\\.[A-Za-z_$][\\w$]*)+(?![\\w$])"),
                    "SDK/public modules must not reference runtime internal packages"),
            new Rule(
                    Pattern.compile("(?<![\\w$])com\\.live2d(?:\\.[A-Za-z_$][\\w$]*)+(?![\\w$])"),
                    "SDK/plugins must not reference Cubism internal packages (com.live2d)"),
            new Rule(
                    Pattern.compile("(?<![\\w$])dev\\.turboism\\.core\\.(?:parameter|mesh|psd|mirror)"
                            + "(?:\\.[A-Za-z_$][\\w$]*)+(?![\\w$])"),
                    "SDK/plugins must not reference runtime core internals"),
            new Rule(
                    Pattern.compile("(?<![\\w$])dev\\.turboism\\.persistence(?:\\.[A-Za-z_$][\\w$]*)+(?![\\w$])"),
                    "SDK/plugins must not reference the runtime persistence kernel (:persistence-core)"),
            new Rule(
                    Pattern.compile("(?<![\\w$])dev\\.turboism\\.compatibility(?:\\.[A-Za-z_$][\\w$]*)+(?![\\w$])"),
                    "SDK/plugins must not reference the compatibility admission policy (:compatibility-policy)"),
            new Rule(
                    Pattern.compile("(?<![\\w$])dev\\.turboism\\.distribution(?:\\.[A-Za-z_$][\\w$]*)+(?![\\w$])"),
                    "SDK/plugins must not reference distribution internals"));

    private static final Pattern INTERNAL_CONTRACT_REFERENCE =
            Pattern.compile("(?<![\\w$])dev\\.turboism\\.internal(?:\\.[A-Za-z_$][\\w$]*)+(?![\\w$])");

    private static final List<TokenRule> HOST_UI_TRAVERSAL = List.of(
            new TokenRule(
                    "SwingUtilities.getWindowAncestor(",
                    Pattern.compile("SwingUtilities\\s*\\.\\s*getWindowAncestor\\s*\\(")),
            new TokenRule("SwingUtilities.getRoot(", Pattern.compile("SwingUtilities\\s*\\.\\s*getRoot\\s*\\(")),
            new TokenRule(".getTopLevelAncestor()", Pattern.compile("\\.\\s*getTopLevelAncestor\\s*\\(\\s*\\)")));

    private JavaSourceScan() {
    }

    /** A source file already read by the caller; {@code relativePath} is what messages name. */
    public record SourceFile(String relativePath, List<String> lines) {
    }

    /**
     * A retired package declaration. Applies to every scanned source, whoever owns it: the package
     * is gone from the SDK and must not come back through a copied file.
     */
    public static List<String> forbiddenPackageDeclarations(SourceFile file) {
        List<String> violations = new ArrayList<>();
        for (String line : file.lines()) {
            for (Rule rule : PACKAGE_DECLARATIONS) {
                if (rule.pattern().matcher(line).find()) {
                    violations.add(file.relativePath() + ": " + rule.message());
                }
            }
        }
        return violations;
    }

    /**
     * Import declarations the SDK and plugins may not carry, one message per hit, in line order.
     *
     * <p>The distribution import is reported separately from the table so its message stays
     * distinct from the "forbidden import" family it is also a member of.
     */
    public static List<String> restrictedImports(SourceFile file) {
        List<String> violations = new ArrayList<>();
        List<String> lines = file.lines();
        for (int index = 0; index < lines.size(); index++) {
            String trimmed = lines.get(index).strip();
            int lineNumber = index + 1;
            if (DISTRIBUTION_IMPORT.matcher(trimmed).matches()) {
                violations.add("Forbidden distribution import in " + file.relativePath() + ":" + lineNumber);
            }
            for (Rule rule : RESTRICTED_IMPORTS) {
                if (rule.pattern().matcher(trimmed).matches()) {
                    violations.add("Forbidden import in " + file.relativePath() + ":" + lineNumber + ": "
                            + rule.message());
                }
            }
        }
        return violations;
    }

    /**
     * Fully-qualified references to internals, at most one message per rule.
     *
     * <p>Non-static imports are filtered out, but {@code import static} lines must stay: they can
     * smuggle a forbidden reference past both this scan and the restricted-import regex.
     */
    public static List<String> qualifiedReferences(SourceFile file) {
        String source = stripCommentsAndStrings(String.join(
                "\n",
                file.lines().stream()
                        .filter(line -> {
                            String trimmed = line.stripLeading();
                            return !(trimmed.startsWith("import ") && !trimmed.startsWith("import static "));
                        })
                        .toList()));
        List<String> violations = new ArrayList<>();
        for (Rule rule : QUALIFIED_REFERENCES) {
            Matcher match = rule.pattern().matcher(source);
            if (match.find()) {
                violations.add("Forbidden fully-qualified reference '" + match.group() + "' in "
                        + file.relativePath() + ": " + rule.message());
            }
        }
        return violations;
    }

    /**
     * References to {@code dev.turboism.internal.*}, the composition-internal management contracts:
     * the SDK and ordinary plugins must never import or reference them, so built-in-only services
     * cannot leak through the plugin surface. The framework shell lives in {@code :runtime} and
     * needs no plugin exception.
     *
     * <p>Import lines are reported by line number; every other occurrence, including one inside an
     * {@code import static} line, is reported as a reference.
     */
    public static List<String> internalContractReferences(SourceFile file) {
        List<String> violations = new ArrayList<>();
        List<String> lines = file.lines();
        for (int index = 0; index < lines.size(); index++) {
            String trimmed = lines.get(index).strip();
            if (trimmed.startsWith("import ") || trimmed.startsWith("import static ")) {
                String imported = trimmed.substring("import ".length());
                if (imported.startsWith("static ")) {
                    imported = imported.substring("static ".length());
                }
                if (imported.endsWith(";")) {
                    imported = imported.substring(0, imported.length() - 1);
                }
                if (imported.strip().startsWith("dev.turboism.internal.")) {
                    violations.add("Forbidden internal-contract import in " + file.relativePath() + ":"
                            + (index + 1) + ": " + INTERNAL_CONTRACT_NOTE);
                }
            }
        }
        String source = stripCommentsAndStrings(String.join(
                "\n",
                lines.stream().filter(line -> !line.stripLeading().startsWith("import ")).toList()));
        Matcher references = INTERNAL_CONTRACT_REFERENCE.matcher(source);
        while (references.find()) {
            violations.add("Forbidden internal-contract reference '" + references.group() + "' in "
                    + file.relativePath() + ": " + INTERNAL_CONTRACT_NOTE);
        }
        return violations;
    }

    /**
     * Plugin-owned external Swing views discovering or mutating host UI trees.
     *
     * <p>Comments and string literals are stripped first: the tokens only matter as executed calls,
     * and whitespace-tolerant matching covers {@code SwingUtilities .} call-site variants.
     */
    public static List<String> hostUiTraversal(SourceFile file) {
        String source = stripCommentsAndStrings(String.join("\n", file.lines()));
        List<String> violations = new ArrayList<>();
        for (TokenRule rule : HOST_UI_TRAVERSAL) {
            if (rule.pattern().matcher(source).find()) {
                violations.add("Plugin-owned external Swing views must not discover or mutate host UI trees; "
                        + "forbidden token '" + rule.token() + "' in " + file.relativePath());
            }
        }
        return violations;
    }

    /**
     * Blanks out {@code //} line comments, block comments and string/char literals, preserving
     * newlines and every other character position.
     *
     * <p>The quirks are deliberate and fixture-proven: an unterminated block comment swallows the
     * rest of the file, a newline inside a string literal ends the literal, and a text block is read
     * as a sequence of ordinary string literals. Changing any of that changes which references a
     * plugin can hide.
     */
    public static String stripCommentsAndStrings(String source) {
        StringBuilder output = new StringBuilder(source.length());
        int state = 0;
        boolean escaped = false;
        int index = 0;
        while (index < source.length()) {
            char character = source.charAt(index);
            switch (state) {
                case 0 -> {
                    if (source.startsWith("//", index)) {
                        output.append(' ');
                        state = 1;
                        index += 2;
                    } else if (source.startsWith("/*", index)) {
                        output.append(' ');
                        state = 2;
                        index += 2;
                    } else if (character == '"') {
                        output.append(' ');
                        state = 3;
                        index++;
                    } else if (character == '\'') {
                        output.append(' ');
                        state = 4;
                        index++;
                    } else {
                        output.append(character);
                        index++;
                    }
                }
                case 1 -> {
                    if (character == '\n') {
                        output.append('\n');
                        state = 0;
                    }
                    index++;
                }
                case 2 -> {
                    if (source.startsWith("*/", index)) {
                        output.append(' ');
                        state = 0;
                        index += 2;
                    } else {
                        if (character == '\n') {
                            output.append('\n');
                        }
                        index++;
                    }
                }
                case 3, 4 -> {
                    char closingQuote = state == 3 ? '"' : '\'';
                    if (character == '\\' && !escaped) {
                        escaped = true;
                        index++;
                    } else if (escaped) {
                        escaped = false;
                        index++;
                    } else if (character == closingQuote) {
                        output.append(' ');
                        state = 0;
                        index++;
                    } else if (character == '\n') {
                        output.append('\n');
                        state = 0;
                        index++;
                    } else {
                        index++;
                    }
                }
            }
        }
        return output.toString();
    }

    private static Rule importRule(String declaration, String message) {
        return new Rule(Pattern.compile("import (?:static\\s+)?" + declaration + ";"), message);
    }

    /** A compiled rule and the words it contributes to a message. */
    private record Rule(Pattern pattern, String message) {
    }

    /** A host-UI token as written in the message, and the whitespace-tolerant pattern it matches. */
    private record TokenRule(String token, Pattern pattern) {
    }
}
