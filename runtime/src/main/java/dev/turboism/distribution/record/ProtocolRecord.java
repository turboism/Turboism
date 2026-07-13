package dev.turboism.distribution.record;

import java.util.Objects;

record ProtocolRecord(String sourceJson) {
    ProtocolRecord {
        sourceJson = Objects.requireNonNull(sourceJson, "sourceJson");
    }
}
