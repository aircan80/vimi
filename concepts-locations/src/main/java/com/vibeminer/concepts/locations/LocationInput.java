package com.vibeminer.concepts.locations;

import com.vibeminer.access.Cancellation;
import java.util.List;
import java.util.Objects;

/**
 * Prepared immutable provider snapshot; methods perform local reads, never acquisition/preparation.
 */
public record LocationInput(LocationTypes.Source source, Reader reader) {
    public LocationInput {
        Objects.requireNonNull(source);
        Objects.requireNonNull(reader);
    }

    public interface Reader {
        LocationTypes.Locations locations(LocationTypes.Entity entity, Cancellation token)
                throws Exception;

        LocationTypes.Starters starters(long questId, Cancellation token) throws Exception;

        default LocationTypes.Frames frames(Cancellation token) throws Exception {
            return new LocationTypes.Frames(
                    LocationTypes.State.UNSUPPORTED,
                    List.of(),
                    List.of("This source supplies no reviewed world-to-UiMap frames."));
        }
    }
}
