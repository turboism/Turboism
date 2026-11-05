package dev.turboism.gradle.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.gradle.api.GradleException;

/**
 * The aggregate gate graph as data, read from {@code gradle/gates.txt}.
 *
 * <p>The five aggregate gates used to restate their membership as Kotlin {@code dependsOn} lists,
 * so the only machine-readable statement of what a gate covers was a second parser written in
 * Python. The manifest is that statement instead, and this reader is the only place the graph is
 * interpreted; {@code gradle/verification.gradle.kts} registers exactly the aggregates and members
 * declared here, and {@code scripts/test/test_ci_coverage.py} asserts the surrounding contract.
 *
 * <p>Loading is fail-closed and reports every malformed line in one exception: a duplicate
 * aggregate, a member of an undeclared aggregate, an unbound {@code @token}, a route without a
 * reason, and an aggregate without both {@code owner} and {@code harm} are all collected before
 * throwing. A manifest that half-parsed would silently shrink a gate, which is the failure mode
 * this class exists to prevent.
 *
 * <p>{@code @token} members are the dynamic collections the caller binds; every other member stays
 * a plain task name so Gradle keeps resolving it lazily, including a task registered by a script
 * applied after this one. Member order is declaration order and is observable, because Gradle
 * reports the graph in dependency order: reordering a member list is a behaviour change.
 *
 * <p>Instances are immutable and are meant to be loaded once on the configuration thread.
 */
public final class GateManifest {

    /** A workflow that launches the aggregate, and the line that does it, or {@code via:}. */
    private static final Pattern WORKFLOW_OWNER = Pattern.compile("[\\w./-]+\\.ya?ml:\\d+");

    private static final Pattern VIA_OWNER = Pattern.compile("via:[A-Za-z_][\\w-]*");

    private static final Pattern TOKEN = Pattern.compile("@[\\w:.-]+");

    private final Path file;
    private final List<String> aggregateNames;
    private final Map<String, Aggregate> aggregates;
    private final List<Route> routes;

    private GateManifest(
            Path file, List<String> aggregateNames, Map<String, Aggregate> aggregates, List<Route> routes) {
        this.file = file;
        this.aggregateNames = aggregateNames;
        this.aggregates = aggregates;
        this.routes = routes;
    }

    /**
     * Loads a manifest that binds no {@code @token}, so any member naming one fails the load.
     *
     * @throws GateManifestException listing every malformed line
     */
    public static GateManifest load(Path file) {
        return load(file, Set.of());
    }

