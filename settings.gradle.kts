rootProject.name = "turboism-root"

include(
    "bootstrap",
    "runtime",
    "sdk",
    "plugins:demo",
    "plugins:ui-theme",
    "plugins:log-filter",
    "plugins:core",
    "plugins:perf-opt",
    "plugins:render-opt",
    "plugins:clip-mask",
    "plugins:parameter",
    "plugins:mesh",
    "plugins:project-inspector",
    "plugins:bounding-box",
    "plugins:context-menu",
    "plugins:project-panel",
    "plugins:scene-palette-enhancer",
    "plugins:psd-import",
    "plugins:texture-atlas",
    "plugins:texture-atlas-stats",
    "plugins:physics-editor",
    "testframework",
    "tests"
)
