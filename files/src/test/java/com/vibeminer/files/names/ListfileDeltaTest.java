package com.vibeminer.files.names;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ListfileDeltaTest {
    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String sha(byte[] b) {
        return HexFormat.of().formatHex(ListfileStore.digest().digest(b));
    }

    static byte[] delta(byte[] a, byte[] b) {
        return ListfileDelta.encode(
                new ByteArrayInputStream(a),
                new ByteArrayInputStream(b),
                sha(a),
                sha(b),
                b.length,
                1 << 20,
                Cancellation.NONE);
    }

    @Test
    void exactCsvRoundTripAndTwoLinkChain() {
        byte[] a = bytes("\r\n0;a\r\n2;b\n\n5;c"),
                b = bytes("\n0;A\n3;d\n5;c\n"),
                c = bytes("3;D\r\n4294967295;z");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ListfileDelta.apply(
                new ByteArrayInputStream(a),
                List.of(delta(a, b), delta(b, c)),
                out,
                Cancellation.NONE);
        assertArrayEquals(c, out.toByteArray());
    }

    @Test
    void sameBytesIsHeaderOnly() {
        byte[] a = bytes("1;a\n");
        assertEquals(88, delta(a, a).length);
    }

    @Test
    void capReturnsNullAndWrongInputRefuses() {
        byte[] a = bytes("1;a"), b = bytes("2;b");
        assertNull(
                ListfileDelta.encode(
                        new ByteArrayInputStream(a),
                        new ByteArrayInputStream(b),
                        sha(a),
                        sha(b),
                        b.length,
                        88,
                        Cancellation.NONE));
        assertEquals(
                ListfileDeltaException.Problem.INPUT_MISMATCH,
                assertThrows(
                                ListfileDeltaException.class,
                                () ->
                                        ListfileDelta.encode(
                                                new ByteArrayInputStream(a),
                                                new ByteArrayInputStream(b),
                                                sha(b),
                                                sha(b),
                                                b.length,
                                                1024,
                                                Cancellation.NONE))
                        .problem());
    }

    @Test
    void malformedHeaderTruncationTrailingAndBrokenBase() {
        byte[] a = bytes("1;a"), b = bytes("1;b"), d = delta(a, b);
        for (int n = 0; n < d.length; n++) {
            byte[] cut = Arrays.copyOf(d, n);
            assertThrows(ListfileDeltaException.class, () -> ListfileDelta.readHeader(cut));
        }
        byte[] extra = Arrays.copyOf(d, d.length + 1);
        assertThrows(ListfileDeltaException.class, () -> ListfileDelta.readHeader(extra));
        assertEquals(
                ListfileDeltaException.Problem.BROKEN_CHAIN,
                assertThrows(
                                ListfileDeltaException.class,
                                () ->
                                        ListfileDelta.apply(
                                                new ByteArrayInputStream(bytes("1;z")),
                                                List.of(d),
                                                new ByteArrayOutputStream(),
                                                Cancellation.NONE))
                        .problem());
    }

    @Test
    void wrongTargetAndAbsentRemovalRefused() {
        byte[] a = bytes("1;a"), b = bytes("1;b"), d = delta(a, b);
        d[40] ^= 1;
        assertEquals(
                ListfileDeltaException.Problem.WRONG_TARGET,
                assertThrows(
                                ListfileDeltaException.class,
                                () ->
                                        ListfileDelta.apply(
                                                new ByteArrayInputStream(a),
                                                List.of(d),
                                                new ByteArrayOutputStream(),
                                                Cancellation.NONE))
                        .problem());
        byte[] remove = delta(a, bytes("2;b"));
        remove[89] = 0;
        assertEquals(
                ListfileDeltaException.Problem.MALFORMED,
                assertThrows(
                                ListfileDeltaException.class,
                                () ->
                                        ListfileDelta.apply(
                                                new ByteArrayInputStream(a),
                                                List.of(remove),
                                                new ByteArrayOutputStream(),
                                                Cancellation.NONE))
                        .problem());
    }

    @Test
    void cancellationAndChainLimit() {
        Cancellation token = new Cancellation();
        token.cancel();
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () ->
                        ListfileDelta.encode(
                                new ByteArrayInputStream(bytes("1;a")),
                                new ByteArrayInputStream(bytes("1;a")),
                                sha(bytes("1;a")),
                                sha(bytes("1;a")),
                                3,
                                1024,
                                token));
        assertThrows(
                ListfileDeltaException.class,
                () ->
                        ListfileDelta.apply(
                                new ByteArrayInputStream(new byte[0]),
                                Collections.nCopies(17, new byte[0]),
                                new ByteArrayOutputStream(),
                                Cancellation.NONE));
    }
}
