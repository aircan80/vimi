package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.Refusal;

public interface ObjectCheck {
    StoreTypes.Refusal append(byte[] bytes, int offset, int length);

    default Refusal append(byte[] bytes) {
        return append(bytes, 0, bytes.length);
    }

    Refusal complete();

    long provenLength();
}