    /**
     * Loads the manifest, failing closed in both directions: a {@code @token} member the caller did
     * not bind, and a bound token no member names, are both malformed.
     *
     * @param boundTokens the token names the caller can resolve to a task collection
     * @throws GateManifestException listing every malformed line
     */
    public static GateManifest load(Path file, Set<String> boundTokens) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new GateManifestException(
                    "Cannot read gate manifest " + file + ": " + failure.getMessage(), failure);
        }
        return parse(file, lines, boundTokens);
    }

    private static GateManifest parse(Path file, List<String> lines, Set<String> boundTokens) {
        Map<String, RawAggregate> declared = new LinkedHashMap<>();
        List<RawMember> members = new ArrayList<>();
        Map<String, Route> routes = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            String where = file + ":" + (index + 1);
            String text = stripComment(lines.get(index)).trim();
            if (text.isEmpty()) {
                continue;
            }
            List<String> words = split(text);
            String directive = words.get(0);
            if (words.size() < 2) {
                problems.add(where + ": \"" + directive + "\" needs a task or aggregate name");
                continue;
            }
            String name = words.get(1);
            Map<String, String> attributes = new LinkedHashMap<>();
            List<String> flags = new ArrayList<>();
            switch (directive) {
                case "aggregate" -> {
                    collect(words, 2, attributes, flags, problems, where);
                    RawAggregate aggregate = new RawAggregate(where, name, attributes, flags);
                    RawAggregate previous = declared.putIfAbsent(name, aggregate);
                    if (previous != null) {
                        problems.add(where + ": aggregate \"" + name + "\" is already declared at "
                                + previous.where());
                    }
                }
                case "member" -> {
                    if (words.size() < 3) {
                        problems.add(where + ": member of \"" + name + "\" names no task");
                        continue;
                    }
                    members.add(new RawMember(where, name, words.get(2)));
                    if (words.size() > 3) {
                        problems.add(where + ": member of \"" + name + "\" names more than one task");
                    }
                }
                case "manual", "opt-in", "orphan" -> {
                    collect(words, 2, attributes, flags, problems, where);
                    Route route = route(directive, where, name, attributes, flags, problems);
                    Route previous = routes.putIfAbsent(name, route);
                    if (previous != null) {
                        problems.add(where + ": \"" + name + "\" is already routed as " + previous.kind());
                    }
                }
                default -> problems.add(where + ": unknown directive \"" + directive
                        + "\" (expected aggregate, member, manual, opt-in or orphan)");
            }
        }

        List<String> names = new ArrayList<>();
        Map<String, List<String>> memberLists = new LinkedHashMap<>();
        for (RawAggregate aggregate : declared.values()) {
            names.add(aggregate.name());
            memberLists.put(aggregate.name(), new ArrayList<>());
        }
        Set<String> usedTokens = new LinkedHashSet<>();
        for (RawMember member : members) {
            if (!memberLists.containsKey(member.aggregate())) {
                problems.add(member.where() + ": member belongs to undeclared aggregate \""
                        + member.aggregate() + "\"");
                continue;
            }
            memberLists.get(member.aggregate()).add(member.task());
            if (member.task().startsWith("@")) {
                if (!TOKEN.matcher(member.task()).matches()) {
                    problems.add(member.where() + ": \"" + member.task() + "\" is not a token name");
                } else if (!boundTokens.contains(member.task())) {
                    problems.add(member.where() + ": token \"" + member.task()
                            + "\" is not bound by the caller, so the aggregate would silently shrink");
                } else {
                    usedTokens.add(member.task());
                }
            }
        }
        for (String token : boundTokens) {
            if (!usedTokens.contains(token)) {
                problems.add(file + ": token \"" + token + "\" is bound but no member names it");
            }
        }

        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (RawAggregate raw : declared.values()) {
            Aggregate aggregate = aggregate(raw, memberLists.get(raw.name()), declared.keySet(), problems);
            aggregates.put(aggregate.name(), aggregate);
        }
        for (Route route : routes.values()) {
            boolean isMember = memberLists.values().stream().anyMatch(list -> list.contains(route.taskName()));
            if (isMember) {
                problems.add(file + ": routed task \"" + route.taskName()
                        + "\" is also an aggregate member, so its route can never take effect");
            }
        }

        if (!problems.isEmpty()) {
            throw new GateManifestException(
                    "Gate manifest " + file + " is malformed:\n  " + String.join("\n  ", problems));
        }
        return new GateManifest(file, List.copyOf(names), aggregates, List.copyOf(routes.values()));
    }

    private static Aggregate aggregate(
            RawAggregate raw, List<String> members, Set<String> declaredNames, List<String> problems) {
        String where = raw.where();
        String group = required(raw, "group", problems);
        String description = required(raw, "description", problems);
        String harm = required(raw, "harm", problems);
        String owner = required(raw, "owner", problems);
        if (!owner.isEmpty() && !WORKFLOW_OWNER.matcher(owner).matches()) {
            if (VIA_OWNER.matcher(owner).matches()) {
                String launcher = owner.substring("via:".length());
                if (!declaredNames.contains(launcher)) {
                    problems.add(where + ": owner \"via:" + launcher + "\" names no declared aggregate");
                }
            } else {
                problems.add(where + ": owner \"" + owner
                        + "\" must be \"<workflow>:<line>\" or \"via:<aggregate>\"");
            }
        }
        Set<String> allowed = Set.of("group", "description", "harm", "owner");
        for (String attribute : raw.attributes().keySet()) {
            if (!allowed.contains(attribute)) {
                problems.add(where + ": unknown attribute \"" + attribute + "\" on aggregate \"" + raw.name() + "\"");
            }
        }
        for (String flag : raw.flags()) {
            if (!flag.equals("release-flags") && !flag.equals("require-xvfb")) {
                problems.add(where + ": unknown flag \"" + flag + "\" on aggregate \"" + raw.name() + "\"");
            }
        }
        return new Aggregate(
                raw.name(),
                group,
                description,
                List.copyOf(members),
                owner,
                raw.flags().contains("release-flags"),
                raw.flags().contains("require-xvfb"),
                harm);
    }

    private static String required(RawAggregate raw, String attribute, List<String> problems) {
        String value = raw.attributes().get(attribute);
        if (value == null) {
            problems.add(raw.where() + ": aggregate \"" + raw.name() + "\" is missing " + attribute + "=\"<value>\"");
            return "";
        }
        if (value.isBlank()) {
            problems.add(raw.where() + ": aggregate \"" + raw.name() + "\" has a blank " + attribute + "=\"<value>\"");
        }
        return value;
    }

    private static Route route(
            String directive,
            String where,
            String task,
            Map<String, String> attributes,
            List<String> flags,
            List<String> problems) {
        Kind kind = switch (directive) {
            case "manual" -> Kind.MANUAL;
            case "opt-in" -> Kind.OPT_IN;
            default -> Kind.ORPHAN;
        };
        String reason = attributes.get("reason");
        if (reason == null || reason.isBlank()) {
            problems.add(where + ": " + directive + " route for \"" + task + "\" is missing reason=\"<value>\"");
            reason = "";
        }
        for (String attribute : attributes.keySet()) {
            if (!attribute.equals("reason") && !attribute.equals("doc")) {
                problems.add(where + ": unknown attribute \"" + attribute + "\" on route for \"" + task + "\"");
            }
        }
        for (String flag : flags) {
            problems.add(where + ": unexpected flag \"" + flag + "\" on route for \"" + task + "\"");
        }
        return new Route(task, kind, reason, attributes.getOrDefault("doc", ""));
    }

    /**
     * Splits {@code key="value"} attributes and bare flags.
     *
     * <p>Values may contain spaces and {@code =} but not a double quote, which keeps the grammar a
     * single line per declaration and still lets a harm citation read as a sentence.
     */
    private static void collect(
            List<String> words,
            int from,
            Map<String, String> attributes,
            List<String> flags,
            List<String> problems,
            String where) {
        for (int index = from; index < words.size(); index++) {
            String word = words.get(index);
            int equals = word.indexOf('=');
            if (equals < 0) {
                flags.add(word);
                continue;
            }
            String key = word.substring(0, equals);
            String value = word.substring(equals + 1);
            if (!value.startsWith("\"") || !value.endsWith("\"") || value.length() < 2) {
                problems.add(where + ": \"" + word + "\" must be written as " + key + "=\"<value>\"");
                continue;
            }
            attributes.putIfAbsent(key, value.substring(1, value.length() - 1));
        }
    }

    /** Drops an unquoted comment, so a declaration may carry a trailing note. */
    private static String stripComment(String line) {
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            } else if (character == '#' && !quoted) {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private static List<String> split(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            }
            if (!quoted && Character.isWhitespace(character)) {
                if (current.length() > 0) {
                    words.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(character);
        }
        if (current.length() > 0) {
            words.add(current.toString());
        }
        return words;
    }

    /** Declared aggregate names, in declaration order: the only names a script may register. */
    public List<String> aggregateNames() {
        return aggregateNames;
    }

    /**
     * The aggregate called {@code name}.
     *
     * @throws IllegalArgumentException when the name is not declared, never a partial aggregate
     */
    public Aggregate aggregate(String name) {
        Aggregate aggregate = aggregates.get(name);
        if (aggregate == null) {
            throw new IllegalArgumentException(
                    "Aggregate \"" + name + "\" is not declared in " + file + "; declared: " + aggregateNames);
        }
        return aggregate;
    }

    /** Non-member gates and their route, in file order. */
    public List<Route> routes() {
        return routes;
    }

    /** True when {@code taskName} carries a manual, opt-in or orphan route. */
    public boolean isRouted(String taskName) {
        return routes.stream().anyMatch(route -> route.taskName().equals(taskName));
    }

    /**
     * One aggregate gate.
     *
     * <p>{@code owner} is the workflow and line that launches it, or {@code via:<aggregate>} for an
     * aggregate reached only through another one; {@code harm} is never blank because the manifest
     * refuses to load without it, and {@code members} is declaration order.
     */
    public record Aggregate(
            String name,
            String group,
            String description,
            List<String> members,
            String owner,
            boolean releaseFlags,
            boolean requireXvfb,
            String harm) {
    }

    /** A gate deliberately outside the aggregate graph, and why. */
    public record Route(String taskName, Kind kind, String reason, String documentation) {
    }

    /** Why a gate is not an aggregate member. */
    public enum Kind {
        /** Run by a human on demand; no aggregate and no workflow launches it. */
        MANUAL,
        /** Reached outside the static graph: a module {@code check} alias, a dynamic edge or a workflow. */
        OPT_IN,
        /** No route at all; a tombstone the maintainer must resolve. */
        ORPHAN
    }

    /** A manifest that cannot be read whole; the message lists every offending line. */
    public static final class GateManifestException extends GradleException {

        private static final long serialVersionUID = 1L;

        GateManifestException(String message) {
            super(message);
        }

        GateManifestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record RawAggregate(String where, String name, Map<String, String> attributes, List<String> flags) {
    }

    private record RawMember(String where, String aggregate, String task) {
    }
}
