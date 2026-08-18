package me.kzheart.klib.compat;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 与服务器 API 无关的标准化 Minecraft 服务器版本。 */
public final class ServerVersion implements Comparable<ServerVersion> {
    private static final Pattern VERSION = Pattern.compile("(?:^|[^0-9])(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private final int major;
    private final int minor;
    private final int patch;

    private ServerVersion(int major, int minor, int patch) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version parts must be non-negative");
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public static ServerVersion of(int major, int minor, int patch) {
        return new ServerVersion(major, minor, patch);
    }

    public static ServerVersion parse(String value) {
        Objects.requireNonNull(value, "value");
        Matcher matcher = VERSION.matcher(value);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Cannot parse server version: " + value);
        }
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), patch);
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    @Override
    public int compareTo(ServerVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) {
            result = Integer.compare(minor, other.minor);
        }
        return result == 0 ? Integer.compare(patch, other.patch) : result;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ServerVersion)) {
            return false;
        }
        ServerVersion that = (ServerVersion) other;
        return major == that.major && minor == that.minor && patch == that.patch;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * major + minor) + patch;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
