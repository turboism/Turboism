package dev.turboism.plugin.acp;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;

/** Test seam for one full-duplex ACP JSONL transport. */
interface AcpTransport extends AutoCloseable {

    InputStream stdout();

    InputStream stderr();

    OutputStream stdin();

    boolean isAlive();

    void terminate(Duration grace);

    @Override
    void close();
}
