package dev.turboism.plugin.physicseditor;

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

/**
 * Disk-backed GIF sequence encoding and bounded Gifsicle optimization.
 *
 * <p>Both halves are plugin-owned policy: the frame delay distribution, the color/resize ladder and
 * the bounded wait on the external optimizer are feature decisions, not framework ones. The class
 * reports failures as {@link IOException} so its caller owns the user-visible message.</p>
 */
final class GifFrameEncoder implements AutoCloseable {

    /** Optimization attempts before the encoder gives up on the requested size limit. */
    private static final int MAX_OPTIMIZATION_STEPS = 24;

    /** Bound on one external optimizer run; a hung Gifsicle must not wedge the recording thread. */
    private static final long OPTIMIZER_TIMEOUT_SECONDS = 60L;

    private final ImageWriter writer =
            ImageIO.getImageWritersByFormatName("gif").next();
    private final ImageOutputStream stream;
    private final int fps;
    private int frames;

    GifFrameEncoder(final Path destination, final int fps) throws IOException {
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
        if (maxBytes == Long.MAX_VALUE) {
            // Optimization is off: the operator asked for this pixel size with no size ceiling, so
            // Gifsicle is not part of this export at all. Shelling out anyway made a machine without
            // Gifsicle unable to export any GIF, even though nothing needed optimizing.
            rescale(source, output, width, height, frameCount);
            return;
        }
        // Every attempt uses the original recording, avoiding cumulative quantization/resizing damage.
        for (int step = 0; step < MAX_OPTIMIZATION_STEPS; step++) {
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

    /**
     * Signals that the configured optimizer executable could not be started.
     *
     * <p>The caller turns this into actionable guidance, because "CreateProcess error=2" tells an
     * operator nothing and an unoptimized export needs no optimizer at all.</p>
     */
    static final class OptimizerUnavailableException extends IOException {
        private final String executable;

        OptimizerUnavailableException(final String executable, final IOException cause) {
            super("Gifsicle is not available: " + executable, cause);
            this.executable = executable;
        }

        String executable() {
            return executable;
        }
    }

    /**
     * Rewrites the recording at the requested pixel size without an external optimizer.
     *
     * <p>This is the unoptimized export path: the frames are resampled in-process, the delay
     * distribution and the loop extension are preserved, and no Gifsicle process is involved.</p>
     */
    private static void rescale(
            final Path source, final Path output, final int width, final int height, final int frameCount)
            throws IOException {
        try (var input = ImageIO.createImageInputStream(source.toFile())) {
            final var reader = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(input);
                final ImageWriter writer =
                        ImageIO.getImageWritersByFormatName("gif").next();
                try (var stream = ImageIO.createImageOutputStream(output.toFile())) {
                    writer.setOutput(stream);
                    writer.prepareWriteSequence(null);
                    try {
                        for (int frame = 0; frame < frameCount; frame++) {
                            final BufferedImage image = reader.read(frame);
                            final BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                            final var graphics = scaled.createGraphics();
                            try {
                                graphics.setRenderingHint(
                                        java.awt.RenderingHints.KEY_INTERPOLATION,
                                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                                graphics.drawImage(image, 0, 0, width, height, null);
                            } finally {
                                graphics.dispose();
                            }
                            final var metadata = writer.getDefaultImageMetadata(
                                    javax.imageio.ImageTypeSpecifier.createFromRenderedImage(scaled),
                                    writer.getDefaultWriteParam());
                            final String format = metadata.getNativeMetadataFormatName();
                            final var root = (IIOMetadataNode) metadata.getAsTree(format);
                            final var control = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension")
                                    .item(0);
                            final var sourceMetadata = (IIOMetadataNode)
                                    reader.getImageMetadata(frame).getAsTree(format);
                            final var sourceControl = (IIOMetadataNode) sourceMetadata
                                    .getElementsByTagName("GraphicControlExtension")
                                    .item(0);
                            control.setAttribute("delayTime", sourceControl.getAttribute("delayTime"));
                            control.setAttribute("disposalMethod", "none");
                            if (frame == 0) {
                                final var extensions = new IIOMetadataNode("ApplicationExtensions");
                                final var loop = new IIOMetadataNode("ApplicationExtension");
                                loop.setAttribute("applicationID", "NETSCAPE");
                                loop.setAttribute("authenticationCode", "2.0");
                                loop.setUserObject(new byte[] {1, 0, 0});
                                extensions.appendChild(loop);
                                root.appendChild(extensions);
                            }
                            metadata.setFromTree(format, root);
                            writer.writeToSequence(new IIOImage(scaled, null, metadata), writer.getDefaultWriteParam());
                        }
                    } finally {
                        writer.endWriteSequence();
                        writer.dispose();
                    }
                }
            } finally {
                reader.dispose();
            }
        }
    }

    static void verifyExecutable(final String executable) throws IOException, InterruptedException {
        run(List.of(executable, "--version"));
    }

    private static void run(final List<String> command) throws IOException, InterruptedException {
        final Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException missing) {
            // The host reports a bare "CreateProcess error=2" here; name the actual problem instead.
            throw new OptimizerUnavailableException(command.get(0), missing);
        }
        try {
            if (!process.waitFor(OPTIMIZER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                throw new IOException("Gifsicle timed out");
            if (process.exitValue() != 0) throw new IOException("Gifsicle failed (exit " + process.exitValue() + ")");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
