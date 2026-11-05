package com.vibeminer.rendering.bgfx;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Precompiled Java child processes for World stream tests.
 *
 * <p>Launching a child as {@code java Child.java} compiles it inside the child on every start. On a
 * loaded Windows runner that costs seconds of CPU per launch and blows startup and close budgets
 * that the protocol, not the compiler, is meant to be tested against. These children are compiled
 * with the test sources and start as plain classes.
 */
public final class WorldStreamTestChildren {
    private WorldStreamTestChildren() {}

    /** Command line that runs {@code main} of {@code child} in a fresh, lightweight JVM. */
    static List<String> command(Class<?> child, String... arguments) {
        Path classes;
        try {
            classes = Path.of(child.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException failure) {
            throw new IllegalStateException(failure);
        }
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-XX:+UseSerialGC");
        command.add("-XX:TieredStopAtLevel=1");
        command.add("-cp");
        command.add(classes.toString());
        command.add(child.getName());
        command.addAll(List.of(arguments));
        return command;
    }

    /** Prints READY, then waits for stdin to close. */
    public static final class Wait {
        private Wait() {}

        public static void main(String[] args) throws Exception {
            System.out.println("READY");
            System.out.flush();
            while (System.in.read() != -1) {
                // Wait for termination.
            }
        }
    }

    /** Announces a session with one credit and answers CLOSE with CLOSED; never expects COMMIT. */
    public static final class Closing {
        private Closing() {}

        public static void main(String[] a) throws Exception {
            String n = "WS2 " + a[0] + " ";
            System.out.print(n + "SESSION TERRAIN_BASIC_V1\n" + n + "CREDIT 1 0 1\n");
            System.out.flush();
            var input = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = input.readLine()) != null) {
                String[] p = line.split(" ");
                if (p[2].equals("CLOSE")) {
                    System.out.print(n + "CLOSED\n");
                    System.out.flush();
                    return;
                }
                if (p[2].equals("COMMIT")) {
                    throw new AssertionError("Closing commit was advertised");
                }
            }
        }
    }
}
