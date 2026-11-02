package com.vibeminer.accounting;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ResourceTest {
    @Test
    void invalidIdentitiesAndUnknownDeclaredSizesAreDistinguished() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UsageIndex.Resource("", "key", UsageIndex.Role.DERIVED, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UsageIndex.Resource("store", "key", UsageIndex.Role.DERIVED, -2));
    }
}
