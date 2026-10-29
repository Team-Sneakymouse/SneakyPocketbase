# Agent instructions (SneakyPocketbase)

Follow the Team-Sneakymouse cross-repo rules in EmptyPlugin's AGENTS.md; this file adds rules specific to this repo.

## Repo-specific rules

- **Public API uses JDK types only.** `PocketbaseApi` exposes JSON strings, `Runnable`, collections and `CompletableFuture`, so consumers need neither Kotlin nor coroutines. Don't add Kotlin types to the Java interface.
- **Events are async.** Fire `AsyncPocketbaseEvent` only from the SSE coroutine, and keep the Javadoc saying handlers must hop to the main thread with `runTask` for Bukkit calls.
- **Respect write outcomes.** Create/update results follow the `PocketbaseWriteFailure` categories; never retry a create whose outcome is unknown without an idempotency key.
- **Keep lifecycle tests.** Reconnect and recovery logic has tests (`PocketbaseRecoveryTest`, `PocketbaseLifecycleTest`); add cases when changing connection state.
- **Coordinate API jar changes.** The `apiJar` task publishes the API artifact; renaming or reshaping it can break SneakyPersonalization and SneakyMail builds, so change them together.
