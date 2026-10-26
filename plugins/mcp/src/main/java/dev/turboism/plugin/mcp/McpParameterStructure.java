package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded structural projection and pure, sequential plan simulation. Parameter GUIDs and group display IDs remain distinct. */
final class McpParameterStructure {
    static final int MAX_GROUPS = 128, MAX_PARAMETERS = 512, MAX_EDITS = 16, MAX_BYTES = 192 * 1024;
    static final String NEW = "~created-group:";

    record Group(String id, String name, String parentId, List<ParameterGroupChild> children) {
        Group {
            children = List.copyOf(children);
        }

        Map<String, Object> json() {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("name", name);
            m.put("parentId", parentId);
            m.put(
                    "children",
                    children.stream()
                            .map(c -> Map.of("kind", c.kind().name(), "id", c.id()))
                            .toList());
            return Collections.unmodifiableMap(m);
        }
    }

    record Param(
            String id,
            String guid,
            String name,
            ParameterType type,
            Boolean repeat,
            float min,
            float max,
            float defaultValue,
            Boolean combined,
            String partnerId) {
        Param pair(Boolean flag, String partner) {
            return new Param(id, guid, name, type, repeat, min, max, defaultValue, flag, partner);
        }

        Map<String, Object> json(int index) {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("guid", guid);
            m.put("index", index);
            m.put("name", name);
            m.put("type", type.name());
            m.put("repeat", repeat);
            m.put("minimum", min);
            m.put("maximum", max);
            m.put("default", defaultValue);
            m.put("combined", combined);
            m.put("partnerId", partnerId);
            return Collections.unmodifiableMap(m);
        }
    }

    record State(List<Group> groups, List<Param> parameters) {
        State {
            groups = List.copyOf(groups);
            parameters = List.copyOf(parameters);
        }

        Map<String, Object> json() {
            var rows = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < parameters.size(); i++)
                rows.add(parameters.get(i).json(i));
            return Map.of("groups", groups.stream().map(Group::json).toList(), "parameters", List.copyOf(rows));
        }

        Group group(String id) {
            return groups.stream().filter(g -> g.id.equals(id)).findFirst().orElseThrow();
        }

        Param parameter(String id) {
            return parameters.stream().filter(p -> p.id.equals(id)).findFirst().orElseThrow();
        }

        Group parent(String parameter) {
            return groups.stream()
                    .filter(g -> g.children.contains(ParameterGroupChild.parameter(new ParameterId(parameter))))
                    .findFirst()
                    .orElseThrow();
        }

