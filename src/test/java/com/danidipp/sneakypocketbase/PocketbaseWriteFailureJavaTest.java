package com.danidipp.sneakypocketbase;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import static org.junit.jupiter.api.Assertions.*;

class PocketbaseWriteFailureJavaTest {
    @Test void javaConsumerUnwrapsOnlyJdkWrappersAndUsesPublicOutcome() {
        IOException sensitiveCause = new IOException("secret payload and raw response");
        for (PocketbaseWriteFailure.Outcome outcome : PocketbaseWriteFailure.Outcome.values()) {
            PocketbaseWriteFailure expected = new PocketbaseWriteFailure(outcome, 403, sensitiveCause);
            Throwable wrapped = assertThrows(CompletionException.class,
                () -> CompletableFuture.failedFuture(new ExecutionException(expected)).join());
            while ((wrapped instanceof CompletionException || wrapped instanceof ExecutionException)
                    && wrapped.getCause() != null) wrapped = wrapped.getCause();
            assertInstanceOf(IOException.class, wrapped);
            PocketbaseWriteFailure actual = assertInstanceOf(PocketbaseWriteFailure.class, wrapped);
            assertEquals(outcome, actual.getOutcome());
            assertEquals(Integer.valueOf(403), actual.getStatusCode());
            assertSame(sensitiveCause, actual.getCause());
            assertFalse(actual.getMessage().contains("secret"));
        }
        assertNull(new PocketbaseWriteFailure(PocketbaseWriteFailure.Outcome.UNKNOWN, null, sensitiveCause).getStatusCode());
    }
}
