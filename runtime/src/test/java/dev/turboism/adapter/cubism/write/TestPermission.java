package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.permission.PluginPermission;

final class TestPermission implements PluginPermission {
    private final String id;

    TestPermission(final String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String scope() {
        return "write";
    }

    @Override
    public String reason() {
        return "test";
    }
}
