package com.vibeminer.concepts.items;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.core.RelationIndexes;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;

/** Item model files for components that place item models (the Items page's own join). */
public final class ItemModelFiles {
    private ItemModelFiles() {}

    /**
     * The model files one {@code ModelResourcesID} names ({@code ModelFileData}), each with its
     * {@code ComponentModelFileData} key (race, gender, class, position).
     */
    public static ItemResourceRef files(
            BuildTables tables,
            RelationIndexes relations,
            long modelResourcesId,
            TableTypes.Priority priority,
            Cancellation token) {
        return ItemAppearanceReader.modelFiles(
                tables, relations, modelResourcesId, priority, token);
    }

    /**
     * The file a character of {@code raceId} and {@code gender} (0 male, 1 female) wears from
     * {@code files} at {@code position}: a component for its own race and gender is preferred to
     * one for any race (0) or either gender (2 and up), and the lowest file ID breaks ties. Null
     * when none fits. Pure.
     */
    public static Long componentFile(
            java.util.List<ItemResourceFile> files, long raceId, long gender, long position) {
        Long best = null;
        int bestRank = Integer.MAX_VALUE;
        for (var file : files) {
            var key = file.component();
            if (key == null
                    || key.state() != ItemRefState.FOUND
                    || key.raceId() == null
                    || key.genderIndex() == null
                    || key.positionIndex() == null) {
                continue;
            }
            boolean race = key.raceId() == raceId, anyRace = key.raceId() == 0;
            boolean sex = key.genderIndex() == gender, anySex = key.genderIndex() >= 2;
            if (!(race || anyRace) || !(sex || anySex) || key.positionIndex() != position) {
                continue;
            }
            int rank = (race ? 0 : 2) + (sex ? 0 : 1);
            if (rank < bestRank || rank == bestRank && file.fileDataId() < best) {
                best = file.fileDataId();
                bestRank = rank;
            }
        }
        return best;
    }
}
