package dev.turboism.validation.atlasimage.t033;

import java.util.Arrays;

public final class T033FixtureEvents {
    public enum FaultPoint {
        NONE,
        AFTER_CLEAR,
        AFTER_SOURCE_READ,
        AFTER_TARGET_WRITE
    }

    private static FaultPoint faultPoint = FaultPoint.NONE;
    private static int clearCount;
    private static int sourceReadCount;
    private static int targetWriteCount;
    private static int faultThrowCount;
    private static int[] sourceReads = new int[16];
    private static int[] targetWrites = new int[16];

    private T033FixtureEvents() {
    }

    public static void reset(final FaultPoint nextFaultPoint) {
        faultPoint = nextFaultPoint;
        clearCount = 0;
        sourceReadCount = 0;
        targetWriteCount = 0;
        faultThrowCount = 0;
        sourceReads = new int[16];
        targetWrites = new int[16];
    }

    public static void afterClear() {
        clearCount++;
        throwIf(FaultPoint.AFTER_CLEAR);
    }

    public static void beforeSourceRead(final int index) {
        // The fixture keeps this hook immediately before the array read.
        if (index < 0) {
            throw new AssertionError("negative source index before read");
        }
    }

    public static void afterSourceRead(final int index) {
        sourceReads = append(sourceReads, sourceReadCount, index);
        sourceReadCount++;
        throwIf(FaultPoint.AFTER_SOURCE_READ);
    }

    public static void beforeTargetWrite(final int index) {
        // The fixture keeps this hook immediately before the array store.
        if (index < 0) {
            throw new AssertionError("negative target index before write");
        }
    }

    public static void afterTargetWrite(final int index) {
        targetWrites = append(targetWrites, targetWriteCount, index);
        targetWriteCount++;
        throwIf(FaultPoint.AFTER_TARGET_WRITE);
    }

    public static Snapshot snapshot() {
        return new Snapshot(
            clearCount,
            sourceReadCount,
            targetWriteCount,
            faultThrowCount,
            Arrays.copyOf(sourceReads, sourceReadCount),
            Arrays.copyOf(targetWrites, targetWriteCount)
        );
    }

    private static int[] append(final int[] values, final int size, final int value) {
        final int[] target = size == values.length
            ? Arrays.copyOf(values, values.length * 2)
            : values;
        target[size] = value;
        return target;
    }

    private static void throwIf(final FaultPoint point) {
        if (faultPoint == point) {
            faultThrowCount++;
            throw new FixtureFault(point.name());
        }
    }

    public record Snapshot(
        int clearCount,
        int sourceReadCount,
        int targetWriteCount,
        int faultThrowCount,
        int[] sourceReads,
        int[] targetWrites
    ) {
    }

    @SuppressWarnings("serial")
    static final class FixtureFault extends RuntimeException {
        FixtureFault(final String point) {
            super("fixture fault at " + point);
        }
    }
}
