package dev.turboism.adapter.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OffCanvasBackgroundHostAccessTest {

    private static final String BACKGROUND = "CubismCommon.gl.viewArea.background";

    @Test
    void rejectsUnreviewedArtifactsBeforeResolvingHostClasses(@TempDir final Path directory) throws Exception {
        final Path artifact = Files.writeString(directory.resolve("unknown.jar"), "unreviewed");
        assertThrows(
                IllegalArgumentException.class,
                () -> new OffCanvasBackgroundHostAccess(artifact, getClass().getClassLoader()));
    }

    /** Opt-in real-class regression; licensed jars remain external to the repository. */
    @Test
    void refreshesExistingAndFutureBackgroundMaterialsAcrossReviewedHosts() throws Exception {
        final String evidence = System.getenv("TURBOISM_OFF_CANVAS_EVIDENCE");
        final String jogl = System.getenv("TURBOISM_OFF_CANVAS_JOGL");
        assumeTrue(evidence != null && jogl != null, "requires separately installed Cubism and JOGL evidence");
        final Object previous = UIManager.get(BACKGROUND);
        try {
            for (String version : dev.turboism.mapping.verification.ReviewedHostArtifacts.reviewedCubismVersions()) {
                final Path directory = Path.of(evidence, version);
                final List<URL> urls = new ArrayList<>();
                for (Path root : List.of(directory, Path.of(jogl))) {
                    try (var files = Files.list(root)) {
                        for (Path jar : files.filter(path -> path.toString().endsWith(".jar"))
                                .toList()) {
                            urls.add(jar.toUri().toURL());
                        }
                    }
                }
                try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                    verifySwitchAndRestore(directory.resolve("Live2D_Cubism.jar"), loader);
                }
            }
        } finally {
            UIManager.put(BACKGROUND, previous);
        }
    }

    private static void verifySwitchAndRestore(final Path jar, final ClassLoader loader) throws Exception {
        final Color initial = new Color(240, 224, 229);
        final Color dark = new Color(36, 41, 51);
        final Color light = new Color(224, 239, 245);
        UIManager.put(BACKGROUND, initial);
        final var access = new OffCanvasBackgroundHostAccess(jar, loader);
        final Class<?> colors = loader.loadClass("com.live2d.cubism.view.gl.SGFramework.h$d");
        final Object singleton = colors.getField("a").get(null);
        final var readColor = colors.getMethod("c");
        assertEquals(initial, readColor.invoke(singleton));
        final var lazyField = colors.getDeclaredField("d");
        lazyField.setAccessible(true);
        final Object lazy = lazyField.get(null);
        final Class<?> settings = loader.loadClass("com.live2d.cubism.view.context.CEViewContext")
                .getMethod("getAppearanceSetting")
                .getReturnType();
        final Object first = settings.getConstructor().newInstance();
        final Object second = settings.getConstructor().newInstance();
        final boolean indexed = jar.getParent().getFileName().toString().startsWith("5.3.");
        assertEquals(initial, materialColor(first, indexed));

        for (Color next : List.of(dark, light, initial)) {
            UIManager.put(BACKGROUND, next);
            OffCanvasBackgroundHostAccess.updateCachedColor(lazy, next);
            access.updateAppearance(first, next);
            access.updateAppearance(second, next);
            assertEquals(next, readColor.invoke(singleton), jar.toString());
            assertEquals(next, materialColor(first, indexed), "first existing view: " + jar);
            assertEquals(next, materialColor(second, indexed), "second existing view: " + jar);
            assertEquals(next, materialColor(settings.getConstructor().newInstance(), indexed), "new view: " + jar);
        }
    }

    private static Color materialColor(final Object settings, final boolean indexed) throws Exception {
        final Object panel = settings.getClass().getMethod("f").invoke(settings);
        final Object renderer = panel.getClass().getMethod("getMeshRenderer").invoke(panel);
        final Object material = renderer.getClass().getMethod("getMaterial").invoke(renderer);
        final Object color = indexed
                ? material.getClass()
                        .getMethod("getColor", String.class, Integer.class)
                        .invoke(material, "baseColor", null)
                : material.getClass().getMethod("getColor", String.class).invoke(material, "baseColor");
        return (Color) color.getClass().getMethod("getJColor").invoke(color);
    }
}
