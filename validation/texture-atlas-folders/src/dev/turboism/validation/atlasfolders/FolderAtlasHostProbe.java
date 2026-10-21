package dev.turboism.validation.atlasfolders;

import dev.turboism.sdk.plugin.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.textureatlas.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Task-only host exerciser; uses SDK authoring and actual Swing menu/button actions. */
public final class FolderAtlasHostProbe implements TurboismPlugin {
    private PluginContext context;
    private Path out;
    private final Properties evidence = new Properties();
    private final String algorithm = System.getProperty("turboism.validation.folders.algorithm", "maxrects");
    private final boolean reopen = Boolean.getBoolean("turboism.validation.folders.reopen");
    private int edge = 1024;

    public void init(PluginContext context) { this.context = context; }
    public void enable() {
        out = Path.of(System.getProperty("turboism.home"), "state", "atlas-folders");
        Thread worker = new Thread(this::run, "folder-atlas-host-probe");
        worker.setDaemon(true); worker.start();
        context.logger().info("FOLDER_ATLAS_PROBE_READY");
    }
    public void disable() {}
    public void shutdown() {}

    private void run() {
        try {
            Files.createDirectories(out);
            await("runner trigger", 240, () -> Files.isRegularFile(out.resolve("go.flag")));
            await("active model", 180, () -> host(() -> context.cubism().model().isAvailable()
                    && !context.cubism().model().active().drawables().all().isEmpty()));
            if (reopen) {
                var snapshot = snapshot();
                String expected = Files.readString(out.resolve("expected.sha256")).strip();
                check("reopen.plan", digest(snapshot.currentPlan()).equals(expected), digest(snapshot.currentPlan()));
                geometry(snapshot);
            } else {
                ensureAtlas();
                setupFolders();
                var before = snapshot();
                int largest = before.items().stream().mapToInt(i -> Math.max(i.width(), i.height()) + 6).max().orElseThrow();
                while (edge < largest && edge < 8192) edge *= 2;
                check("source.imagesFitMaximum", largest <= edge, "largest=" + largest + " edge=" + edge);
                evidence.setProperty("maxEdge", String.valueOf(edge));
                String baseline = digest(before.currentPlan());
                settings(true, true);
                check("cancel.unchanged", baseline.equals(digest(snapshot().currentPlan())), "baseline=" + baseline);
                settings(false, true);
                await("folder layout applied", 180, () -> snapshot().currentPlan().pageNames().stream().anyMatch(n -> n.contains("Host")));
                var after = snapshot();
                geometry(after);
                folderOwnership(after);
                String arranged = digest(after.currentPlan());
                check("layout.changed", !baseline.equals(arranged), arranged);
                shortcut(KeyEvent.VK_Z);
                await("undo", 45, () -> baseline.equals(digest(snapshot().currentPlan())));
                check("undo.exact", true, baseline);
                shortcut(KeyEvent.VK_Y);
                await("redo", 45, () -> arranged.equals(digest(snapshot().currentPlan())));
                check("redo.exact", true, arranged);
                // Exercise fixed-size setting through the same production modal.
                settings(false, false);
                await("fixed-size layout", 180, () -> {
                    var p = snapshot().currentPlan();
                    return java.util.stream.IntStream.range(0, p.pageCount()).allMatch(i -> p.pageSize(i).width() == edge && p.pageSize(i).height() == edge);
                });
                check("fixed.squarePages", true, "edge=" + edge);
                geometry(snapshot());
                // Restore the independently sized plan for persistence verification.
                if (!arranged.equals(digest(snapshot().currentPlan()))) {
                    shortcut(KeyEvent.VK_Z);
                    await("restore automatic sizes", 45, () -> arranged.equals(digest(snapshot().currentPlan())));
                }
                save(arranged);
            }
            evidence.setProperty("status", "PASS");
        } catch (Throwable error) {
            evidence.setProperty("status", "FAIL");
            evidence.setProperty("failure", error.toString());
            context.logger().error("FOLDER_ATLAS_FAILURE", error);
            try { Files.writeString(out.resolve("windows.txt"), host(FolderAtlasHostProbe::windows)); } catch (Exception ignored) {}
        }
        try (var writer = Files.newBufferedWriter(out.resolve("result.properties"))) {
            evidence.store(writer, "Folder atlas exact-host evidence");
        } catch (Exception error) { context.logger().error("FOLDER_ATLAS_RESULT_WRITE_FAILED", error); }
        context.logger().info("FOLDER_ATLAS_RESULT status=" + evidence.getProperty("status"));
        // Ordinary JVM shutdown; the queue independently verifies exact process containment.
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        Runtime.getRuntime().exit(0);
    }

