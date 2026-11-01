package com.vibeminer.builds.formats;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict bounded TACT key=value grammar. Unknown fields and empty values are retained. */
public record TactConfig(Map<String, List<String>> fields) {
    public static final int MAX_BYTES = 1 << 20,
            MAX_LINES = 16384,
            MAX_LINE_CHARS = 65536,
            MAX_FIELDS = 4096,
            MAX_VALUES = 4096;

    public TactConfig {
        var copy = new LinkedHashMap<String, List<String>>();
        fields.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        fields = java.util.Collections.unmodifiableMap(copy);
    }

    public static TactConfig parse(byte[] bytes) {
        return parse(bytes, () -> {});
    }

    public static TactConfig parse(byte[] bytes, Runnable cancellation) {
        cancellation.run();
        if (bytes.length > MAX_BYTES) {
            throw limit("Config exceeds byte limit");
        }
        String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
        } catch (CharacterCodingException e) {
            throw invalid("Config is not UTF-8");
        }
        for (int i = 0; i < text.length(); i++) {
            if ((i & 8191) == 0) {
                cancellation.run();
            }
            if (text.charAt(i) == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')) {
                throw invalid("Lone CR");
            }
        }
        Map<String, List<String>> fields = new LinkedHashMap<>();
        int lines = 0;
        String[] split = text.split("\n", -1);
        for (int i = 0; i < split.length; i++) {
            String raw = split[i];
            if (i == split.length - 1 && raw.isEmpty()) {
                break;
            }
            cancellation.run();
            if (raw.endsWith("\r")) {
                raw = raw.substring(0, raw.length() - 1);
            }
            if (++lines > MAX_LINES || raw.length() > MAX_LINE_CHARS) {
                throw limit("Config exceeds line limit");
            }
            for (char ch : raw.toCharArray()) {
                if ((Character.isISOControl(ch) && ch != '\t') || ch == '\ufeff') {
                    throw invalid("Control character in config");
                }
            }
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0 || line.indexOf('=', equals + 1) >= 0) {
                throw invalid("Config line needs exactly one equals sign");
            }
            String key = line.substring(0, equals).strip();
            if (!key.matches("[a-z0-9][a-z0-9-]*")) {
                throw invalid("Invalid config field name");
            }
            String value = line.substring(equals + 1).strip();
            List<String> values = value.isEmpty() ? List.of() : List.of(value.split("[ \t]+"));
            if (values.size() > MAX_VALUES || fields.size() >= MAX_FIELDS) {
                throw limit("Config exceeds field/value limit");
            }
            if (fields.putIfAbsent(key, values) != null) {
                throw invalid("Duplicate config field");
            }
        }
        return new TactConfig(fields);
    }

    private static com.vibeminer.builds.BuildFormatException invalid(String message) {
        return FormatSupport.corrupt("CONFIG_INVALID", message);
    }

    private static com.vibeminer.builds.BuildFormatException limit(String message) {
        return FormatSupport.corrupt("CONFIG_LIMIT", message);
    }
}
