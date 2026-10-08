/**
 * Strict JSON codec for plugin protocol, settings, and diagnostic payloads.
 *
 * <p>The single {@link dev.turboism.sdk.json.Json} entry point is the only JSON surface
 * plugins need; it keeps wire handling dependency-free and rejects ambiguous documents
 * instead of recovering from them.
 */
package dev.turboism.sdk.json;