    private void setupFolders() throws Exception {
        host(() -> {
            var model = context.cubism().model().active();
            var head = model.parts().create("HostHead", null, -1);
            var eyes = model.parts().create("HostEyes", head, -1);
            var left = model.parts().create("HostLeft", eyes, -1);
            var right = model.parts().create("HostRight", eyes, -1);
            var body = model.parts().create("HostBody", null, -1);
            var meshes = new ArrayList<>(model.drawables().all());
            meshes.sort(Comparator.comparing(m -> m.id().value()));
            for (int i = 0; i < meshes.size(); i++) meshes.get(i).setParent(
                    i < meshes.size() / 3 ? left : i < meshes.size() * 2 / 3 ? right : body, -1);
            evidence.setProperty("fixture.meshCount", String.valueOf(meshes.size()));
            return null;
        });
        check("setup.realPartHierarchy", true, "HostHead/HostEyes/{HostLeft,HostRight}, HostBody");
    }

    private void ensureAtlas() throws Exception {
        if (host(() -> context.cubism().textureAtlasLayouts().current().isPresent())) return;
        menu(text -> text.contains("编辑纹理集") || text.contains("Edit Texture Atlas") || text.equals("Texture Atlas"));
        await("atlas editor", 120, () -> {
            host(() -> {
                for (Window w : Window.getWindows()) if (w.isVisible() && w instanceof JDialog d &&
                        (d.getTitle().contains("新纹理集") || d.getTitle().startsWith("New"))) {
                    click(d, "OK", "确定");
                }
                return null;
            });
            return host(() -> context.cubism().textureAtlasLayouts().current().isPresent());
        });
        host(() -> {
            for (Window w : Window.getWindows()) if (w.isVisible() && title(w).contains("编辑纹理集")) click(w, "OK", "确定");
            return null;
        });
        Thread.sleep(1500);
    }

