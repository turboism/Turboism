package dev.turboism.plugin.acp;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One authentication method advertised by an ACP agent during initialization.
 *
 * <p>{@link Kind#AGENT} methods run in-band through the {@code authenticate} request. {@link
 * Kind#TERMINAL} methods describe a user-run subprocess (argv in {@code args}, environment
 * overrides in {@code env}) launched in a visible terminal; the client must not call {@code
 * authenticate} for those.</p>
 */
record AcpAuthMethod(
        String id, String name, String description, Kind kind, List<String> args, Map<String, String> env) {

    enum Kind {
        AGENT,
        TERMINAL
    }

    AcpAuthMethod {
        id = Objects.requireNonNull(id, "id");
        if (id.isBlank() || id.length() > 512) {
            throw new IllegalArgumentException("auth method id is invalid");
        }
        name = Objects.requireNonNullElse(name, "");
        description = Objects.requireNonNullElse(description, "");
        kind = Objects.requireNonNullElse(kind, Kind.AGENT);
        args = List.copyOf(Objects.requireNonNullElse(args, List.of()));
        env = Map.copyOf(Objects.requireNonNullElse(env, Map.of()));
    }

    String displayName() {
        return name.isBlank() ? id : name;
    }
}
