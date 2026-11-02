package com.vibeminer.questie.data;

import com.vibeminer.generated.Cancellation;
import com.vibeminer.questie.QuestieTypes.EntityKind;
import com.vibeminer.questie.QuestieTypes.SourceSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipFile;

/** Reads data only; ZIP contents and source Lua are never executed or extracted. */
final class BakedArchive {
    static final int MAX_TOC = 128 * 1024 * 1024;
    final Map<String, String> metadata = new LinkedHashMap<>();
    final Map<EntityKind, Schema> schemas = new EnumMap<>(EntityKind.class);
    final Set<String> usedKeys = new HashSet<>();

    BakedArchive(SourceSnapshot source, Cancellation token) throws IOException {
        if (Files.size(source.archive()) != source.bytes()) {
            throw new IllegalArgumentException("Archive size changed");
        }
        try (var input = Files.newInputStream(source.archive())) {
            var digest = sha256();
            var buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                token.check();
                digest.update(buffer, 0, count);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(source.sha256())) {
                throw new IllegalArgumentException("Archive checksum changed");
            }
        }
        try (var zip = new ZipFile(source.archive().toFile())) {
            var entries = zip.entries();
            var names = new HashSet<String>();
            long raw = 0;
            int count = 0;
            while (entries.hasMoreElements()) {
                token.check();
                var entry = entries.nextElement();
                String name = entry.getName();
                if (++count > 10000
                        || name.startsWith("/")
                        || name.contains("\\")
                        || name.contains(":")
                        || java.util.Arrays.asList(name.split("/")).contains("..")
                        || !names.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Unsafe/duplicate archive path");
                }
                if (entry.getSize() < 0
                        || entry.getSize() > MAX_TOC
                        || (raw += entry.getSize()) > 512L * 1024 * 1024) {
                    throw new IllegalArgumentException("Archive expansion limit");
                }
            }
            String toc =
                    Cbor.utf8(read(zip, "QuestieDB/" + source.flavor().tocName(), MAX_TOC, token));
            for (String line : toc.split("\n")) {
                token.check();
                line = line.stripTrailing();
                if (!line.startsWith("## ")) {
                    continue;
                }
                int colon = line.indexOf(':', 3);
                if (colon < 0) {
                    continue;
                }
                String key = line.substring(3, colon).toLowerCase(Locale.ROOT);
                if (key.startsWith("x-")
                        && line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1023) {
                    throw new IllegalArgumentException("Overlong TOC metadata line");
                }
                if (metadata.putIfAbsent(key, line.substring(colon + 1).strip()) != null
                        && key.startsWith("x-")) {
                    throw new IllegalArgumentException("Duplicate TOC metadata key");
                }
            }
            String commit = metadata.get("x-build-commit");
            if (commit == null || !commit.equals(source.producerCommit())) {
                throw new IllegalArgumentException("Producer commit mismatch");
            }
            String config = Cbor.utf8(read(zip, "QuestieDB/src/config.lua", 1 << 20, token));
            var contract =
                    java.util.regex.Pattern.compile(
                                    "(?m)^config\\.contractVersion\\s*=\\s*(\\d+)\\s*$")
                            .matcher(config);
            if (!contract.find()
                    || Integer.parseInt(contract.group(1)) < 2
                    || Integer.parseInt(contract.group(1)) > 4) {
                throw new IllegalArgumentException(
                        "Unsupported QuestieDB storage contract (requires 2–4)");
            }
            if (!contract.group(1).equals(metadata.get("x-contract-version"))
                    || !source.flavor().upstream().equals(metadata.get("x-flavor"))) {
                throw new IllegalArgumentException("TOC contract/flavor mismatch");
            }
            String localeVersion = metadata.get("x-l10n-version");
            if (localeVersion != null && !localeVersion.equals("1")) {
                throw new IllegalArgumentException("Unsupported localization format");
            }
            for (var kind : EntityKind.values()) {
                schemas.put(
                        kind,
                        new Schema(
                                kind,
                                Cbor.utf8(
                                        read(
                                                zip,
                                                "QuestieDB/src/meta/"
                                                        + kind.name().toLowerCase(Locale.ROOT)
                                                        + "Meta.lua",
                                                1 << 20,
                                                token))));
            }
            if (source.flavor() == com.vibeminer.questie.QuestieTypes.Flavor.FOREVER) {
                var alias = zip.getEntry("QuestieDB/QuestieDB_Camelot.toc");
                if (alias != null
                        && !toc.equals(Cbor.utf8(read(zip, alias.getName(), MAX_TOC, token)))) {
                    throw new IllegalArgumentException("Forever alias differs");
                }
            }
        }
    }

    private static byte[] read(ZipFile zip, String name, int max, Cancellation token)
            throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > max) {
            throw new IllegalArgumentException("Missing/oversized archive entry: " + name);
        }
        try (var input = zip.getInputStream(entry);
                var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                token.check();
                if (output.size() + count > max) {
                    throw new IllegalArgumentException("Archive entry size limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    String stored(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        String value = metadata.get(key);
        if (value != null) {
            usedKeys.add(key);
        }
        if (value == null) {
            return null;
        }
        if (!value.startsWith("~")) {
            return value;
        }
        if (!value.matches("~[1-9][0-9]{0,5}~")) {
            throw new IllegalArgumentException("Invalid chunk marker");
        }
        int count = Integer.parseInt(value.substring(1, value.length() - 1));
        var text = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            String partKey = key + "-" + i;
            usedKeys.add(partKey);
            String part = metadata.get(partKey);
            if (part == null || part.startsWith("~")) {
                throw new IllegalArgumentException("Missing/invalid chunk");
            }
            if (text.length() + part.length() > 96 * 1024 * 1024) {
                throw new IllegalArgumentException("Chunk byte limit");
            }
            text.append(part);
        }
        return text.toString();
    }

    Object decode(String name, boolean compressed, Cancellation token) {
        String text = stored(name);
        if (text == null) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64: " + name, e);
        }
        if (!compressed) {
            return Cbor.decode(bytes);
        }
        var inflater = new Inflater();
        inflater.setInput(bytes);
        try (var output = new ByteArrayOutputStream()) {
            var buffer = new byte[65536];
            while (!inflater.finished()) {
                token.check();
                int count = inflater.inflate(buffer);
                if (count == 0 || output.size() + count > 64 * 1024 * 1024) {
                    throw new IllegalArgumentException("Invalid/oversized zlib block");
                }
                output.write(buffer, 0, count);
            }
            if (inflater.getRemaining() != 0) {
                throw new IllegalArgumentException("Trailing zlib bytes");
            }
            return Cbor.decode(output.toByteArray());
        } catch (DataFormatException | IOException e) {
            throw new IllegalArgumentException("Invalid zlib", e);
        } finally {
            inflater.end();
        }
    }
}
