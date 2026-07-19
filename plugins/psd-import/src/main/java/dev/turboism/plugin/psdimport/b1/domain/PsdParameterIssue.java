package dev.turboism.plugin.psdimport.b1.domain;

import java.util.Objects;

public record PsdParameterIssue(String parameter, PsdParameterIssueCode code) {
    public PsdParameterIssue {
        parameter = Objects.requireNonNull(parameter, "parameter");
        code = Objects.requireNonNull(code, "code");
    }
}
