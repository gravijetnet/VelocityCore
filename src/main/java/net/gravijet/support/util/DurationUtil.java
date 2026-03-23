package net.gravijet.support.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationUtil {
    private static final Pattern PATTERN = Pattern.compile("(\\d+)([dhms])");
    private DurationUtil() {}

    /** Returns millis. Long.MAX_VALUE = permanent. -1 = invalid. */
    public static long parse(String input) {
        if (input == null || input.isBlank()) return -1L;
        String s = input.toLowerCase().trim();
        if (s.equals("perm") || s.equals("permanent") || s.equals("forever")) return Long.MAX_VALUE;
        Matcher m = PATTERN.matcher(s);
        long total = 0;
        boolean found = false;
        while (m.find()) {
            found = true;
            long v = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "d" -> total += v * 86_400_000L;
                case "h" -> total += v * 3_600_000L;
                case "m" -> total += v * 60_000L;
                case "s" -> total += v * 1_000L;
            }
        }
        return found ? total : -1L;
    }

    public static String format(long millis) {
        if (millis == Long.MAX_VALUE) return "permanent";
        if (millis <= 0) return "0s";
        long d = millis / 86_400_000L; millis %= 86_400_000L;
        long h = millis / 3_600_000L;  millis %= 3_600_000L;
        long m = millis / 60_000L;     millis %= 60_000L;
        long s = millis / 1_000L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d");
        if (h > 0) sb.append(h).append("h");
        if (m > 0) sb.append(m).append("m");
        if (s > 0) sb.append(s).append("s");
        return sb.isEmpty() ? "0s" : sb.toString();
    }
}
