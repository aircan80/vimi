package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.images.BlpImageTypes;
import java.util.concurrent.CompletableFuture;

/** Asynchronous seam between browser controls and prepared data/media services. */
interface ResearchDataSource {
    default String locale() {
        return "Unknown";
    }

    CompletableFuture<ResearchPresentation.Page> page(
            String area, String term, String after, Cancellation token);

    CompletableFuture<?> detail(String area, ResearchPresentation.Row row, Cancellation token);

    CompletableFuture<?> media(String area, ResearchPresentation.Row row, Cancellation token);

    CompletableFuture<ResearchSource.TableRows> tableRows(
            long id, Long after, String identity, Cancellation token);

    CompletableFuture<BlpImageTypes.Result> icon(long id, Cancellation token);
}
