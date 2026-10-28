package com.danidipp.sneakypocketbase;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Java-compatible interface exposed to other plugin classloaders. */
public interface PocketbaseApi {
    /** Authentication callback only; does not promise realtime or collection readiness. Runs asynchronously. */
    void whenReady(Runnable callback);

    /** Register the lifecycle listener first, then read this immutable snapshot. Ignore older revisions. */
    PocketbaseLifecycleSnapshot getLifecycleSnapshot();

    CompletableFuture<String> getOne(String collection, String recordId);

    CompletableFuture<List<String>> getFullList(
        String collection,
        int batchSize,
        String sort,
        String filter
    );

    /**
     * Creates once, without automatic retries. Manager failures complete exceptionally with a
     * {@link PocketbaseWriteFailure}; unwrap JDK CompletionException/ExecutionException to inspect it.
     * Only NOT_SENT and authoritative standard PocketBase REJECTED outcomes prove non-application.
     * UNKNOWN requires reconciliation; a subsequent lookup returning 404 does not authorize resend.
     * Cancelling the returned observer future does not stop an admitted coroutine/HTTP request and
     * yields a plain CancellationException, never evidence that the write was not sent.
     * Causes are diagnostic only: do not log them automatically (they may contain sensitive data).
     */
    CompletableFuture<String> create(String collection, String recordJson);

    CompletableFuture<String> update(String collection, String recordId, String recordJson);

    CompletableFuture<Boolean> delete(String collection, String recordId);

    /** Completes on local registration, even while disconnected. Observe server acceptance in the snapshot. */
    CompletableFuture<Void> subscribe(String collection);

    CompletableFuture<Void> unsubscribe(String collection);
}
