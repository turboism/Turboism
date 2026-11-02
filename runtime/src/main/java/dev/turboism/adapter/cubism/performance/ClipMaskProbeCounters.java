package dev.turboism.adapter.cubism.performance;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-frame counters for the measurement-only clipping-mask probe.
 *
 * <p>The render thread writes; a reporting thread reads {@link #snapshot()}. Totals
 * are accumulated as events arrive, so a mid-frame reading is monotone and never
 * loses work; only the per-frame peaks wait for {@link #frame()}, which the woven
 * {@code CEViewContext.renderScene_exe} entry calls once per rendered scene.</p>
 *
 * <p>What the counters mean, measured on the reviewed 5.3.04 body and identical on
 * every reviewed version:</p>
 *
 * <ul>
 *   <li>{@code maskPasses} - calls of {@code GClipping/GRemovableClipping
 *       .getClippingVaildMasks()}. The render system makes exactly one such call per
 *       renderer whose {@code GClipping} passed the empty-set, {@code isRemove()} and
 *       {@code PSD_EXPORT} early-outs, so it counts real mask passes, not dispatch
 *       attempts;</li>
 *   <li>{@code distinctMaskSets} - distinct mask-entity sets per frame, summed over
 *       frames. A set is identified by an order-sensitive digest of the mask
 *       entities' identity hashes, which is exactly what decides whether two passes
 *       in one frame would render byte-identical mask content;</li>
 *   <li>{@code dedupeSavings} - {@code maskPasses - distinctMaskSets}: mask renders a
 *       same-frame dedupe could have skipped;</li>
 *   <li>{@code maskFboBinds}/{@code maskFboPixels} - pooled grayscale render textures
 *       bound during a mask pass, and the texels their {@code glClear} provisions (the
 *       clear runs without a scissor, so it covers the whole attachment);</li>
 *   <li>{@code groupPasses}/{@code groupFboBinds}/{@code groupFboPixels} - the same
 *       facts for {@code GGroupRenderer.render}'s offscreen ARGB attachment;</li>
 *   <li>{@code maskPathNanos}/{@code groupPathNanos} - wall time of mask/group
 *       dispatches that actually reached the mask/group work, excluding the early
 *       returns the render loop makes for unclipped renderers.</li>
 * </ul>
 */
final class ClipMaskProbeCounters {

    private final Object lock = new Object();

    private long frames;
    private long maskDispatches;
    private long maskPasses;
    private long maskEntities;
    private long maskBinds;
    private long maskPixels;
    private long groups;
    private long groupBinds;
    private long groupPixels;
    private long maskPathNanos;
    private long groupPathNanos;
    private long distinctMaskSets;
    private long observerFailures;

    private long frameMaskPasses;
    private long frameDistinctMaskSets;
    private long frameMaskPixels;
    private long frameGroups;
    private long peakMaskPassesPerFrame;
    private long peakDistinctMaskSetsPerFrame;
    private long peakMaskPixelsPerFrame;
    private long peakGroupPassesPerFrame;
    private final Set<Long> frameMaskSets = new HashSet<>();

    private int maskDepth;
    private int groupDepth;
    private long dispatchStarted;
    private boolean maskWorked;

    /** Flushes the current frame's peaks and starts the next frame. */
    void frame() {
        synchronized (lock) {
            frames++;
            peakMaskPassesPerFrame = Math.max(peakMaskPassesPerFrame, frameMaskPasses);
            peakDistinctMaskSetsPerFrame = Math.max(peakDistinctMaskSetsPerFrame, frameDistinctMaskSets);
            peakMaskPixelsPerFrame = Math.max(peakMaskPixelsPerFrame, frameMaskPixels);
            peakGroupPassesPerFrame = Math.max(peakGroupPassesPerFrame, frameGroups);
            frameMaskPasses = 0L;
            frameDistinctMaskSets = 0L;
            frameMaskPixels = 0L;
            frameGroups = 0L;
            frameMaskSets.clear();
        }
    }

    /** Records a mask-pass dispatch entry. */
    void enterMaskDispatch() {
        maskDepth++;
        maskWorked = false;
        dispatchStarted = System.nanoTime();
        synchronized (lock) {
            maskDispatches++;
        }
    }

    /**
     * Records a mask-pass dispatch exit, accumulating time only for dispatches that
     * reached the mask work.
     */
    void exitMaskDispatch() {
        if (maskDepth > 0) maskDepth--;
        if (!maskWorked) return;
        maskWorked = false;
        final long elapsed = Math.max(0L, System.nanoTime() - dispatchStarted);
        synchronized (lock) {
            maskPathNanos += elapsed;
        }
    }

    /**
     * Records one real mask pass.
     *
     * @param setDigest order-sensitive digest of the mask-entity identities
     * @param entities  mask entities in the set
     */
    void maskPass(final long setDigest, final int entities) {
        maskWorked = true;
        synchronized (lock) {
            maskPasses++;
            maskEntities += entities;
            frameMaskPasses++;
            if (frameMaskSets.add(setDigest)) {
                distinctMaskSets++;
                frameDistinctMaskSets++;
            }
        }
    }

    /**
     * Records a render-texture bind, classified by the pass currently in flight.
     *
     * @param width  attachment width in texels
     * @param height attachment height in texels
     */
    void bind(final int width, final int height) {
        if (maskDepth > 0) {
            synchronized (lock) {
                final long texels = (long) width * height;
                maskBinds++;
                maskPixels += texels;
                frameMaskPixels += texels;
            }
        } else if (groupDepth > 0) {
            synchronized (lock) {
                groupBinds++;
                groupPixels += (long) width * height;
            }
        }
    }

    /** Records a transparent-group render entry. */
    void enterGroupRender() {
        groupDepth++;
        dispatchStarted = System.nanoTime();
        synchronized (lock) {
            frameGroups++;
            groups++;
        }
    }

    /** Records a transparent-group render exit. */
    void exitGroupRender() {
        if (groupDepth > 0) groupDepth--;
        final long elapsed = Math.max(0L, System.nanoTime() - dispatchStarted);
        synchronized (lock) {
            groupPathNanos += elapsed;
        }
    }

    /** Counts one probe-side observation failure; the probe never changes host behaviour. */
    void fail() {
        synchronized (lock) {
            observerFailures++;
        }
    }

    /**
     * @return an immutable reading of every counter, in a stable key order
     */
    Map<String, Long> snapshot() {
        synchronized (lock) {
            final Map<String, Long> result = new LinkedHashMap<>();
            result.put("active", 1L);
            result.put("frames", frames);
            result.put("maskDispatches", maskDispatches);
            result.put("maskPasses", maskPasses);
            result.put("maskEntities", maskEntities);
            result.put("distinctMaskSets", distinctMaskSets);
            result.put("dedupeSavings", maskPasses - distinctMaskSets);
            result.put("maskPassesPerFramePeak", peakMaskPassesPerFrame);
            result.put("distinctMaskSetsPerFramePeak", peakDistinctMaskSetsPerFrame);
            result.put("maskFboBinds", maskBinds);
            result.put("maskFboPixels", maskPixels);
            result.put("maskFboPixelsPerFramePeak", peakMaskPixelsPerFrame);
            result.put("groupPasses", groups);
            result.put("groupPassesPerFramePeak", peakGroupPassesPerFrame);
            result.put("groupFboBinds", groupBinds);
            result.put("groupFboPixels", groupPixels);
            result.put("maskPathNanos", maskPathNanos);
            result.put("groupPathNanos", groupPathNanos);
            result.put("observerFailures", observerFailures);
            return java.util.Collections.unmodifiableMap(result);
        }
    }
}