    private void settings(boolean cancel, boolean automatic) throws Exception {
        menu(text -> text.equals("按部件文件夹一键排版") || text.equals("Arrange Atlas by Part Folders"));
        await("production settings modal", 45, () -> host(() -> settingsWindow() != null));
        host(() -> {
            JDialog dialog = settingsWindow();
            check("dialog.modal", dialog.isModal(), dialog.getTitle());
            var combos = components(dialog).stream().filter(c -> c instanceof JComboBox<?>).map(c -> (JComboBox<?>) c).toList();
            check("dialog.maxSizeChoices", combos.get(0).getItemCount() == 4,
                    java.util.stream.IntStream.range(0, combos.get(0).getItemCount()).mapToObj(i -> combos.get(0).getItemAt(i).toString()).toList().toString());
            check("dialog.defaultAlgorithm", combos.get(1).getSelectedIndex() == 0, combos.get(1).getSelectedItem().toString());
            combos.get(1).setSelectedIndex(2);
            check("dialog.nativeDisabled", combos.get(1).getSelectedIndex() == 0, "selection returns to MaxRects");
            combos.get(0).setSelectedIndex(switch(edge) { case 1024 -> 0; case 2048 -> 1; case 4096 -> 2; default -> 3; });
            combos.get(1).setSelectedIndex(algorithm.equals("dalsoo") ? 1 : 0);
            check("dialog.selectedAlgorithm", combos.get(1).getSelectedIndex() == (algorithm.equals("dalsoo") ? 1 : 0), algorithm);
            for (var c : components(dialog)) if (c instanceof JCheckBox box) box.setSelected(automatic);
            BufferedImage image = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); dialog.paint(graphics); graphics.dispose();
            ImageIO.write(image, "png", out.resolve(cancel ? "modal-cancel.png" : automatic ? "modal-automatic.png" : "modal-fixed.png").toFile());
            click(dialog, cancel ? new String[] {"取消", "Cancel"} : new String[] {"开始排版", "Arrange"});
            return null;
        });
        await("settings close", 180, () -> host(() -> settingsWindow() == null));
    }

    private void geometry(TextureAtlasLayoutSnapshot snapshot) throws Exception {
        var plan = snapshot.currentPlan();
        check("layout.complete", new HashSet<>(plan.placements().stream().map(TextureAtlasPlacement::textureId).toList())
                .equals(new HashSet<>(snapshot.items().stream().map(TextureAtlasLayoutItem::textureId).toList())), "placements=" + plan.placements().size());
        check("layout.multiplePages", plan.pageCount() > 1, "pages=" + plan.pageCount());
        Map<String, TextureAtlasLayoutItem> items = new HashMap<>();
        for (var i : snapshot.items()) items.put(i.textureId(), i);
        for (var p : plan.placements()) {
            var size = plan.pageSize(p.pageIndex()); var item = items.get(p.textureId());
            check("geometry." + p.textureId(), !p.rotated() && p.width() == item.width() && p.height() == item.height()
                    && p.x() >= 3 && p.y() >= 3 && p.x() + p.width() + 3 <= size.width()
                    && p.y() + p.height() + 3 <= size.height(), p.toString());
        }
        for (int i = 0; i < plan.placements().size(); i++) for (int j = i + 1; j < plan.placements().size(); j++) {
            var a = plan.placements().get(i); var b = plan.placements().get(j);
            if (a.pageIndex() != b.pageIndex()) continue;
            if ((long)a.x() < (long)b.x()+b.width()+6 && (long)a.x()+a.width()+6 > b.x()
                    && (long)a.y() < (long)b.y()+b.height()+6 && (long)a.y()+a.height()+6 > b.y()) throw new AssertionError("overlap/padding");
        }
        Files.writeString(out.resolve("readback-plan.txt"), plan.toString());
        evidence.setProperty("pageCount", String.valueOf(plan.pageCount()));
        evidence.setProperty("pageNames", plan.pageNames().toString());
        evidence.setProperty("pageSizes", java.util.stream.IntStream.range(0, plan.pageCount()).mapToObj(i -> plan.pageSize(i).toString()).toList().toString());
    }

    private void folderOwnership(TextureAtlasLayoutSnapshot snapshot) throws Exception {
        var model = host(() -> context.cubism().model().active());
        var ownership = host(() -> {
            Map<String, String> result = new HashMap<>();
            Map<String, String> meshFolders = new HashMap<>();
            for (var mesh : model.drawables().all()) meshFolders.put(mesh.id().value(), model.parts().find(mesh.parentPartId().orElseThrow()).name());
            for (var relation : model.textures().relations().modelImages()) {
                var folders = relation.usingArtMeshIds().stream().map(id -> meshFolders.get(id.value())).distinct().toList();
                if (folders.size() == 1) result.put(relation.id().value(), folders.get(0));
            }
            return result;
        });
        for (var p : snapshot.currentPlan().placements()) {
            String folder = ownership.get(p.textureId());
            if (folder == null) continue;
            String name = snapshot.currentPlan().pageNames().get(p.pageIndex());
            boolean valid = name.contains(folder) || (!folder.equals("HostBody") && (name.contains("HostHead") || name.contains("HostEyes")));
            check("folder." + p.textureId(), valid, folder + " -> " + name);
        }
        var names = snapshot.currentPlan().pageNames();
        check("folder.recursiveSplit", names.stream().anyMatch(n -> n.contains("HostLeft") || n.contains("HostRight")), names.toString());
        int body = -1;
        for (int i = 0; i < names.size(); i++) if (names.get(i).contains("HostBody")) { body = i; break; }
        check("folder.pageOrder", body > 0 && names.subList(body, names.size()).stream().allMatch(n -> n.contains("HostBody")), names.toString());
    }

    private void save(String planDigest) throws Exception {
        Path fixture = Path.of(System.getProperty("turboism.validation.folders.fixture"));
        byte[] original = Files.readAllBytes(fixture);
        shortcut(KeyEvent.VK_S);
        await("native save", 120, () -> !Arrays.equals(original, Files.readAllBytes(fixture)));
        Thread.sleep(2000);
        Path saved = out.resolve("saved.cmo3");
        Files.copy(fixture, saved, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(out.resolve("expected.sha256"), planDigest);
        Files.write(fixture, original);
        check("save.preserved", Files.size(saved) > 0, saved.toString());
        check("save.fixtureRestored", Arrays.equals(original, Files.readAllBytes(fixture)), "source copy unchanged");
        evidence.setProperty("savedSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(saved))));
        evidence.setProperty("planSha256", planDigest);
    }

    private TextureAtlasLayoutSnapshot snapshot() throws Exception {
        return host(() -> context.cubism().textureAtlasLayouts().current().orElseThrow());
    }
    private static String digest(TextureAtlasLayoutPlan plan) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plan.toString().getBytes(StandardCharsets.UTF_8)));
    }
    private void check(String key, boolean ok, String detail) {
        evidence.setProperty(key, (ok ? "PASS " : "FAIL ") + detail);
        if (!ok) throw new AssertionError(key + ": " + detail);
    }
    private void menu(Predicate<String> label) throws Exception {
        boolean found = host(() -> {
            for (Window window : Window.getWindows()) if (window.isVisible()) {
                JMenuBar bar = window instanceof JFrame f ? f.getJMenuBar() : window instanceof JDialog d ? d.getJMenuBar() : null;
                if (bar == null) continue;
                for (var c : components(bar)) if (c instanceof JMenuItem item && !(item instanceof JMenu)
                        && item.getText() != null && label.test(item.getText()) && item.isEnabled()) {
                    SwingUtilities.invokeLater(() -> item.doClick(0)); return true;
                }
            }
            return false;
        });
        if (!found) throw new IllegalStateException("menu not found: " + host(FolderAtlasHostProbe::windows));
    }
    private void shortcut(int key) throws Exception {
        menu(text -> false, key);
        Thread.sleep(500);
    }
    private void menu(Predicate<String> unused, int key) throws Exception {
        boolean found = host(() -> {
            for (var frame : Frame.getFrames()) if (frame instanceof JFrame f && frame.isVisible() && f.getJMenuBar() != null)
                for (var c : components(f.getJMenuBar())) if (c instanceof JMenuItem item && item.getAccelerator() != null
                        && item.getAccelerator().getKeyCode() == key && (item.getAccelerator().getModifiers() & InputEvent.CTRL_DOWN_MASK) != 0
                        && item.isEnabled()) { SwingUtilities.invokeLater(() -> item.doClick(0)); return true; }
            return false;
        });
        if (!found) throw new IllegalStateException("shortcut unavailable " + KeyEvent.getKeyText(key));
    }
    private static void click(Container root, String... labels) {
        for (var c : components(root)) if (c instanceof AbstractButton b && b.isEnabled() && Arrays.asList(labels).contains(b.getText())) {
            SwingUtilities.invokeLater(() -> b.doClick(0)); return;
        }
        throw new IllegalStateException("button absent " + Arrays.toString(labels));
    }
    private static List<Component> components(Container root) {
        List<Component> result = new ArrayList<>();
        if (root instanceof JMenu menu) {
            for (var c : menu.getMenuComponents()) { result.add(c); if (c instanceof Container v) result.addAll(components(v)); }
        } else for (var c : root.getComponents()) { result.add(c); if (c instanceof Container v) result.addAll(components(v)); }
        return result;
    }
    private static JDialog settingsWindow() {
        for (Window w : Window.getWindows()) if (w.isVisible() && w instanceof JDialog d &&
                (d.getTitle().equals("按部件文件夹一键排版") || d.getTitle().equals("Arrange Atlas by Part Folders"))) return d;
        return null;
    }
    private static String title(Window w) { return w instanceof Frame f ? f.getTitle() : w instanceof Dialog d ? d.getTitle() : ""; }
    private static String windows() { return Arrays.stream(Window.getWindows()).filter(Window::isVisible).map(w -> title(w) + ":" + w.getClass().getName()).toList().toString(); }
    private interface Condition { boolean test() throws Exception; }
    private static void await(String label, int seconds, Condition check) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < until) { if (check.test()) return; Thread.sleep(500); }
        throw new IllegalStateException("timeout: " + label);
    }
    private static <T> T host(Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return work.call();
        FutureTask<T> task = new FutureTask<>(work); SwingUtilities.invokeLater(task);
        return task.get(60, TimeUnit.SECONDS);
    }
}
