package com.fasterxml.jackson.databind;

/** Plugin-side fixture copy of a third-party type the agent fat JAR used to leak. */
public final class ObjectMapper {

    private ObjectMapper() { }

    public static String marker() {
        return "plugin";
    }
}
