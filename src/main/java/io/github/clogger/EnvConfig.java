package io.github.clogger;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Package-private helpers for reading {@code CLOGGER_*} environment variables
 * as the highest-priority configuration source. Returns {@link Optional} so
 * callers can apply overrides only when a variable is actually set — leaving
 * Logback XML values (or hard-coded defaults) intact otherwise.
 *
 * <p>All parsers silently ignore malformed values: an unparseable int or an
 * unrecognized boolean string yields {@link Optional#empty()} rather than an
 * exception, matching the existing lenient behavior the appenders use for
 * the legacy {@code CLOGGER_LINES} variable.</p>
 */
final class EnvConfig {

    private EnvConfig() {}

    /**
     * Reads {@code name} as an integer. Returns empty if the var is unset,
     * blank, unparseable, or strictly less than {@code min}.
     */
    static OptionalInt readInt(String name, int min) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return OptionalInt.empty();
        try {
            int n = Integer.parseInt(raw.trim());
            return n >= min ? OptionalInt.of(n) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /**
     * Reads {@code name} as a boolean. Accepts {@code true}/{@code false}
     * case-insensitively. Any other value (including unset) yields empty.
     */
    static Optional<Boolean> readBool(String name) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return Optional.empty();
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true")) return Optional.of(Boolean.TRUE);
        if (v.equals("false")) return Optional.of(Boolean.FALSE);
        return Optional.empty();
    }

    /**
     * Reads {@code name} as a trimmed non-blank string. Returns empty for
     * unset or whitespace-only values. Case is preserved — callers normalize
     * if they need an uppercase enum form.
     */
    static Optional<String> readString(String name) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return Optional.empty();
        return Optional.of(raw.trim());
    }
}
