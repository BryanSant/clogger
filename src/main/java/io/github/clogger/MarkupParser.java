package io.github.clogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates a Rich-inspired markup subset into ANSI/OSC escape sequences.
 *
 * <p>Supported tags:</p>
 * <ul>
 *   <li>Styles: {@code [bold]}, {@code [italic]}, {@code [dim]},
 *       {@code [underline]}, {@code [strike]}</li>
 *   <li>Named colors: {@code [red]}, {@code [blue]}, {@code [orange]}, …</li>
 *   <li>Hex colors: {@code [#fff]} or {@code [#ff77ac]}</li>
 *   <li>Hyperlinks: {@code [link https://example.com]}</li>
 * </ul>
 *
 * <p>All tags close with {@code [/]}, which pops the most recent open tag —
 * tags nest. Unrecognized tag names, hex strings that don't match
 * {@code #rgb} or {@code #rrggbb}, malformed brackets, and stray {@code [/]}
 * are not errors: the offending {@code '['} is emitted as literal text and
 * parsing continues from the next character.</p>
 *
 * <p>{@code defaultSgr} is the ANSI sequence that represents the caller's
 * "current" foreground color/style at the point where the markup begins. On
 * every {@code [/]}, the parser emits {@code RESET}, an OSC 8 link-close,
 * then {@code defaultSgr} and replays any tags still on the stack — so a
 * closing tag fully restores the outer context (line color + age dim)
 * regardless of what was inside.</p>
 *
 * <p>An optional {@link ColorTransform} intercepts named- and hex-color tags
 * so the emitted RGB can be adjusted to match the outer context (e.g.,
 * age-based dimming). Without it, color tags emit at full saturation
 * regardless of the line's age.</p>
 */
final class MarkupParser {

    /** Transforms a markup color's RGB triple into the ANSI escape to emit. */
    @FunctionalInterface
    interface ColorTransform {
        String toAnsi(int r, int g, int b);
    }

    private static final ColorTransform DEFAULT_TRANSFORM = MarkupParser::ansiFg;

    private static final String RESET      = "\033[0m";
    private static final String LINK_CLOSE = "\033]8;;\033\\";

    private static final Map<String, int[]> COLORS = new HashMap<>();
    static {
        COLORS.put("black",          new int[]{  0,   0,   0});
        COLORS.put("red",            new int[]{220,  60,  60});
        COLORS.put("green",          new int[]{ 70, 200,  90});
        COLORS.put("yellow",         new int[]{230, 210,  80});
        COLORS.put("blue",           new int[]{ 80, 130, 240});
        COLORS.put("magenta",        new int[]{220,  80, 200});
        COLORS.put("cyan",           new int[]{ 60, 200, 220});
        COLORS.put("white",          new int[]{235, 235, 235});
        COLORS.put("bright_black",   new int[]{127, 127, 127});
        COLORS.put("bright_red",     new int[]{255,  85,  85});
        COLORS.put("bright_green",   new int[]{ 85, 255,  85});
        COLORS.put("bright_yellow",  new int[]{255, 255,  85});
        COLORS.put("bright_blue",    new int[]{ 92, 138, 255});
        COLORS.put("bright_magenta", new int[]{255, 105, 235});
        COLORS.put("bright_cyan",    new int[]{ 85, 255, 255});
        COLORS.put("bright_white",   new int[]{255, 255, 255});

        COLORS.put("grey",   new int[]{128, 128, 128});
        COLORS.put("gray",   new int[]{128, 128, 128});
        COLORS.put("orange", new int[]{255, 165,   0});
        COLORS.put("pink",   new int[]{255, 175, 200});
        COLORS.put("purple", new int[]{160,  90, 220});
        COLORS.put("brown",  new int[]{165,  90,  45});
    }

    private MarkupParser() {}

    static String parse(String input, String defaultSgr) {
        return parse(input, defaultSgr, DEFAULT_TRANSFORM);
    }

    static String parse(String input, String defaultSgr, ColorTransform transform) {
        if (input == null || input.indexOf('[') < 0) return input;
        if (transform == null) transform = DEFAULT_TRANSFORM;

        StringBuilder out = new StringBuilder(input.length() + 16);
        List<String> stack = new ArrayList<>();
        int i = 0, n = input.length();

        while (i < n) {
            char c = input.charAt(i);
            if (c != '[') {
                out.append(c);
                i++;
                continue;
            }

            int end = input.indexOf(']', i + 1);
            if (end < 0) {
                out.append('[');
                i++;
                continue;
            }

            String tag = input.substring(i + 1, end);

            if (tag.equals("/")) {
                if (stack.isEmpty()) {
                    out.append('[');
                    i++;
                    continue;
                }
                stack.remove(stack.size() - 1);
                out.append(RESET).append(LINK_CLOSE).append(defaultSgr);
                for (String t : stack) out.append(openSequence(t, transform));
                i = end + 1;
                continue;
            }

            String openSeq = openSequence(tag, transform);
            if (openSeq == null) {
                out.append('[');
                i++;
                continue;
            }
            stack.add(tag);
            out.append(openSeq);
            i = end + 1;
        }

        // Unclosed tags shouldn't leak past the message — restore the outer
        // context so the caller's trailing colors/RESET behave as written.
        if (!stack.isEmpty()) {
            out.append(RESET).append(LINK_CLOSE).append(defaultSgr);
        }
        return out.toString();
    }

    private static String openSequence(String tag, ColorTransform transform) {
        switch (tag) {
            case "bold":      return "\033[1m";
            case "dim":       return "\033[2m";
            case "italic":    return "\033[3m";
            case "underline": return "\033[4m";
            case "strike":    return "\033[9m";
            default: break;
        }

        if (tag.equals("link") || tag.startsWith("link ")) {
            String url = tag.length() > 4 ? tag.substring(5).trim() : "";
            return "\033]8;;" + url + "\033\\";
        }

        if (tag.startsWith("#")) {
            int[] rgb = parseHex(tag.substring(1));
            return rgb == null ? null : transform.toAnsi(rgb[0], rgb[1], rgb[2]);
        }

        int[] rgb = COLORS.get(tag.toLowerCase(Locale.ROOT));
        return rgb == null ? null : transform.toAnsi(rgb[0], rgb[1], rgb[2]);
    }

    private static int[] parseHex(String hex) {
        try {
            if (hex.length() == 3) {
                int r = Integer.parseInt(hex.substring(0, 1), 16);
                int g = Integer.parseInt(hex.substring(1, 2), 16);
                int b = Integer.parseInt(hex.substring(2, 3), 16);
                return new int[]{r * 17, g * 17, b * 17};
            }
            if (hex.length() == 6) {
                int r = Integer.parseInt(hex.substring(0, 2), 16);
                int g = Integer.parseInt(hex.substring(2, 4), 16);
                int b = Integer.parseInt(hex.substring(4, 6), 16);
                return new int[]{r, g, b};
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private static String ansiFg(int r, int g, int b) {
        return "\033[38;2;" + r + ";" + g + ";" + b + "m";
    }
}
