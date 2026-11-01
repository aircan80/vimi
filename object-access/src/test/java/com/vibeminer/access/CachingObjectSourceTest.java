package com.vibeminer.access;

final class CachingObjectSourceTest extends ObjectSourceContracts {
    @Override
    protected AccessScenario create() {
        return AccessScenario.realMemory();
    }
}
