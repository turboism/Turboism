package dev.turboism.plugin.frameanimation.slice;

import java.io.File;
import java.io.Serializable;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Natural sort order comparator for strings and files, ordering "file_2" before "file_10".
 */
public final class NaturalOrderComparator implements Comparator<String>, Serializable {

    private static final long serialVersionUID = 1L;

    public static final NaturalOrderComparator INSTANCE = new NaturalOrderComparator();

    private static final Pattern CHUNK_PATTERN = Pattern.compile("(\\d+)|(\\D+)");

    public static final Comparator<File> FILE_COMPARATOR = (f1, f2) -> INSTANCE.compare(f1.getName(), f2.getName());

    @Override
    public int compare(final String s1, final String s2) {
        if (s1 == null && s2 == null) return 0;
        if (s1 == null) return -1;
        if (s2 == null) return 1;

        final Matcher m1 = CHUNK_PATTERN.matcher(s1);
        final Matcher m2 = CHUNK_PATTERN.matcher(s2);

        while (m1.find() && m2.find()) {
            final String chunk1 = m1.group();
            final String chunk2 = m2.group();

            final boolean isNum1 = Character.isDigit(chunk1.charAt(0));
            final boolean isNum2 = Character.isDigit(chunk2.charAt(0));

            if (isNum1 && isNum2) {
                // Compare numerically
                // Trim leading zeros for numerical length comparison
                final String t1 = trimLeadingZeros(chunk1);
                final String t2 = trimLeadingZeros(chunk2);

                if (t1.length() != t2.length()) {
                    return Integer.compare(t1.length(), t2.length());
                }
                final int numCmp = t1.compareTo(t2);
                if (numCmp != 0) {
                    return numCmp;
                }
                // If equal, compare original chunks (preserving zero-padding differences)
                final int rawCmp = Integer.compare(chunk1.length(), chunk2.length());
                if (rawCmp != 0) {
                    return rawCmp;
                }
            } else {
                final int textCmp = chunk1.compareToIgnoreCase(chunk2);
                if (textCmp != 0) {
                    return textCmp;
                }
            }
        }

        return Integer.compare(s1.length(), s2.length());
    }

    private static String trimLeadingZeros(final String str) {
        int i = 0;
        while (i < str.length() - 1 && str.charAt(i) == '0') {
            i++;
        }
        return str.substring(i);
    }
}
