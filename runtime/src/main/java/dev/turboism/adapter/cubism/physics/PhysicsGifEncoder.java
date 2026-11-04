package dev.turboism.adapter.cubism.physics;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/** Disk-backed GIF sequence encoding and bounded Gifsicle optimization. */
final class PhysicsGifEncoder implements AutoCloseable {
    private final ImageWriter writer =
            ImageIO.getImageWritersByFormatName("gif").next();
    private final ImageOutputStream stream;
    private final int fps;
    private int frames;

    PhysicsGifEncoder(final Path destination, final int fps) throws IOException {
        this.fps = fps;
        stream = ImageIO.createImageOutputStream(destination.toFile());
        writer.setOutput(stream);
        writer.prepareWriteSequence(null);
    }

    void append(final BufferedImage image) throws IOException {
        final var metadata = writer.getDefaultImageMetadata(
                javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image), writer.getDefaultWriteParam());
        final String format = metadata.getNativeMetadataFormatName();
        final var root = (IIOMetadataNode) metadata.getAsTree(format);
        final var control = (IIOMetadataNode)
                root.getElementsByTagName("GraphicControlExtension").item(0);
        control.setAttribute("disposalMethod", "none");
        // GIF stores centiseconds. Distribute rounding so 30 FPS remains 3 seconds per 90 frames.
        control.setAttribute("delayTime", Integer.toString((int)
                (Math.round((frames + 1) * 100.0 / fps) - Math.round(frames * 100.0 / fps))));
        if (frames == 0) {
            final var extensions = new IIOMetadataNode("ApplicationExtensions");
            final var loop = new IIOMetadataNode("ApplicationExtension");
            loop.setAttribute("applicationID", "NETSCAPE");
            loop.setAttribute("authenticationCode", "2.0");
            loop.setUserObject(new byte[] {1, 0, 0});
            extensions.appendChild(loop);
            root.appendChild(extensions);
        }
        metadata.setFromTree(format, root);
        writer.writeToSequence(new IIOImage(image, null, metadata), writer.getDefaultWriteParam());
        frames++;
    }

    @Override
    public void close() throws IOException {
        try {
            writer.endWriteSequence();
        } finally {
            writer.dispose();
            stream.close();
        }
    }

    static void optimize(
            final String executable,
            final Path source,
            final Path output,
            final long maxBytes,
            final int width,
            final int height,
            final int frameCount)
            throws IOException, InterruptedException {
        // Every attempt uses the original recording, avoiding cumulative quantization/resizing damage.
        for (int step = 0; step < 24; step++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            final double scale = step < 3 ? 1 : Math.pow(0.8, step - 2);
            final int colors = step == 0 ? 256 : step == 1 ? 128 : step < 10 ? 64 : 16;
            final List<String> command = List.of(
                    executable,
                    "--careful",
                    "--unoptimize",
                    "--optimize=3",
                    "--optimize=keep-empty",
                    "--colors=" + colors,
                    "--resize",
                    Math.max(1, (int) (width * scale)) + "x" + Math.max(1, (int) (height * scale)),
                    "--output",
                    output.toString(),
                    source.toString());
            run(command);
            if (Files.size(output) <= maxBytes) {
                try (var input = ImageIO.createImageInputStream(output.toFile())) {
                    final var reader =
                            ImageIO.getImageReadersByFormatName("gif").next();
                    try {
                        reader.setInput(input);
                        if (reader.getNumImages(true) != frameCount) throw new IOException("GIF frame count changed");
                    } finally {
                        reader.dispose();
                    }
                }
                return;
            }
        }
        throw new IOException("GIF cannot fit the requested size limit; increase the limit or reduce frame count.");
    }

    static void verifyExecutable(final String executable) throws IOException, InterruptedException {
        run(List.of(executable, "--version"));
    }

    private static void run(final List<String> command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) throw new IOException("Gifsicle timed out");
            if (process.exitValue() != 0) throw new IOException("Gifsicle failed (exit " + process.exitValue() + ")");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
