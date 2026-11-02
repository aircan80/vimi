package com.vibeminer.files;

import com.vibeminer.files.FileTypes.FileFormat;
import com.vibeminer.files.FileTypes.FileFormatSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

final class FormatDetection {
    record Detected(FileFormat format, FileFormatSource source) {}

    private FormatDetection() {}

    static FileFormat magic(byte[] h) {
        if (h == null || h.length < 4) {
            return FileFormat.UNKNOWN;
        }
        String tag = new String(h, 0, 4, StandardCharsets.ISO_8859_1);
        if (Set.of(
                        "WDBC", "WDB2", "WDB3", "WDB4", "WDB5", "WDB6", "WDC1", "WDC2", "WDC3",
                        "WDC4", "1SLC")
                .contains(tag)) {
            return FileFormat.DB2;
        }
        if (tag.equals("WDC5")) {
            return h.length >= 8 && u32(h, 4) == 5 ? FileFormat.DB2 : FileFormat.UNKNOWN;
        }
        switch (tag) {
            case "BLP1", "BLP2":
                return FileFormat.BLP;
            case "MD21":
                return h.length >= 12
                                && new String(h, 8, 4, StandardCharsets.US_ASCII).equals("MD20")
                        ? FileFormat.M2_CHUNKED
                        : FileFormat.UNKNOWN;
            case "MD20":
                return FileFormat.M2_LEGACY;
            case "SKIN":
                return FileFormat.SKIN;
            case "REVM":
                if (h.length >= 16 && u32(h, 4) == 4) {
                    return switch (new String(h, 12, 4, StandardCharsets.US_ASCII)) {
                        case "DHOM" -> FileFormat.WMO_ROOT;
                        case "PGOM" -> FileFormat.WMO_GROUP;
                        case "RDHM" -> FileFormat.ADT_ROOT;
                        case "DHPM" -> FileFormat.WDT;
                        case "DHLM" -> FileFormat.ADT_LOD;
                        default -> FileFormat.UNKNOWN;
                    };
                }
                return FileFormat.UNKNOWN;
            case "OggS":
                return h.length >= 5 && h[4] == 0 ? FileFormat.OGG : FileFormat.UNKNOWN;
            case "RIFF":
                return h.length >= 12
                                && new String(h, 8, 4, StandardCharsets.US_ASCII).equals("WAVE")
                        ? FileFormat.WAV
                        : FileFormat.UNKNOWN;
            case "true":
                return FileFormat.TTF;
            case "OTTO":
                return FileFormat.OTF;
            case "ttcf":
                return FileFormat.TTC;
            default:
                break;
        }
        if (h[0] == 'I'
                && h[1] == 'D'
                && h[2] == '3'
                && h.length >= 10
                && h[3] >= 2
                && h[3] <= 4
                && (h[4] & 255) != 255
                && (h[6] | h[7] | h[8] | h[9]) >= 0) {
            return FileFormat.MP3;
        }
        if (u32(h, 0) == 256 && h.length >= 8) {
            int n = (h[4] & 255) << 8 | h[5] & 255, search = (h[6] & 255) << 8 | h[7] & 255;
            if (n >= 1 && n <= 64 && search == 16 * Integer.highestOneBit(n)) {
                return FileFormat.TTF;
            }
        }
        if (h.length >= 8
                && h[0] == (byte) 137
                && h[1] == 'P'
                && h[2] == 'N'
                && h[3] == 'G'
                && h[4] == 13
                && h[5] == 10
                && h[6] == 26
                && h[7] == 10) {
            return FileFormat.PNG;
        }
        if (h[0] == (byte) 255 && h[1] == (byte) 216 && h[2] == (byte) 255) {
            return FileFormat.JPEG;
        }
        return FileFormat.UNKNOWN;
    }

    static Detected detect(byte[] head, String path) {
        FileFormat magic = magic(head);
        if (magic != FileFormat.UNKNOWN) {
            return new Detected(magic, FileFormatSource.MAGIC);
        }
        FileFormat named = name(path);
        return new Detected(
                named,
                named == FileFormat.UNKNOWN ? FileFormatSource.NONE : FileFormatSource.EXTENSION);
    }

    static FileFormat name(String path) {
        if (path == null) {
            return FileFormat.UNKNOWN;
        }
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return FileFormat.UNKNOWN;
        }
        String ext = name.substring(dot + 1);
        return switch (ext) {
            case "db2", "dbc" -> FileFormat.DB2;
            case "m2" -> FileFormat.M2;
            case "wmo" ->
                    name.matches(".*_\\d{3}(_lod\\d)?\\.wmo")
                            ? FileFormat.WMO_GROUP
                            : FileFormat.WMO_ROOT;
            case "adt" ->
                    name.matches(".*_tex\\d+\\.adt")
                            ? FileFormat.ADT_TEX
                            : name.matches(".*_obj\\d+\\.adt")
                                    ? FileFormat.ADT_OBJ
                                    : name.endsWith("_lod.adt")
                                            ? FileFormat.ADT_LOD
                                            : FileFormat.ADT_ROOT;
            case "wdt" ->
                    name.matches(".*_(occ|lgt|fogs|mpv|preload)\\.wdt")
                            ? FileFormat.WDT_SPLIT
                            : FileFormat.WDT;
            case "xsd" -> FileFormat.XML;
            case "html", "htm" -> FileFormat.HTML;
            case "jpg", "jpeg" -> FileFormat.JPEG;
            default -> {
                if (!Set.of(
                                "blp", "skin", "anim", "bone", "phys", "skel", "tex", "wdl", "bls",
                                "wwf", "ogg", "mp3", "wav", "ttf", "otf", "ttc", "png", "lua",
                                "xml", "toc", "txt")
                        .contains(ext)) {
                    yield FileFormat.UNKNOWN;
                }
                try {
                    yield FileFormat.valueOf(ext.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    yield FileFormat.UNKNOWN;
                }
            }
        };
    }

    private static int u32(byte[] b, int at) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(at);
    }
}
