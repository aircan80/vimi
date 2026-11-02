package com.vimi.gui;

/** Pure presentation rules shared by the workspace and activity entry. */
public final class PreparationStatus {
    private PreparationStatus() {}

    public static String describe(BuildPreparation.Snapshot snapshot) {
        String ready = readiness(snapshot.ready());
        String activity =
                switch (snapshot.activity()) {
                    case IDLE -> "Idle";
                    case STOPPING -> "Stopping…";
                    case CANCELED -> "Preparation canceled";
                    case FAILED -> "Preparation failed";
                    case RUNNING ->
                            switch (snapshot.phase()) {
                                case METADATA -> "Preparing metadata…";
                                case FILES -> "Preparing files…";
                                case KEYS -> "Loading build keys…";
                                case TABLES ->
                                        restoringTables(snapshot)
                                                ? "Restoring cached tables…"
                                                : "Preparing tables…";
                                case LOOKUPS -> "Preparing lookups…";
                                case IDLE -> "Working…";
                            };
                };
        if (snapshot.busy() && snapshot.total() != null)
            activity += " " + snapshot.done() + "/" + snapshot.total() + " " + snapshot.unit();
        return ready + " · " + activity;
    }

    static boolean restoringTables(BuildPreparation.Snapshot snapshot) {
        return snapshot.phase() == BuildPreparation.Phase.TABLES
                && "cached tables restored".equals(snapshot.unit());
    }

    public static String readiness(BuildPreparation.Ready ready) {
        return switch (ready) {
            case NOT_PREPARED -> "Not prepared";
            case METADATA_READY -> "Metadata ready";
            case FILES_READY -> "Files ready";
            case TABLES_READY -> "Tables ready";
        };
    }

    /** A prior assessment, not a promise that closed resources are currently usable. */
    public static String closed(BuildPreparation.Ready ready, String locale) {
        return "Closed · last readiness: " + readiness(ready) + " · " + locale;
    }

    public static BuildPreparation.Job activityFocus(
            java.util.List<BuildPreparation.Job> jobs, BuildPreparation.Job prior) {
        for (var job : jobs) if (job.snapshot().busy()) return job;
        for (var job : jobs) if (job.enrichingNames()) return job;
        if (prior != null && jobs.contains(prior)) return prior;
        return jobs.isEmpty() ? null : jobs.getLast();
    }

    public static boolean available(String area, BuildPreparation.Ready ready) {
        return switch (area) {
            case "Overview" -> ready.ordinal() >= BuildPreparation.Ready.METADATA_READY.ordinal();
            case "Files" -> ready.ordinal() >= BuildPreparation.Ready.FILES_READY.ordinal();
            case "Tables", "Items", "Creatures" -> ready == BuildPreparation.Ready.TABLES_READY;
            default -> false;
        };
    }

    public static String tab(String area, BuildPreparation.Snapshot state, boolean names) {
        if (!available(area, state.ready())) {
            if (!state.busy()) return area + " · Not ready";
            if (!area.equals("Files")
                    && state.ready().ordinal() < BuildPreparation.Ready.FILES_READY.ordinal())
                return area + " · Waiting";
            return area + " · Loading";
        }
        if (area.equals("Files") && names) return area + " · Enriching";
        if (area.equals("Tables")
                && state.ready() == BuildPreparation.Ready.TABLES_READY
                && state.busy()) return area + " · Enriching";
        return area;
    }
}
