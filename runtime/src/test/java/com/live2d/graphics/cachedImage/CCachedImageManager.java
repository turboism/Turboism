package com.live2d.graphics.cachedImage;

import com.live2d.graphics.CImageResource;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only stand-in for the host's {@code com.live2d.graphics.cachedImage.CCachedImageManager}.
 * Counts registrations so tests can prove the register tail still runs on a skipped rebuild.
 */
public class CCachedImageManager {
    private final AtomicInteger registrations = new AtomicInteger();

    public void a(final CImageResource resource) {
        registrations.incrementAndGet();
    }

    public int registrations() {
        return registrations.get();
    }
}
