package com.vibeminer.store;

import java.time.Instant;
import java.util.*;
import com.vibeminer.store.StoreTypes.*;
import static com.vibeminer.store.StoreAssertions.*;

/** The same public behavior must hold for disk stores and substitutable fakes. */
public final class StoreContract {
    private StoreContract() { }
    public static void verify(ObjectReader reader) {
        ObjectWriter writer = (ObjectWriter)reader; HistoryLog history = (HistoryLog)reader;
        byte[] bytes = new byte[]{4,5,6}; ObjectKey k = key(bytes);
        check(!reader.has(k, NONE).join());
        check(writer.put(List.of(new PutObject(k,bytes)),true,NONE).join().getFirst().outcome()==PutOutcome.STORED);
        check(writer.put(List.of(new PutObject(k,bytes)),true,NONE).join().getFirst().outcome()==PutOutcome.ALREADY_PRESENT);
        check(Arrays.equals(reader.read(k,NONE).join().bytes(),bytes));
        check(Arrays.equals(reader.readRange(k,1,2,NONE).join().bytes(),new byte[]{5,6}));
        check(writer.delete(List.of(k),NONE).join().getFirst().outcome()==DeleteOutcome.DELETED);
        check(reader.read(k,NONE).join().state()==ReadState.ABSENT);
        var o = new History.Observation(1,"wow","us",Instant.EPOCH,"",bytes);
        history.appendHistory(o,NONE).join(); history.appendHistory(o,NONE).join();
        check(history.readHistory(new History.Query(),NONE).join().size()==1);
        ObjectKey record = History.objects(o).getLast().key();
        check(writer.delete(List.of(record),NONE).join().getFirst().outcome()==DeleteOutcome.REFUSED);
    }
}
