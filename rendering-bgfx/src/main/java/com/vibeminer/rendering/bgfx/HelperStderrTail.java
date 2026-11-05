package com.vibeminer.rendering.bgfx;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, sanitized tail of native helper stderr. Only the last lines are retained for an
 * abnormal-exit log record; nothing is retained in the UI, persisted or parsed as protocol. Lines
 * are decoded as UTF-8 and redacted before anything outside printable ASCII becomes '?', so a
 * non-ASCII account name is still recognised, and a binary or control-sequence flood cannot forge
 * log lines.
 */
public final class HelperStderrTail {
    public static final int MAX_LINES = 40;

    /** Bytes kept per line. */
    public static final int MAX_LINE_CHARS = 240;

    /** Longer input is cut before redaction, so redaction cost stays bounded. */
    static final int MAX_REDACT_CHARS = 4096;

    private static final String PATH = "<path>";
    private static final String REDACTED = "<redacted>";
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
    // The scheme is bounded, so a long dotted token cannot make the scan quadratic.
    private static final String SCHEME = "\\b([a-z][a-z0-9+.-]{0,31}://)";
    // Greedy within the authority, so a password containing '@' is covered up to the last '@'.
    private static final Pattern USER_INFO = Pattern.compile("(?i)" + SCHEME + "[^\\s/?#]*@");
    private static final Pattern QUERY = Pattern.compile("(?i)" + SCHEME + "([^\\s?#]*+)[?#]\\S*+");
    private static final Pattern URL = Pattern.compile("(?i)" + SCHEME + "\\S*+");
    private static final Pattern ENCODED = Pattern.compile("(?i)%(3A|5C|2F|25)");
    // Rounds of URL decoding, so a double- or triple-encoded path is still seen.
    private static final int DECODE_ROUNDS = 3;
    // Relative too: the WoW account folder, and the realm and character folders below it.
    private static final Pattern ACCOUNT =
            Pattern.compile("(?i)(WTF[\\\\/]++Account[\\\\/]++)(?=\\S)");

    /**
     * Names too common to replace everywhere: a user or host called "world" must not turn every
     * "World streaming failed" into noise. Paths that contain them are still redacted as paths.
     */
    private static final Set<String> COMMON =
            Set.of(
                    "admin",
                    "administrator",
                    "default",
                    "desktop",
                    "guest",
                    "home",
                    "java",
                    "laptop",
                    "linux",
                    "local",
                    "localhost",
                    "macbook",
                    "main",
                    "owner",
                    "player",
                    "public",
                    "root",
                    "server",
                    "system",
                    "test",
                    "user",
                    "users",
                    "vimi",
                    "windows",
                    "world");

    private static final List<String> ROOTS = new CopyOnWriteArrayList<>();
    private static final List<String> HOST_NAMES = new CopyOnWriteArrayList<>();
    private static final CompletableFuture<String> RESOLVED_HOST = new CompletableFuture<>();
    private static volatile boolean hostWaitOver;
    private static volatile Sensitive sensitive;

    static {
        // Cheap, synchronous sources first, so even the first record of a session has them.
        try {
            var file = Path.of("/etc/hostname");
            if (Files.isRegularFile(file) && Files.size(file) < 256) {
                HOST_NAMES.add(Files.readString(file).strip());
            }
        } catch (Exception | LinkageError unavailable) {
            // Not every platform has one.
        }
        sensitive = compute();
        // The resolver may take a network round trip: a platform daemon thread, never a carrier.
        var resolver =
                new Thread(
                        () -> {
                            try {
                                RESOLVED_HOST.complete(
                                        java.net.InetAddress.getLocalHost().getHostName());
                            } catch (Exception | LinkageError unavailable) {
                                RESOLVED_HOST.complete("");
                            }
                        },
                        "helper-stderr-host-name");
        resolver.setDaemon(true);
        resolver.start();
        RESOLVED_HOST.thenAccept(
                host -> {
                    if (host != null && !host.isBlank()) {
                        HOST_NAMES.add(host.strip());
                        sensitive = compute();
                    }
                });
    }

