package com.danidipp.sneakypocketbase;

/** Stable entry point for consumers; it does not expose the Kotlin plugin implementation class. */
public final class PocketbaseProvider {
    private static volatile PocketbaseApi api;

    private PocketbaseProvider() {}

    public static PocketbaseApi getApi() {
        final PocketbaseApi current = api;
        if (current == null) {
            throw new IllegalStateException("SneakyPocketbase is not loaded");
        }
        return current;
    }

    static void install(final PocketbaseApi api) {
        PocketbaseProvider.api = api;
    }

    static void clear() {
        PocketbaseProvider.api = null;
    }
}