        State normalize(Map<String, String> nativeToPlanned) {
            return new State(
                    groups.stream()
                            .map(g -> new Group(
                                    nativeToPlanned.getOrDefault(g.id, g.id),
                                    g.name,
                                    nativeToPlanned.getOrDefault(g.parentId, g.parentId),
                                    g.children.stream()
                                            .map(c -> c.kind() == ParameterGroupChild.Kind.GROUP
                                                    ? ParameterGroupChild.group(new ParameterGroupId(
                                                            nativeToPlanned.getOrDefault(c.id(), c.id())))
                                                    : c)
                                            .toList()))
                            .toList(),
                    parameters);
        }
    }

    record Step(Map<String, Object> edit, State after, List<String> deletedGroups, List<String> deletedParameters) {}

    static State capture(CubismModel model) {
        var nativeGroups = model.parameterGroups().all();
        var nativeParams = model.parameters().all();
        if (nativeGroups.size() > MAX_GROUPS || nativeParams.size() > MAX_PARAMETERS) throw new Budget();
        var groups = new ArrayList<Group>();
        for (var g : nativeGroups) {
            String id = text(g.id().value(), 4096);
            final List<ParameterGroupChild> children;
            try {
                children = g.orderedChildren();
            } catch (NoSuchMethodError | AbstractMethodError unavailable) {
                throw new UnsupportedOperationException("Mixed parameter order SDK API is unavailable.", unavailable);
            }
            if (children.size() > MAX_GROUPS + MAX_PARAMETERS) throw new Budget();
            for (var c : children) text(c.id(), 4096);
            var childGroups = children.stream()
                    .filter(c -> c.kind() == ParameterGroupChild.Kind.GROUP)
                    .map(ParameterGroupChild::id)
                    .toList();
            var childParams = children.stream()
                    .filter(c -> c.kind() == ParameterGroupChild.Kind.PARAMETER)
                    .map(ParameterGroupChild::id)
                    .toList();
            if (!childGroups.equals(g.childGroupIds().stream()
                            .map(ParameterGroupId::value)
                            .toList())
                    || !childParams.equals(
                            g.parameterIds().stream().map(ParameterId::value).toList())) throw new InvalidState();
            groups.add(new Group(
                    id,
                    optional(g.name()),
                    g.parentId().map(v -> text(v.value(), 4096)).orElse(null),
                    children));
        }
        var params = new ArrayList<Param>();
        var parameterIds = new HashSet<String>();
        var parameterGuids = new HashSet<String>();
        for (var p : nativeParams) {
            if (!parameterIds.add(text(p.id().value(), 4096))) throw new Ambiguous();
            if (p.index() != params.size()) throw new InvalidState();
            float min = p.getMinimumValue(), max = p.getMaximumValue(), def = p.getDefaultValue();
            if (!Float.isFinite(min) || !Float.isFinite(max) || !Float.isFinite(def) || min > def || def > max)
                throw new InvalidState();
            String guid;
            try {
                guid = text(p.guid(), 4096);
            } catch (NoSuchMethodError | AbstractMethodError e) {
                throw new UnsupportedOperationException("Parameter GUID SDK reader unavailable", e);
            }
            if (!parameterGuids.add(guid)) throw new InvalidState();
            params.add(new Param(
                    text(p.id().value(), 4096),
                    guid,
                    optional(p.name()),
                    p.type(),
                    p.repeat().orElse(null),
                    min,
                    max,
                    def,
                    p.combined().orElse(null),
                    p.combinedWith().map(v -> text(v.value(), 4096)).orElse(null)));
        }
        var state = new State(groups, params);
        validate(state);
        if (!state.groups.get(0).id.equals(model.parameterGroups().root().id().value())) throw new InvalidState();
        return state;
    }

    private static String optional(Optional<String> value) {
        return value.map(v -> text(v, 4096)).orElse(null);
    }

    static void validate(State state) {
        if (state.groups.isEmpty()) throw new InvalidState();
        if (state.groups.size() > MAX_GROUPS || state.parameters.size() > MAX_PARAMETERS) throw new Budget();
        var groups = new HashMap<String, Group>();
        var params = new HashMap<String, Param>();
        for (var g : state.groups) if (groups.putIfAbsent(g.id, g) != null) throw new Ambiguous();
        for (var p : state.parameters) if (params.putIfAbsent(p.id, p) != null) throw new Ambiguous();
        var root = state.groups.get(0);
        if (root.parentId != null) throw new InvalidState();
        var seenGroups = new HashSet<String>();
        var seenParams = new HashSet<String>();
        var preorder = new ArrayList<String>();
        var pending = new ArrayDeque<String>();
        pending.push(root.id);
        while (!pending.isEmpty()) {
            String id = pending.pop();
            if (!seenGroups.add(id)) throw new InvalidState();
            var g = groups.get(id);
            if (g == null) throw new InvalidState();
            preorder.add(id);
            for (var c : g.children) {
                if (c.kind() == ParameterGroupChild.Kind.PARAMETER) {
                    if (!params.containsKey(c.id()) || !seenParams.add(c.id())) throw new InvalidState();
                } else {
                    var child = groups.get(c.id());
                    if (child == null || !Objects.equals(child.parentId, g.id)) throw new InvalidState();
                }
            }
            for (int i = g.children.size() - 1; i >= 0; i--)
                if (g.children.get(i).kind() == ParameterGroupChild.Kind.GROUP)
                    pending.push(g.children.get(i).id());
        }
        if (!preorder.equals(state.groups.stream().map(Group::id).toList()) || !seenParams.equals(params.keySet()))
            throw new InvalidState();
        for (var p : state.parameters) {
            if (p.partnerId == null) {
                if (Boolean.TRUE.equals(p.combined)) throw new InvalidState();
                continue;
            }
            var other = params.get(p.partnerId);
            if (other == null
                    || !p.id.equals(other.partnerId)
                    || p.id.equals(other.id)
                    || p.combined == null
                    || other.combined == null
                    || p.combined.equals(other.combined)) throw new InvalidState();
            var parent = state.parent(p.id);
            if (!parent.id.equals(state.parent(other.id).id)) throw new InvalidState();
            if (Boolean.TRUE.equals(p.combined)) {
                int index = parent.children.indexOf(ParameterGroupChild.parameter(new ParameterId(p.id)));
                if (index + 1 >= parent.children.size()
                        || !parent.children
                                .get(index + 1)
                                .equals(ParameterGroupChild.parameter(new ParameterId(other.id))))
                    throw new InvalidState();
            }
        }
        if (bytes(state.json()) > MAX_BYTES) throw new Budget();
    }

    static List<Step> simulate(State before, Object raw) {
        if (!(raw instanceof List<?> edits) || edits.isEmpty() || edits.size() > MAX_EDITS)
            throw new IllegalArgumentException();
        var steps = new ArrayList<Step>();
        State state = before;
        var refs = new HashMap<String, String>();
        for (Object edit : edits) {
            var op = fieldsWithKind(edit);
            String kind = text(op.get("kind"), 64);
            var groups = new LinkedHashMap<String, Group>();
            state.groups.forEach(g -> groups.put(g.id, g));
            var params = new ArrayList<>(state.parameters);
            var deletedGroups = new ArrayList<String>();
            var deletedParams = new ArrayList<String>();
            switch (kind) {
                case "create_group" -> {
                    fields(op, "kind", "ref", "name");
                    String ref = text(op.get("ref"), 64), name = text(op.get("name"), 256);
                    if (refs.putIfAbsent(ref, NEW + ref) != null) throw new IllegalArgumentException();
                    String id = NEW + ref;
                    if (groups.containsKey(id))
                        throw new IllegalArgumentException("created ref collides with an existing group ID");
                    var root = state.groups.get(0);
                    var children = new ArrayList<>(root.children);
                    children.add(ParameterGroupChild.group(new ParameterGroupId(id)));
                    groups.put(root.id, new Group(root.id, root.name, null, children));
                    groups.put(id, new Group(id, name, root.id, List.of()));
                }
                case "rename_group" -> {
                    fields(op, "kind", "group", "name");
                    var g = state.group(target(op.get("group"), refs));
                    groups.put(g.id, new Group(g.id, text(op.get("name"), 256), g.parentId, g.children));
                }
                case "remove_group" -> {
                    fields(op, "kind", "group", "expectedGroupIds", "expectedParameterIds");
                    var g = state.group(target(op.get("group"), refs));
                    if (g.parentId == null || refs.containsValue(g.id)) throw new IllegalArgumentException();
                    var subtree = new HashSet<String>();
                    subtree.add(g.id);
                    for (var candidate : state.groups)
                        if (subtree.contains(candidate.parentId)) subtree.add(candidate.id);
                    state.groups.stream().filter(v -> subtree.contains(v.id)).forEach(v -> deletedGroups.add(v.id));
                    state.parameters.stream()
                            .filter(p -> subtree.contains(stateParentId(groups, p.id)))
                            .forEach(p -> deletedParams.add(p.id));
                    if (!ids(op.get("expectedGroupIds"), MAX_GROUPS).equals(deletedGroups)
                            || !ids(op.get("expectedParameterIds"), MAX_PARAMETERS)
                                    .equals(deletedParams)) throw new IllegalArgumentException();
                    var parent = groups.get(g.parentId);
                    var children = new ArrayList<>(parent.children);
                    children.remove(ParameterGroupChild.group(new ParameterGroupId(g.id)));
                    groups.put(parent.id, new Group(parent.id, parent.name, parent.parentId, children));
                    subtree.forEach(groups::remove);
                    params.removeIf(p -> deletedParams.contains(p.id));
                }
                case "move_parameter" -> {
                    fields(op, "kind", "parameterId", "targetGroup", "insertIndex");
                    var p = state.parameter(text(op.get("parameterId"), 4096));
                    requireUnpaired(p);
                    var old = state.parent(p.id);
                    var target = state.group(target(op.get("targetGroup"), refs));
                    var child = ParameterGroupChild.parameter(new ParameterId(p.id));
                    var oldChildren = new ArrayList<>(old.children);
                    oldChildren.remove(child);
                    groups.put(old.id, new Group(old.id, old.name, old.parentId, oldChildren));
                    var newChildren = new ArrayList<>(groups.get(target.id).children);
                    int index = integer(op.get("insertIndex"), newChildren.size());
                    // Inserting between a Combined first and second would split a pair.
                    if (index > 0) {
                        var prior = newChildren.get(index - 1);
                        if (prior.kind() == ParameterGroupChild.Kind.PARAMETER
                                && Boolean.TRUE.equals(state.parameter(prior.id()).combined))
                            throw new IllegalArgumentException();
                    }
                    newChildren.add(index, child);
                    groups.put(target.id, new Group(target.id, target.name, target.parentId, newChildren));
                }
                case "combine" -> {
                    fields(op, "kind", "parameterId", "partnerId");
                    var first = state.parameter(text(op.get("parameterId"), 4096));
                    var second = state.parameter(text(op.get("partnerId"), 4096));
                    if (first.id.equals(second.id)
                            || first.type != ParameterType.NORMAL
                            || second.type != ParameterType.NORMAL) throw new IllegalArgumentException();
                    if (first.partnerId != null && first.partnerId.equals(second.id)) break;
                    requireUnpaired(first);
                    requireUnpaired(second);
                    var group = state.parent(first.id);
                    if (!group.id.equals(state.parent(second.id).id)) throw new IllegalArgumentException();
                    var children = new ArrayList<>(group.children);
                    var a = ParameterGroupChild.parameter(new ParameterId(first.id));
                    var b = ParameterGroupChild.parameter(new ParameterId(second.id));
                    children.remove(b);
                    children.add(children.indexOf(a) + 1, b);
                    groups.put(group.id, new Group(group.id, group.name, group.parentId, children));
                    replace(params, first.pair(true, second.id));
                    replace(params, second.pair(false, first.id));
                }
                case "uncombine" -> {
                    fields(op, "kind", "parameterId");
                    var p = state.parameter(text(op.get("parameterId"), 4096));
                    if (p.combined == null) throw new UnsupportedOperationException();
                    if (p.partnerId != null) {
                        replace(params, p.pair(false, null));
                        replace(params, state.parameter(p.partnerId).pair(false, null));
                    }
                }
                default -> throw new IllegalArgumentException();
            }
            state = reorder(groups, params);
            validate(state);
            steps.add(new Step(op, state, List.copyOf(deletedGroups), List.copyOf(deletedParams)));
        }
        return List.copyOf(steps);
    }

    private static String stateParentId(Map<String, Group> groups, String parameter) {
        return groups.values().stream()
                .filter(g -> g.children.contains(ParameterGroupChild.parameter(new ParameterId(parameter))))
                .findFirst()
                .orElseThrow()
                .id;
    }

    private static State reorder(Map<String, Group> groups, List<Param> params) {
        String root = groups.values().stream()
                .filter(g -> g.parentId == null)
                .findFirst()
                .orElseThrow()
                .id;
        var ordered = new ArrayList<Group>();
        var pending = new ArrayDeque<String>();
        pending.push(root);
        while (!pending.isEmpty()) {
            var g = groups.get(pending.pop());
            ordered.add(g);
            for (int i = g.children.size() - 1; i >= 0; i--)
                if (g.children.get(i).kind() == ParameterGroupChild.Kind.GROUP)
                    pending.push(g.children.get(i).id());
        }
        return new State(ordered, params);
    }

    private static void replace(List<Param> params, Param changed) {
        for (int i = 0; i < params.size(); i++)
            if (params.get(i).id.equals(changed.id)) {
                params.set(i, changed);
                return;
            }
        throw new NoSuchElementException();
    }

    private static void requireUnpaired(Param p) {
        if (p.combined == null) throw new UnsupportedOperationException();
        if (p.partnerId != null || p.combined) throw new IllegalArgumentException();
    }

    static String target(Object raw, Map<String, String> refs) {
        var m = fieldsWithKind(raw);
        if (m.keySet().equals(Set.of("id"))) {
            String id = text(m.get("id"), 4096);
            if (refs.containsValue(id)) throw new IllegalArgumentException();
            return id;
        }
        fields(m, "createdRef");
        String ref = text(m.get("createdRef"), 64);
        return Objects.requireNonNull(refs.get(ref), "unknown createdRef");
    }

    static Map<String, Object> fields(Object raw, String... keys) {
        var m = fieldsWithKind(raw);
        if (!m.keySet().equals(Set.of(keys))) throw new IllegalArgumentException();
        return m;
    }

    private static Map<String, Object> fieldsWithKind(Object raw) {
        if (!(raw instanceof Map<?, ?> m) || m.keySet().stream().anyMatch(k -> !(k instanceof String)))
            throw new IllegalArgumentException();
        var result = new LinkedHashMap<String, Object>();
        m.forEach((k, v) -> result.put((String) k, v));
        return Collections.unmodifiableMap(result);
    }

    static String text(Object raw, int max) {
        if (!(raw instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return s;
    }

    static int integer(Object raw, int max) {
        if (!(raw instanceof Number n)
                || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != n.intValue()
                || n.intValue() < 0
                || n.intValue() > max) throw new IllegalArgumentException();
        return n.intValue();
    }

    private static List<String> ids(Object raw, int max) {
        if (!(raw instanceof List<?> values) || values.size() > max) throw new IllegalArgumentException();
        var result = values.stream().map(v -> text(v, 4096)).toList();
        if (new HashSet<>(result).size() != result.size()) throw new IllegalArgumentException();
        return result;
    }

    static int bytes(Map<String, ?> value) {
        return Json.stringify(value).getBytes(StandardCharsets.UTF_8).length;
    }

    static final class Budget extends IllegalArgumentException {}

    static final class Ambiguous extends IllegalStateException {}

    static final class InvalidState extends IllegalStateException {}
}
