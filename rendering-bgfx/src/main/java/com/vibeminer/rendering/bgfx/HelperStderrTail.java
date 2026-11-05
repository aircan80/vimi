package com.vibeminer.rendering.bgfx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Bounded, sanitized tail of native helper stderr. Only the last lines are retained for an
 * abnormal-exit log record; nothing is retained in the UI, persisted or parsed as protocol.
 * Non-printable bytes become '?', so a binary or control-sequence flood cannot forge log lines.
 */
public final class HelperStderrTail {
    public static final int MAX_LINES = 40;
    public static final int MAX_LINE_CHARS = 240;

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
    // file: URLs carry local paths; any scheme's user-info can carry an account name.
    private static final Pattern FILE_URL = Pattern.compile("(?i)\\b((?:jar:)?file:)\\S*");
    private static final Pattern USER_INFO =
            Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://)[^\\s/?#@]*@");
    private static final Pattern QUERY =
            Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://[^\\s?#]*)[?#]\\S*");
    private static final String PATH_CHARS = "[^\\s:;,'\"()<>\\[\\]|*?]";
    // Absolute paths, whole token: a game install, home or scratch directory names the account.
    private static final Pattern WINDOWS_PATH =
            Pattern.compile(
                    "(?i)(?<![\\w\\\\])(?:[a-z]:[\\\\/]|\\\\\\\\[^\\s\\\\/]+)" + PATH_CHARS + "*");
    private static final Pattern UNIX_PATH =
            Pattern.compile("(?<![\\w.~:/\\\\<-])~?/" + PATH_CHARS + "+");
    private static final String PATH = "<path>";

    private static volatile List<Pattern> identities = identities(List.of());

    static {
        // The machine name may need a resolver round trip: never on a caller, and best effort.
        Thread.ofVirtual()
                .name("helper-stderr-identity")
                .start(
                        () -> {
                            var names = new ArrayList<String>();
                            try {
                                names.add(java.net.InetAddress.getLocalHost().getHostName());
                            } catch (Exception | LinkageError unavailable) {
                                // Environment names still apply.
                            }
                            try {
                                var file = Path.of("/etc/hostname");
                                if (Files.isRegularFile(file) && Files.size(file) < 256) {
                                    names.add(Files.readString(file).trim());
                                }
                            } catch (Exception | LinkageError unavailable) {
                                // Not every platform has one.
                            }
                            identities = identities(names);
                        });
    }

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

    /**
     * Includes a partial last line; never blocks on the producer. Retained lines are redacted here
     * rather than per line read, so a stderr flood costs no regex work.
     */
    public synchronized String snapshot() {
        var result = new StringBuilder();
        if (omittedLines > 0) {
            result.append("[").append(omittedLines).append(" earlier stderr lines omitted]\n");
        }
        for (var line : lines) {
            result.append(redact(line)).append('\n');
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
     * Replaces control/line-separator characters; URL query strings or fragments, which can be
     * transient bearer capabilities; URL user-info and {@code file:} URLs; absolute local paths
     * (game install, home and scratch directories) as whole tokens; and this machine's home
     * directory, user and host names. No install path, account name or hostname survives.
     */
    public static String redact(String text) {
        String result = CONTROL.matcher(text).replaceAll(" ");
        result = FILE_URL.matcher(result).replaceAll("$1" + PATH);
        result = USER_INFO.matcher(result).replaceAll("$1<redacted>@");
        result = QUERY.matcher(result).replaceAll("$1?<redacted>");
        result = WINDOWS_PATH.matcher(result).replaceAll(PATH);
        result = UNIX_PATH.matcher(result).replaceAll(PATH);
        for (var identity : identities) {
            result = identity.matcher(result).replaceAll("<redacted>");
        }
        return result;
    }

    /**
     * Literal home directory, user and host names, longest first. Names under three characters are
     * skipped: replacing them would destroy unrelated words without protecting anything.
     */
    private static List<Pattern> identities(List<String> hostNames) {
        var names = new ArrayList<String>();
        names.add(System.getProperty("user.home", ""));
        names.add(System.getProperty("user.name", ""));
        var environment = System.getenv();
        for (var key : List.of("USERNAME", "USER", "COMPUTERNAME", "HOSTNAME")) {
            names.add(environment.getOrDefault(key, ""));
        }
        for (var host : hostNames) {
            names.add(host);
            int dot = host.indexOf('.');
            if (dot > 0) {
                names.add(host.substring(0, dot));
            }
        }
        var unique = new ArrayList<String>();
        for (var name : names) {
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.length() >= 3
                    && !trimmed.equals("/")
                    && !trimmed.toLowerCase(Locale.ROOT).equals("localhost")
                    && !unique.contains(trimmed)) {
                unique.add(trimmed);
            }
        }
        unique.sort((a, b) -> b.length() - a.length());
        var patterns = new ArrayList<Pattern>();
        for (var name : unique) {
            patterns.add(
                    Pattern.compile(
                            "(?<![\\w])" + Pattern.quote(name) + "(?![\\w])",
                            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return List.copyOf(patterns);
    }

    private void endLine() {
        if (lines.size() == MAX_LINES) {
            lines.removeFirst();
            omittedLines++;
        }
        lines.addLast(current + (currentTruncated ? "..." : ""));
        current.setLength(0);
        currentTruncated = false;
    }
}
