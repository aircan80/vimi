package com.vimi.gui;

import java.util.List;

/**
 * Pins the normal-case prepared table inventory identity: a change to any fixed line silently
 * invalidates every prepared inventory, so it must be deliberate.
 */
public final class PreparationIdentityTest {
    private PreparationIdentityTest() {}

    public static void main(String[] args) {
        var lines =
                List.of(
                        NativePreparationWork.tableCacheIdentity(
                                        "wowf",
                                        "b".repeat(32),
                                        "c".repeat(32),
                                        "us",
                                        "enUS",
                                        "1.60.1.70862",
                                        "d".repeat(32),
                                        "e".repeat(32),
                                        "keys")
                                .split("\n", -1));
        require(lines.size() == 15, "fifteen identity lines");
        require(lines.get(0).equals("native-table-policy-v1"), "table policy line");
        require(
                lines.subList(1, 9)
                        .equals(
                                List.of(
                                        "wowf",
                                        "b".repeat(32),
                                        "c".repeat(32),
                                        "us",
                                        "enUS",
                                        "1.60.1.70862",
                                        "d".repeat(32),
                                        "e".repeat(32))),
                "build lines");
        require(
                lines.get(9).equals("b9a63096cdd99b26967f17f9515377ae62cadfd7"),
                "bundled definitions revision");
        require(
                lines.get(10)
                        .equals("f7b752a991d6a0321d07ecb7f06e62cd94694c1594d2fe5ce0cb7b243c71559a"),
                "bundled manifest hash");
        require(
                lines.get(11)
                        .equals("cc3119202a5f74b5af9318212d2468d3e51659cf7f9f85446cb72bb9d15555dd"),
                "bundled source-list hash");
        require(
                lines.get(12).startsWith("spell-duration-4fdf0951-v2:"),
                "supplemental definitions line");
        require(lines.get(14).equals("keys"), "key fingerprint line");
        System.out.println("Preparation identity: normal-case table inventory identity pinned");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
