package com.vibeminer.access;

final class MemoryObjectSourceTest extends ObjectSourceContracts {
    @Override
    protected AccessScenario create() {
        return AccessScenario.independent();
    }
}
