package dev.turboism.plugin.psdimport.b1.domain;

/**
 * Why a supplied action parameter could not be taken at face value.
 *
 * <p>{@code INVALID_DEFAULTED} means the parameter is recognised but its raw text was neither
 * {@code "true"} nor {@code "false"}, so the declared default was substituted;
 * {@code UNKNOWN_PARAMETER} means the name is not declared by the action at all and was dropped.
 */
public enum PsdParameterIssueCode {
    INVALID_DEFAULTED,
    UNKNOWN_PARAMETER
}
