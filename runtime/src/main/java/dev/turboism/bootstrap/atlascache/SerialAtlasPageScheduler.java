package dev.turboism.bootstrap.atlascache;

import java.util.List;
import java.util.Objects;

/**
 * The default page scheduler: runs each page's rebuild on the caller's thread in list
 * order — identical ordering and failure semantics to the host's own per-atlas loop.
 */
final class SerialAtlasPageScheduler implements AtlasPageScheduler {

    @Override
    public void runPages(final List<? extends Runnable> pageRebuilds) {
        for (final Runnable rebuild : Objects.requireNonNull(pageRebuilds, "pageRebuilds")) {
            rebuild.run();
        }
    }
}
