package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads only reviewed layouts and verified field names; unreadable rows are counted, not kept. */
final class WorldSkyTables {
    static final Map<String, String> LAYOUTS =
            Map.of(
                    "Light", "5F16BC84",
                    "LightParams", "A7F31923",
                    "LightData", "360DA016",
                    "Map", "D43AFAC3");

    private static final String[] COLOURS = {
        "SkyTopColor",
        "SkyMiddleColor",
        "SkyBand1Color",
        "SkyBand2Color",
        "SkySmogColor",
        "SkyFogColor"
    };

    /** Optional: without them the sky and fog still load, with no recorded light. */
    private static final String[] LIGHT = {"DirectColor", "AmbientColor"};

    /** The light tables cannot be used at all; the message names the table and why. */
    static final class Unavailable extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        Unavailable(String message) {
            super(message);
        }
    }

    private WorldSkyTables() {}

    static WorldSky.Index load(BuildTables tables, Cancellation token) {
        Objects.requireNonNull(tables);
        Objects.requireNonNull(token);
        var issues = new ArrayList<String>();
        var light = Table.open(tables, "Light", token, issues);
        var params = Table.open(tables, "LightParams", token, issues);
        var data = Table.open(tables, "LightData", token, issues);
        Table map = null;
        try {
            map = Table.open(tables, "Map", token, issues);
        } catch (Unavailable failure) {
            issues.add("Map fixed times are unavailable: " + failure.getMessage());
        }

        // Every field is checked before any row is read, so an empty table cannot hide a gap.
        light.requireInteger("LightParamsID", 0);
        light.requireInteger("ContinentID", -1);
        for (int axis = 0; axis < 3; axis++) {
            light.requireDecimal("GameCoords", axis);
        }
        light.requireDecimal("GameFalloffStart", -1);
        light.requireDecimal("GameFalloffEnd", -1);
        for (String colour : COLOURS) {
            data.requireInteger(colour, -1);
        }
        data.requireInteger("LightParamID", -1);
        data.requireInteger("Time", -1);
        data.requireDecimal("FogEnd", -1);
        data.requireDecimal("FogScaler", -1);
        boolean lit = true;
        try {
            for (String colour : LIGHT) {
                data.requireInteger(colour, -1);
            }
        } catch (Unavailable failure) {
            issues.add("Direct and ambient light are unavailable: " + failure.getMessage());
            lit = false;
        }
        if (map != null) {
            try {
                map.requireInteger("TimeOfDayOverride", -1);
            } catch (Unavailable failure) {
                issues.add("Map fixed times are unavailable: " + failure.getMessage());
                map = null;
            }
        }

        var paramIds = new HashSet<Long>();
        for (var row : params.rows(tables, WorldSky.MAX_LIGHTS, token)) {
            paramIds.add(row.id());
        }

        var lights = new ArrayList<WorldSky.Light>();
        int unreadable = 0, unknownParams = 0;
        for (var row : light.rows(tables, WorldSky.MAX_LIGHTS, token)) {
            try {
                long paramId = light.integer(row, "LightParamsID", 0);
                if (!paramIds.contains(paramId)) {
                    unknownParams++;
                    continue;
                }
                lights.add(
                        new WorldSky.Light(
                                row.id(),
                                light.integer(row, "ContinentID", -1),
                                light.decimal(row, "GameCoords", 0),
                                light.decimal(row, "GameCoords", 1),
                                light.decimal(row, "GameCoords", 2),
                                light.decimal(row, "GameFalloffStart", -1),
                                light.decimal(row, "GameFalloffEnd", -1),
                                paramId));
            } catch (Unreadable | IllegalArgumentException failure) {
                unreadable++;
            }
        }
        count(issues, unreadable, "Light rows are unreadable and skipped.");
        count(issues, unknownParams, "Light rows name a LightParams row that is absent.");

        var keys = new ArrayList<WorldSky.Key>();
        unreadable = 0;
        int orphan = 0, unlit = 0;
        for (var row : data.rows(tables, WorldSky.MAX_KEYS, token)) {
            try {
                int[] colours = new int[COLOURS.length];
                for (int i = 0; i < COLOURS.length; i++) {
                    colours[i] = colour(data, row, COLOURS[i]);
                }
                long paramId = data.integer(row, "LightParamID", -1);
                if (!paramIds.contains(paramId)) {
                    orphan++;
                    continue;
                }
                long time = data.integer(row, "Time", -1);
                if (time < 0 || time >= WorldSky.DAY) {
                    throw new Unreadable();
                }
                double fogEnd = data.decimal(row, "FogEnd", -1);
                double fogScaler = data.decimal(row, "FogScaler", -1);
                WorldSky.Lighting lighting = null;
                if (lit) {
                    try {
                        lighting =
                                new WorldSky.Lighting(
                                        colour(data, row, LIGHT[0]), colour(data, row, LIGHT[1]));
                    } catch (Unreadable failure) {
                        // Only this key's light is lost; its sky colours and fog are kept.
                        unlit++;
                    }
                }
                keys.add(
                        new WorldSky.Key(
                                row.id(),
                                paramId,
                                (int) time,
                                new WorldSky.Sample(
                                        colours[0],
                                        colours[1],
                                        colours[2],
                                        colours[3],
                                        colours[4],
                                        colours[5],
                                        lighting,
                                        fogEnd,
                                        fogScaler)));
            } catch (Unreadable | IllegalArgumentException failure) {
                unreadable++;
            }
        }
        count(issues, unreadable, "LightData rows are unreadable and skipped.");
        count(issues, orphan, "LightData rows name a LightParams row that is absent.");
        count(
                issues,
                unlit,
                "LightData rows have unreadable direct or ambient light; their sky and fog are"
                        + " kept.");

        var mapTimes = new HashMap<Long, Integer>();
        if (map != null) {
            unreadable = 0;
            try {
                for (var row : map.rows(tables, WorldSky.MAX_MAPS, token)) {
                    try {
                        long value = map.integer(row, "TimeOfDayOverride", -1);
                        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                            throw new Unreadable();
                        }
                        mapTimes.put(row.id(), (int) value);
                    } catch (Unreadable failure) {
                        unreadable++;
                    }
                }
                count(issues, unreadable, "Map rows have an unreadable fixed time.");
            } catch (Unavailable failure) {
                // Only the fixed times are lost; the sky itself does not depend on Map.
                mapTimes.clear();
                issues.add("Map fixed times are unavailable: " + failure.getMessage());
            }
        }
        return WorldSky.Index.of(lights, keys, mapTimes, issues);
    }

    private static int colour(Table table, Db2Types.Row row, String field) {
        long value = table.integer(row, field, -1);
        if (value < 0 || value > 0xff_ffff) {
            throw new Unreadable();
        }
        return (int) value;
    }

    private static void count(List<String> issues, int count, String text) {
        if (count > 0) {
            issues.add(count + " " + text);
        }
    }

    private static final class Unreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unreadable() {
            super(null, null, false, false);
        }
    }

    private static final class Table {
        final String name;
        final TableSchema schema;
        final List<Db2Types.Column> columns;
        private final Map<String, Integer> indexes = new HashMap<>();

        private Table(String name, TableTypes.SchemaResult result) {
            this.name = name;
            schema = result.schema();
            columns = result.columns();
        }

        static Table open(
                BuildTables tables, String name, Cancellation token, List<String> issues) {
            token.check();
            var result =
                    tables.schema(
                                    TableTypes.Ref.forName(name),
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join();
            var entry = result.table();
            if (entry == null
                    || entry.state() != TableTypes.State.READY
                            && entry.state() != TableTypes.State.PARTIAL
                    || result.schema() == null
                    || result.columns() == null) {
                throw new Unavailable(name + " is " + (entry == null ? "absent" : entry.state()));
            }
            String expected = LAYOUTS.get(name);
            if (entry.counts() == null
                    || !expected.equals(entry.counts().layoutHash())
                    || !result.schema().layoutHashes().contains(expected)) {
                throw new Unavailable(name + " has a layout that has not been reviewed");
            }
            if (entry.state() == TableTypes.State.PARTIAL) {
                issues.add(
                        name
                                + " is partial: "
                                + entry.counts().readableRows()
                                + " of "
                                + entry.counts().declaredRows()
                                + " rows are readable.");
            }
            return new Table(name, result);
        }

        private TableSchema.SchemaColumn field(
                String field, int slot, TableSchema.ColumnKind kind) {
            var matches = schema.columns().stream().filter(c -> c.name().equals(field)).toList();
            if (matches.size() != 1) {
                throw new Unavailable(name + "." + field + " is not in the recorded schema");
            }
            var column = matches.getFirst();
            if (!column.verifiedName()
                    || column.kind() != kind
                    || (slot < 0 ? column.arrayLength() != 1 : slot >= column.arrayLength())) {
                throw new Unavailable(name + "." + field + " has an unreviewed shape");
            }
            return column;
        }

        private int index(String field, int slot, TableSchema.ColumnKind kind, String cellKind) {
            String wanted = slot < 0 ? field : field + "[" + slot + "]";
            var cached = indexes.get(wanted);
            if (cached != null) {
                return cached;
            }
            field(field, slot, kind);
            int found = -1;
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equals(wanted)) {
                    if (found >= 0
                            || columns.get(i).interpretation() != null
                            || !cellKind.equals(columns.get(i).kind())) {
                        throw new Unavailable(name + "." + wanted + " is not a plain column");
                    }
                    found = i;
                }
            }
            if (found < 0) {
                throw new Unavailable(name + "." + wanted + " is not readable");
            }
            indexes.put(wanted, found);
            return found;
        }

        private String cell(Db2Types.Row row, int at) {
            if (row.values().size() != columns.size()
                    || row.textIssues() != null
                            && row.textIssues().stream().anyMatch(i -> i.columnIndex() == at)) {
                throw new Unreadable();
            }
            String value = row.values().get(at);
            if (value == null || value.length() > 64) {
                throw new Unreadable();
            }
            return value;
        }

        void requireInteger(String field, int slot) {
            index(field, slot, TableSchema.ColumnKind.INT, "integer");
        }

        void requireDecimal(String field, int slot) {
            index(field, slot, TableSchema.ColumnKind.FLOAT, "float");
        }

        long integer(Db2Types.Row row, String field, int slot) {
            String raw = cell(row, index(field, slot, TableSchema.ColumnKind.INT, "integer"));
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException invalid) {
                throw new Unreadable();
            }
        }

        double decimal(Db2Types.Row row, String field, int slot) {
            String raw = cell(row, index(field, slot, TableSchema.ColumnKind.FLOAT, "float"));
            try {
                double value = Double.parseDouble(raw);
                if (!Double.isFinite(value)) {
                    throw new Unreadable();
                }
                return value;
            } catch (NumberFormatException invalid) {
                throw new Unreadable();
            }
        }

        List<Db2Types.Row> rows(BuildTables tables, int bound, Cancellation token) {
            var result = new ArrayList<Db2Types.Row>();
            long after = -1;
            String identity = null;
            Set<Long> seen = new HashSet<>();
            while (true) {
                token.check();
                var page =
                        tables.page(
                                        new TableTypes.PageRequest(
                                                TableTypes.Ref.forName(name),
                                                after,
                                                256,
                                                List.of(),
                                                identity),
                                        TableTypes.Priority.INTERACTIVE,
                                        token)
                                .join();
                if (page.state() != TableTypes.PageState.OK) {
                    throw new Unavailable(name + " rows are " + page.state());
                }
                if (identity != null && !Objects.equals(identity, page.identity())) {
                    throw new Unavailable(name + " changed while it was read");
                }
                identity = page.identity();
                for (var row : page.rows()) {
                    if (result.size() >= bound) {
                        throw new Unavailable(name + " exceeds " + bound + " rows");
                    }
                    if (seen.add(row.row().id())) {
                        result.add(row.row());
                    }
                }
                if (page.nextAfter() == null) {
                    break;
                }
                if (page.nextAfter() <= after) {
                    throw new Unavailable(name + " paging did not advance");
                }
                after = page.nextAfter();
            }
            return List.copyOf(result);
        }
    }
}
