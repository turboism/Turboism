plugins {
    id("turboism.plugin-conventions")
}

turboismPlugin {
    verifyJar(
        taskName = "verifySelectionBrushJar",
        requiredEntries = setOf(
            "META-INF/turboism/plugin.json",
            "META-INF/turboism/i18n/baseline-keys.txt",
            "META-INF/turboism/i18n/messages.properties",
            "META-INF/turboism/i18n/messages_en.properties",
            "META-INF/turboism/i18n/messages_ja.properties",
            "META-INF/turboism/i18n/messages_ko.properties",
            "META-INF/turboism/i18n/messages_zh_Hans.properties",
            "META-INF/turboism/i18n/messages_zh_Hant.properties",
            "icons/selection-brush.png",
            "icons/selection-brush-active.png",
            "icons/selection-brush-rollover.png",
            "icons/selection-brush-selected.png",
            "icons/selection-brush-disabled.png",
            "icons/selection-brush-disabled-selected.png"
        ),
        classPrefix = "dev/turboism/plugin/selectionbrush/"
    )
}
