package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class VerifiedHostAdapterConnectorTest {

    @Test
    void forwardsAllDescriptorTrustInputsToVerifiedFactorySeam() throws Exception {
        Path record = Path.of("records/reviewed.json");
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        ClassLoader classLoader = new ClassLoader() { };
        AtomicReference<Path> seenRecord = new AtomicReference<>();
        AtomicReference<Path> seenArtifact = new AtomicReference<>();
        AtomicReference<ClassLoader> seenClassLoader = new AtomicReference<>();
        RuntimeHostAdapters expected = RuntimeHostAdapters.safeMode();
        VerifiedHostAdapterConnector connector = new VerifiedHostAdapterConnector(
            (reviewedRecord, verifiedHostArtifact, hostClassLoader) -> {
                seenRecord.set(reviewedRecord);
                seenArtifact.set(verifiedHostArtifact);
                seenClassLoader.set(hostClassLoader);
                return expected;
            }
        );

        HostAdapterConnection connection = connector.connect(new HostInstanceDescriptor(
            "session-a",
            record,
            artifact,
            classLoader
        ));

        assertEquals(record, seenRecord.get());
        assertEquals(artifact, seenArtifact.get());
        assertSame(classLoader, seenClassLoader.get());
        assertSame(expected, connection.adapters());
    }
}
