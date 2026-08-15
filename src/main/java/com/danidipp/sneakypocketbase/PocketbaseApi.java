package com.danidipp.sneakypocketbase;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Java-compatible interface exposed to other plugin classloaders. */
public interface PocketbaseApi {
    void whenReady(Runnable callback);

    CompletableFuture<String> getOne(String collection, String recordId);

    CompletableFuture<List<String>> getFullList(
        String collection,
        int batchSize,
        String sort,
        String filter
    );

    CompletableFuture<String> create(String collection, String recordJson);

    CompletableFuture<String> update(String collection, String recordId, String recordJson);

    CompletableFuture<Boolean> delete(String collection, String recordId);

    CompletableFuture<Void> subscribe(String collection);

    CompletableFuture<Void> unsubscribe(String collection);
}
