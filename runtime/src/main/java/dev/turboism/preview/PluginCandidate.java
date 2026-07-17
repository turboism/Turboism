package dev.turboism.preview;

import dev.turboism.sdk.plugin.PluginDescriptor;

import java.nio.file.Path;

/** Parsed plugin archive candidate used during local runtime discovery. */
record PluginCandidate(Path jar, PluginDescriptor descriptor) {
}