    private final ArrayDeque<byte[]> lines = new ArrayDeque<>(MAX_LINES);
    private final ArrayDeque<Boolean> truncated = new ArrayDeque<>(MAX_LINES);
    private final ByteArrayOutputStream current = new ByteArrayOutputStream(MAX_LINE_CHARS);
    private long omittedLines;
    private boolean currentTruncated;

    public synchronized void accept(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            int value = bytes[i] & 0xff;
            if (value == '\n') {
                endLine();
            } else if (value != '\r') {
                if (current.size() < MAX_LINE_CHARS) {
                    current.write(value);
                } else {
                    currentTruncated = true;
                }
            }
        }
    }

    /**
     * Includes a partial last line; never blocks on the producer. Retained lines are decoded and
     * redacted here rather than per line read, so a stderr flood costs no redaction work.
     */
    public synchronized String snapshot() {
        var result = new StringBuilder();
        if (omittedLines > 0) {
            result.append("[").append(omittedLines).append(" earlier stderr lines omitted]\n");
        }
        var marks = truncated.iterator();
        for (var line : lines) {
            result.append(line(line)).append(marks.next() ? "..." : "").append('\n');
        }
        if (current.size() > 0) {
            result.append(line(current.toByteArray()))
                    .append(currentTruncated ? "..." : "")
                    .append('\n');
        }
        return result.toString();
    }

    public synchronized boolean isEmpty() {
        return lines.isEmpty() && current.size() == 0 && omittedLines == 0;
    }

    /**
     * Registers a directory the application knows to be private, such as its data/library directory
     * or a game install it was pointed at. Its literal text is redacted wherever it appears, in
     * either slash style.
     */
    public static void addSensitiveRoot(Path root) {
        if (root == null) {
            return;
        }
        try {
            String text = root.toAbsolutePath().normalize().toString();
            if (text.length() >= 4 && !ROOTS.contains(text)) {
                ROOTS.add(text);
                sensitive = compute();
            }
        } catch (RuntimeException unusable) {
            // A path that cannot be made absolute is still covered by the path rule.
        }
    }

    /**
     * Shareable diagnostics text: no game install path, account name or host name survives.
     *
     * <ol>
     *   <li>Before anything else, {@code %3A}, {@code %5C}, {@code %2F} and {@code %25} are decoded
     *       inside non-{@code file:} URLs, up to three rounds. A URL segment in which decoding
     *       produced a slash or backslash becomes {@code <path>} whole.
     *   <li>Literal sensitive values are replaced in the original text, longest first, in both
     *       slash styles: the home and temporary directories and registered roots become {@code
     *       <path>}, and this machine's user and host names become {@code <redacted>}.
     *   <li>Control and line-separator characters become spaces.
     *   <li>URL user-info becomes {@code <redacted>@}, and URL query strings or fragments, which
     *       can be transient bearer capabilities, become {@code ?<redacted>}.
     *   <li>Whatever follows {@code WTF/Account/} in either slash style, relative paths included,
     *       becomes {@code <redacted>}: the account, realm and character folder names.
     *   <li>Every path-like span becomes {@code <path>}: {@code file:} URLs, drive-letter, UNC,
     *       {@code \\?\} and {@code \\.\} paths, POSIX and {@code ~/} paths, including paths right
     *       after {@code :}, {@code =}, <code>{</code>, {@code |}, {@code ->}, {@code +} or {@code
     *       #}. A span runs to the next double quote or backtick, an unbalanced {@code )}, {@code
     *       ]}, {@code >} or <code>}</code>, a {@code ;} or {@code ,}, or the end of the line, so
     *       spaces do not end it. This over-redacts rather than leak.
     * </ol>
     */
    public static String redact(String text) {
        if (text.length() > MAX_REDACT_CHARS) {
            text = text.substring(0, MAX_REDACT_CHARS) + "...";
        }
        awaitHostName();
        return redact(text, sensitive);
    }

    /** As {@link #redact(String)} with explicit sensitive values; tests use this directly. */
    static String redact(String text, Sensitive values) {
        String result = decodeUrls(text);
        for (var root : values.roots()) {
            result = root.matcher(result).replaceAll(Matcher.quoteReplacement(PATH));
        }
        for (var name : values.names()) {
            result = name.matcher(result).replaceAll(Matcher.quoteReplacement(REDACTED));
        }
        result = CONTROL.matcher(result).replaceAll(" ");
        result = USER_INFO.matcher(result).replaceAll("$1" + REDACTED + "@");
        result = QUERY.matcher(result).replaceAll("$1$2?" + REDACTED);
        return paths(accounts(result));
    }

    /**
     * Decodes {@code %3A}, {@code %5C} and {@code %2F} inside non-{@code file:} URLs, so an encoded
     * path such as {@code C%3A%5CUsers%5Cjsmith} meets the literal and path rules. A {@code file:}
     * URL is a path span already.
     */
    private static String decodeUrls(String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        var urls = URL.matcher(text);
        var out = new StringBuilder(text.length());
        int last = 0;
        while (urls.find()) {
            if (urls.group(1).regionMatches(true, 0, "file:", 0, 5)) {
                continue;
            }
            out.append(text, last, urls.start());
            String url = urls.group();
            int segment = 0;
            for (int i = 0; i <= url.length(); i++) {
                if (i == url.length() || "/?#&=".indexOf(url.charAt(i)) >= 0) {
                    out.append(decodeSegment(url.substring(segment, i)));
                    if (i < url.length()) {
                        out.append(url.charAt(i));
                    }
                    segment = i + 1;
                }
            }
            last = urls.end();
        }
        return out.append(text, last, text.length()).toString();
    }

    /**
     * One URL segment, decoded up to {@value #DECODE_ROUNDS} times. A segment in which decoding
     * produced a slash or backslash held an encoded path, wherever in the segment it starts, so the
     * whole segment becomes {@code <path>}.
     */
    private static String decodeSegment(String segment) {
        String current = segment;
        for (int round = 0; round < DECODE_ROUNDS; round++) {
            var escapes = ENCODED.matcher(current);
            if (!escapes.find()) {
                break;
            }
            var decoded = new StringBuilder(current.length());
            do {
                String hex = escapes.group(1).toUpperCase(Locale.ROOT);
                if (hex.equals("5C") || hex.equals("2F")) {
                    return PATH;
                }
                escapes.appendReplacement(decoded, hex.equals("3A") ? ":" : "%");
            } while (escapes.find());
            escapes.appendTail(decoded);
            current = decoded.toString();
        }
        return current;
    }

    /**
     * Replaces what follows {@code WTF/Account/} (the account, realm and character folders), in
     * either slash style and in relative paths too, with {@code <redacted>}. Any delimiter may
     * start the account name, and {@code ,} or {@code ;} never end the span.
     */
    private static String accounts(String text) {
        var accounts = ACCOUNT.matcher(text);
        var out = new StringBuilder(text.length());
        int last = 0;
        while (accounts.find(last)) {
            int end = accountEnd(text, accounts.end(), opener(text, accounts.start(), last));
            int tail = end;
            while (tail > accounts.end() && Character.isWhitespace(text.charAt(tail - 1))) {
                tail--;
            }
            out.append(text, last, accounts.end()).append(REDACTED).append(text, tail, end);
            last = end;
            if (last >= text.length()) {
                break;
            }
        }
        return out.append(text, last, text.length()).toString();
    }

    /**
     * The first character of the whitespace-delimited token holding {@code at}, not before floor.
     */
    private static char opener(String text, int at, int floor) {
        int start = at;
        while (start > floor && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        return start < at ? text.charAt(start) : ' ';
    }

    /**
     * The end of an account span: the account folder always, then on as a path span would run,
     * except that {@code ,} and {@code ;} do not end it.
     */
    private static int accountEnd(String text, int from, char opener) {
        int i = from;
        while (i < text.length()
                && !Character.isWhitespace(text.charAt(i))
                && !slash(text.charAt(i))) {
            i++;
        }
        int depth = 0;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '"' || c == '`') {
                break;
            }
            if (c == '\'' && opener == '\'' && closesQuote(text, i)) {
                break;
            }
            if (c == '(' || c == '[' || c == '<' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '>' || c == '}') {
                if (depth == 0) {
                    break;
                }
                depth--;
            }
        }
        return i;
    }

    /** An apostrophe followed by a letter or digit (Kel'Thuzad, it's) does not close a quote. */
    private static boolean closesQuote(String text, int at) {
        return at + 1 >= text.length() || !Character.isLetterOrDigit(text.charAt(at + 1));
    }

    /** Literal patterns to replace, longest first. */
    record Sensitive(List<Pattern> roots, List<Pattern> names) {
        static Sensitive of(List<String> roots, List<String> names) {
            var rootValues = new ArrayList<String>();
            for (var root : roots) {
                String trimmed = root == null ? "" : root.strip();
                while (trimmed.length() > 1 && (trimmed.endsWith("/") || trimmed.endsWith("\\"))) {
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                }
                if (trimmed.length() < 4) {
                    continue;
                }
                for (var variant :
                        List.of(trimmed, trimmed.replace('\\', '/'), trimmed.replace('/', '\\'))) {
                    if (!rootValues.contains(variant)) {
                        rootValues.add(variant);
                    }
                }
            }
            var nameValues = new ArrayList<String>();
            for (var name : names) {
                String trimmed = name == null ? "" : name.strip();
                if (trimmed.length() >= 4
                        && !COMMON.contains(trimmed.toLowerCase(Locale.ROOT))
                        && !nameValues.contains(trimmed)) {
                    nameValues.add(trimmed);
                }
            }
            return new Sensitive(compile(rootValues, true), compile(nameValues, false));
        }

        private static List<Pattern> compile(List<String> values, boolean root) {
            var sorted = new ArrayList<>(values);
            sorted.sort((a, b) -> b.length() - a.length());
            var patterns = new ArrayList<Pattern>();
            for (var value : sorted) {
                // Unicode word boundaries: a name inside a longer word is not that name.
                // An encoded separator right before a name ends with a letter: C%5Cjsmith.
                String before =
                        root ? "" : "(?:(?<![\\p{L}\\p{N}_])|(?<=(?i:%(?:25){0,2}(?:5C|2F))))";
                patterns.add(
                        Pattern.compile(
                                before + Pattern.quote(value) + "(?![\\p{L}\\p{N}_])",
                                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
            return List.copyOf(patterns);
        }
    }

    private static Sensitive compute() {
        var roots = new ArrayList<String>(ROOTS);
        roots.add(System.getProperty("user.home", ""));
        roots.add(System.getProperty("java.io.tmpdir", ""));
        var environment = System.getenv();
        for (var key : List.of("USERPROFILE", "HOME", "TMPDIR", "TEMP", "TMP", "LOCALAPPDATA")) {
            roots.add(environment.getOrDefault(key, ""));
        }
        // Short roots such as /tmp are too broad for a literal; the path rule still covers them.
        roots.removeIf(root -> root == null || root.strip().length() < 5);
        var names = new ArrayList<String>();
        names.add(System.getProperty("user.name", ""));
        for (var key : List.of("USERNAME", "USER", "LOGNAME", "COMPUTERNAME", "HOSTNAME")) {
            names.add(environment.getOrDefault(key, ""));
        }
        for (var host : HOST_NAMES) {
            names.add(host);
            int dot = host.indexOf('.');
            if (dot > 0) {
                names.add(host.substring(0, dot));
            }
        }
        return Sensitive.of(roots, names);
    }

    /** Waits once, briefly, for the resolver, so the first records can name no host. */
    private static void awaitHostName() {
        if (hostWaitOver || RESOLVED_HOST.isDone()) {
            return;
        }
        try {
            RESOLVED_HOST.get(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception slow) {
            // A slow resolver must not stall every record; the cheap sources still apply.
        } finally {
            hostWaitOver = true;
        }
    }

    /** Replaces every path-like span with {@code <path>}; linear in the text length. */
    static String paths(String text) {
        var out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            int kept = pathStart(text, i);
            if (kept < 0) {
                out.append(text.charAt(i));
                i++;
                continue;
            }
            char opener = i == 0 ? ' ' : text.charAt(i - 1);
            out.append(text, i, i + kept).append(PATH);
            int end = spanEnd(text, i + kept, opener);
            // Keep the whitespace that separated the path from whatever ended it.
            int tail = end;
            while (tail > i + kept && Character.isWhitespace(text.charAt(tail - 1))) {
                tail--;
            }
            out.append(text, tail, end);
            i = Math.max(end, i + 1);
        }
        return out.toString();
    }

    /**
     * When a path starts at {@code i}, the length of the text kept before {@code <path>} (the
     * {@code file:} scheme), else -1.
     */
    private static int pathStart(String text, int i) {
        int n = text.length();
        char c = text.charAt(i);
        char prev = i == 0 ? ' ' : text.charAt(i - 1);
        boolean wordBefore = Character.isLetterOrDigit(prev) || prev == '_';
        if (!wordBefore && text.regionMatches(true, i, "file:", 0, 5)) {
            return i + 5 < n && !Character.isWhitespace(text.charAt(i + 5)) ? 5 : -1;
        }
        if (text.startsWith(PATH, i)
                && i + PATH.length() < n
                && slash(text.charAt(i + PATH.length()))) {
            return 0;
        }
        if (c == '\\' && prev != '\\' && i + 2 < n && text.charAt(i + 1) == '\\') {
            // UNC, plus the \\?\ long-path and \\.\ device prefixes.
            return Character.isWhitespace(text.charAt(i + 2)) ? -1 : 0;
        }
        if (!wordBefore
                && c < 0x80
                && Character.isLetter(c)
                && i + 2 < n
                && text.charAt(i + 1) == ':'
                && slash(text.charAt(i + 2))) {
            // A drive, unless this is a one-letter scheme such as x://.
            boolean scheme = i + 3 < n && text.charAt(i + 2) == '/' && text.charAt(i + 3) == '/';
            return scheme ? -1 : 0;
        }
        boolean arrow = prev == '>' && i >= 2 && text.charAt(i - 2) == '-';
        if (!boundary(prev) && !arrow) {
            return -1;
        }
        if (c == '~' && i + 1 < n && slash(text.charAt(i + 1))) {
            return 0;
        }
        if (c == '/' && i + 1 < n) {
            char next = text.charAt(i + 1);
            if (Character.isWhitespace(next) || stop(next)) {
                return -1;
            }
            // "scheme://" stays a URL; a path right after ':' (key:/path) does not.
            return prev == ':' && next == '/' ? -1 : 0;
        }
        return -1;
    }

    private static int spanEnd(String text, int from, char opener) {
        int depth = 0;
        int i = from;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '"' || c == '`' || c == ';' || c == ',') {
                break;
            }
            // An apostrophe ends a path only when one opened it: O'Brien stays inside.
            if (c == '\'' && opener == '\'' && closesQuote(text, i)) {
                break;
            }
            if (c == '(' || c == '[' || c == '<' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '>' || c == '}') {
                if (depth == 0) {
                    break;
                }
                depth--;
            }
        }
        return i;
    }

    private static boolean slash(char c) {
        return c == '/' || c == '\\';
    }

    private static boolean boundary(char c) {
        return Character.isWhitespace(c)
                || c == ':'
                || c == '='
                || c == '"'
                || c == '\''
                || c == '`'
                || c == '('
                || c == '['
                || c == '<'
                || c == ','
                || c == ';'
                || c == '@'
                || c == '{'
                || c == '|'
                || c == '+'
                || c == '#'
                || c == ')'
                || c == ']'
                || c == '}';
    }

    private static boolean stop(char c) {
        return c == '"' || c == '\'' || c == ')' || c == ']' || c == '>' || c == '}' || c == ';'
                || c == ',';
    }

    /** UTF-8 decode, redact, then keep only printable ASCII. */
    private static String line(byte[] bytes) {
        String redacted = redact(new String(bytes, StandardCharsets.UTF_8));
        var safe = new StringBuilder(redacted.length());
        for (int i = 0; i < redacted.length(); i++) {
            char c = redacted.charAt(i);
            safe.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return safe.toString();
    }

    private void endLine() {
        if (lines.size() == MAX_LINES) {
            lines.removeFirst();
            truncated.removeFirst();
            omittedLines++;
        }
        lines.addLast(current.toByteArray());
        truncated.addLast(currentTruncated);
        current.reset();
        currentTruncated = false;
    }
}
