package com.vibeminer.eglink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CreatureDisplaysTest {
    @Test
    void displaysKeepIdProbabilityAndScaleInRecordedOrder() throws Exception {
        var displays =
                EgLinkRepository.parseDisplays(
                        "{\"name\":\"Leper Gnome\",\"displays\":[{\"displayId\":6922,\"probability\":1.0,"
                                + "\"scale\":1.0},{\"displayId\":6921,\"probability\":0.5,\"scale\":1.25}]}");
        assertEquals(
                List.of(
                        new EgLinkTypes.CreatureDisplay(6922, 1.0, 1.0),
                        new EgLinkTypes.CreatureDisplay(6921, 0.5, 1.25)),
                displays);
        // A record without displays lists none; a display without a scale is drawn at 1.
        assertEquals(List.of(), EgLinkRepository.parseDisplays("{\"name\":\"Imp\"}"));
        // Probabilities are relative weights as recorded (50 and 50, 100, 0), not shares.
        assertEquals(
                List.of(
                        new EgLinkTypes.CreatureDisplay(3167, 50, 1),
                        new EgLinkTypes.CreatureDisplay(5446, 0, 1)),
                EgLinkRepository.parseDisplays(
                        "{\"displays\":[{\"displayId\":3167,\"probability\":50.0},"
                                + "{\"displayId\":5446,\"probability\":0.0}]}"));
        assertEquals(
                List.of(new EgLinkTypes.CreatureDisplay(5, 1.0, 1.0)),
                EgLinkRepository.parseDisplays(
                        "{\"displays\":[{\"displayId\":5,\"probability\":1}]}"));
    }

    @Test
    void malformedDisplaysAreAnErrorNeverAPartialList() {
        for (String json :
                List.of(
                        "[]",
                        "{\"displays\":{}}",
                        "{\"displays\":[{\"probability\":1.0}]}",
                        "{\"displays\":[{\"displayId\":0,\"probability\":1.0}]}",
                        "{\"displays\":[{\"displayId\":4294967296,\"probability\":1.0}]}",
                        "{\"displays\":[{\"displayId\":5,\"probability\":-1}]}",
                        "{\"displays\":[{\"displayId\":5,\"probability\":1e7}]}",
                        "{\"displays\":[{\"displayId\":5,\"probability\":1.0,\"scale\":0}]}",
                        "{\"displays\":[{\"displayId\":5.5,\"probability\":1.0}]}")) {
            assertThrows(IOException.class, () -> EgLinkRepository.parseDisplays(json), json);
        }
    }

    @Test
    void provenanceNamesTheSourceLicenseBuildAndCollectionDate() {
        var displays =
                new EgLinkTypes.CreatureDisplays(
                        "id", "wowf", "1.60.1.70862", "2026-11-02", 3, List.of());
        assertEquals(
                "EG Link creature_details (CC BY 4.0), wowf build 1.60.1.70862, collected"
                        + " 2026-11-02",
                displays.provenance());
    }
}
