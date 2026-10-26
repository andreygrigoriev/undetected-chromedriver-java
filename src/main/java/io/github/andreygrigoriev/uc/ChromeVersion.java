package io.github.andreygrigoriev.uc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A Chrome version such as {@code 131.0.6778.85}. Missing trailing parts are zero. */
public record ChromeVersion(int major, int minor, int build, int patch) implements Comparable<ChromeVersion> {

    private static final Pattern FORMAT = Pattern.compile("(\\d{1,9})(?:\\.(\\d{1,9}))?(?:\\.(\\d{1,9}))?(?:\\.(\\d{1,9}))?");

    public static ChromeVersion parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Version is null");
        }
        Matcher m = FORMAT.matcher(text.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid Chrome version: '" + text + "'");
        }
        return new ChromeVersion(part(m, 1), part(m, 2), part(m, 3), part(m, 4));
    }

    private static int part(Matcher m, int group) {
        String value = m.group(group);
        return value == null ? 0 : Integer.parseInt(value);
    }

    @Override
    public int compareTo(ChromeVersion other) {
        int c = Integer.compare(major, other.major);
        if (c == 0) {
            c = Integer.compare(minor, other.minor);
        }
        if (c == 0) {
            c = Integer.compare(build, other.build);
        }
        if (c == 0) {
            c = Integer.compare(patch, other.patch);
        }
        return c;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + build + "." + patch;
    }
}
