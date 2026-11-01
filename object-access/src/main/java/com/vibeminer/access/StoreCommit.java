package com.vibeminer.access;

import com.vibeminer.store.ObjectKey;
import java.util.List;

/** Published only after presence reads agree with the completed durable write. */
public record StoreCommit(List<ObjectKey> stored, List<ObjectKey> removed) {
    public StoreCommit {
        stored = List.copyOf(stored);
        removed = List.copyOf(removed);
    }
}
