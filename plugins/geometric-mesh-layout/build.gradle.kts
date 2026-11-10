plugins {
    id("turboism.plugin-conventions")
}

turboismPlugin {
    verifyJar(
        taskName = "verifyGeometricMeshLayoutJar",
        requiredEntries = setOf(
            "META-INF/turboism/plugin.json",
            "META-INF/turboism/i18n/baseline-keys.txt",
            "META-INF/turboism/i18n/messages.properties",
            "META-INF/turboism/i18n/messages_en.properties",
            "META-INF/turboism/i18n/messages_ja.properties",
            "META-INF/turboism/i18n/messages_ko.properties",
            "META-INF/turboism/i18n/messages_zh_Hans.properties",
            "META-INF/turboism/i18n/messages_zh_Hant.properties",
            "icons/geometric-mesh-layout.png",
            "icons/geometric-mesh-layout-active.png",
            "icons/geometric-mesh-layout-rollover.png",
            "icons/geometric-mesh-layout-selected.png",
            "icons/geometric-mesh-layout-disabled.png",
            "icons/geometric-mesh-layout-disabled-selected.png"
        ),
        classPrefix = "dev/turboism/plugin/geometricmeshlayout/"
    )
}
