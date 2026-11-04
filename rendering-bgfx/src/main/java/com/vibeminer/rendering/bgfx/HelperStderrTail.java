package com.vibeminer.rendering.bgfx;

import java.util.ArrayDeque;
import java.util.regex.Pattern;

/**
 * Bounded, sanitized tail of native helper stderr. Only the last lines are retained for an
 * abnormal-exit log record; nothing is retained in the UI, persisted or parsed as protocol.
 * Non-printable bytes become '?', so a binary or control-sequence flood cannot forge log lines.
 */
public final class HelperStderrTail {
    public static final int MAX_LINES = 40;
    public static final int MAX_LINE_CHARS = 240;

    private static final Pattern QUERY =
            Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://[^\\s?#]*)[?#]\\S*");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

    private final ArrayDeque<String> lines = new ArrayDeque<>(MAX_LINES);
    private final StringBuilder current = new StringBuilder(MAX_LINE_CHARS);
    private long omittedLines;
    private boolean currentTruncated;

    public synchronized void accept(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            int value = bytes[i] & 0xff;
            if (value == '\n') {
                endLine();
            } else if (value != '\r') {
                char safe =
                        value == '\t' ? ' ' : value >= 0x20 && value < 0x7f ? (char) value : '?';
                if (current.length() < MAX_LINE_CHARS) {
                    current.append(safe);
                } else {
                    currentTruncated = true;
                }
            }
        }
    }

    /** Includes a partial last line; never blocks on the producer. */
    public synchronized String snapshot() {
        var result = new StringBuilder();
        if (omittedLines > 0) {
            result.append("[").append(omittedLines).append(" earlier stderr lines omitted]\n");
        }
        for (var line : lines) {
            result.append(line).append('\n');
        }
        if (!current.isEmpty()) {
            result.append(redact(current.toString()))
                    .append(currentTruncated ? "..." : "")
                    .append('\n');
        }
        return result.toString();
    }

    public synchronized boolean isEmpty() {
        return lines.isEmpty() && current.isEmpty() && omittedLines == 0;
    }

    /**
     * Replaces control/line-separator characters and URL query strings or fragments, which can be
     * transient bearer capabilities.
     */
    public static String redact(String text) {
        return QUERY.matcher(CONTROL.matcher(text).replaceAll(" ")).replaceAll("$1?<redacted>");
    }

    private void endLine() {
        if (lines.size() == MAX_LINES) {
            lines.removeFirst();
            omittedLines++;
        }
        lines.addLast(redact(current.toString()) + (currentTruncated ? "..." : ""));
        current.setLength(0);
        currentTruncated = false;
    }
}
