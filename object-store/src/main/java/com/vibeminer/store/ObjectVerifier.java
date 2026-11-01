package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.Refusal;

public interface ObjectVerifier {
    Refusal check(ObjectKey key, byte[] bytes, long declaredLength);
    ObjectCheck begin(ObjectKey key, long declaredLength);
}
