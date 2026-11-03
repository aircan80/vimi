package com.vimi.gui;

public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) throws Exception {
        if (java.util.Arrays.stream(args)
                .anyMatch(
                        a ->
                                a.equals("--package-smoke-test")
                                        || a.equals("--package-ui-smoke-test"))) {
            PackageSmoke.run(args);
            return;
        }
        javafx.application.Application.launch(VimiApp.class, args);
    }
}
