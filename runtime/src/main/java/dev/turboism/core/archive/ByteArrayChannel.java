package dev.turboism.core.archive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;

/** Read-only {@link SeekableByteChannel} over an in-memory archive image. */
final class ByteArrayChannel implements SeekableByteChannel {
    private final byte[] bytes;
    private long position;
    private boolean open = true;

    ByteArrayChannel(final byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public int read(final ByteBuffer target) throws IOException {
        if (!open) throw new ClosedChannelException();
        if (position >= bytes.length) return -1;
        final int length = (int) Math.min(target.remaining(), bytes.length - position);
        target.put(bytes, (int) position, length);
        position += length;
        return length;
    }

    @Override
    public int write(final ByteBuffer source) throws IOException {
        if (!open) throw new ClosedChannelException();
        throw new IOException("read-only channel");
    }

    @Override
    public long position() throws IOException {
        if (!open) throw new ClosedChannelException();
        return position;
    }

    @Override
    public SeekableByteChannel position(final long next) throws IOException {
        if (!open) throw new ClosedChannelException();
        if (next < 0) throw new IllegalArgumentException("negative position");
        position = next;
        return this;
    }

    @Override
    public long size() throws IOException {
        if (!open) throw new ClosedChannelException();
        return bytes.length;
    }

    @Override
    public SeekableByteChannel truncate(final long size) throws IOException {
        if (!open) throw new ClosedChannelException();
        throw new IOException("read-only channel");
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }
}
