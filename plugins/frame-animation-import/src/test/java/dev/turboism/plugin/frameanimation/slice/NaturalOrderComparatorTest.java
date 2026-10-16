package dev.turboism.plugin.frameanimation.slice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NaturalOrderComparatorTest {

    @Test
    void sortsNumericalChunksCorrectly() {
        final List<String> input =
                List.of("frame_10.png", "frame_1.png", "frame_2.png", "frame_20.png", "frame_3.png", "frame_7.png");
        final List<String> sorted = new ArrayList<>(input);
        sorted.sort(NaturalOrderComparator.INSTANCE);

        assertEquals(
                List.of("frame_1.png", "frame_2.png", "frame_3.png", "frame_7.png", "frame_10.png", "frame_20.png"),
                sorted);
    }

    @Test
    void sortsFilesWithDifferentPrefixes() {
        final List<File> files = List.of(
                new File("anim_2.png"), new File("anim_1.png"), new File("anim_100.png"), new File("anim_10.png"));
        final List<File> sorted = new ArrayList<>(files);
        sorted.sort(NaturalOrderComparator.FILE_COMPARATOR);

        assertEquals("anim_1.png", sorted.get(0).getName());
        assertEquals("anim_2.png", sorted.get(1).getName());
        assertEquals("anim_10.png", sorted.get(2).getName());
        assertEquals("anim_100.png", sorted.get(3).getName());
    }

    @Test
    void handlesZeroPadding() {
        // frame_01 vs frame_02 -> 01 < 02
        assertTrue(NaturalOrderComparator.INSTANCE.compare("frame_01.png", "frame_02.png") < 0);
        // frame_1 vs frame_01 -> chunk1 has length 1, chunk2 has length 2; shorter representation sorts first
        assertTrue(NaturalOrderComparator.INSTANCE.compare("frame_1.png", "frame_01.png") < 0);
    }
}
