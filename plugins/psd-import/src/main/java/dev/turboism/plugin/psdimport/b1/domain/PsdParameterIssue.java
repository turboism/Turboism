package dev.turboism.plugin.psdimport.b1.domain;

import java.util.Objects;

/**
 * One complaint raised while parsing action parameters supplied by the host.
 *
 * <p>An issue is advisory, not fatal: parsing always produces a usable value map alongside these,
 * substituting the declared default for anything it could not honour.
 *
 * @param parameter the offending parameter name exactly as the host supplied it, never
 *     {@code null}; for an unknown parameter this name is not one the action declares
 * @param code why the parameter was rejected, never {@code null}
 */
public record PsdParameterIssue(String parameter, PsdParameterIssueCode code) {
    public PsdParameterIssue {
        parameter = Objects.requireNonNull(parameter, "parameter");
        code = Objects.requireNonNull(code, "code");
    }
}
