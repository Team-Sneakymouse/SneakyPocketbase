# SneakyPocketbase

Paper plugin providing PocketBase access and asynchronous execution facilities for other Sneaky plugins.

## Consumer interface

Consumers must enter through `PocketbaseProvider.getApi()`. The returned `PocketbaseApi` interface uses only JDK types: JSON strings, `Runnable`, collections, and `CompletableFuture`.

```kotlin
val pocketbase = PocketbaseProvider.getApi()

pocketbase.subscribe("example_collection").thenRun {
    // Registered locally. Observe lifecycle state for server acceptance.
}

pocketbase.whenReady {
    // Authentication completed. Realtime and subscription readiness are separate.
}
```

Realtime updates are published as `AsyncPocketbaseEvent`. Its action is the Java `AsyncPocketbaseEvent.Action` enum, and `recordJson` contains the raw record JSON for the consumer to deserialize.

```kotlin
@EventHandler
fun onPocketbaseUpdate(event: AsyncPocketbaseEvent) {
    if (event.collectionName != "example_collection") return
    if (event.generation != pocketbase.lifecycleSnapshot.generation) return
    val record = Json.decodeFromString<ExampleRecord>(event.recordJson)
    // Recheck the generation inside any later scheduled task before applying the record.
}
```

## Lifecycle and collection readiness

`PocketbaseApi.getLifecycleSnapshot()` returns an immutable snapshot containing global `ApiState`, global `TransportState`, and a map of collection names to `CollectionStatus`. An absent collection is unregistered. Registered collections are `PENDING`, `READY`, or `FAILED`, with a sanitized reason. `isCollectionReady(name)` requires API availability, a connected transport, and server acceptance for that collection. With no subscriptions the transport may be connected, but no collection is ready.

`subscribe(name)` completes when the collection is registered locally, even during an outage. It does not wait for server acceptance. Registrations survive handler reloads and are reapplied automatically. `unsubscribe(name)` likewise removes local intent; server removal happens asynchronously. These methods register whole collections by their names, without `/*` suffixes. Repeated registration is idempotent; subscriptions are shared by collection, so consumers sharing a collection must coordinate unsubscription.

`AsyncPocketbaseLifecycleEvent` contains the previous and current snapshots. Delivery is asynchronous and serialized in revision order. Events are queued atomically with snapshot publication; the current snapshot may therefore be newer than an event being delivered. Revisions increase for each state change and survive handler replacement. Register the Bukkit listener first, then read the snapshot, and process both on one thread using the same revision guard. Ignore revisions at or below the latest processed revision. This also prevents an older initialization snapshot from overwriting a newer event.

Generation invalidates asynchronous work. It increases immediately on readiness loss or reload and before each realtime connection attempt, and survives handler replacement. A collection losing readiness also invalidates work even if other collections remain ready. Record events expose their generation through `getGeneration()`. The legacy record-event constructor remains available and assigns generation `-1`. API record operations fail exceptionally if their generation changes before execution or completion. Consumers must still check the generation when applying results in a later scheduled task. An exceptional write result does not guarantee that the server did not apply the write; writes are never retried automatically.

This Java example runs snapshot and event processing on the Bukkit main thread. `setTemporaryMode` and `reconcileRecords` represent consumer-owned behavior, and `plugin` is the consumer's `JavaPlugin`. Register these handlers with a `Listener` instance.

```java
private PocketbaseApi pocketbase;
private long processedRevision = -1;
private long reconciliationGeneration = -1;

// Call during the consumer's onEnable(), on the main thread.
void initializePocketbase() {
    pocketbase = PocketbaseProvider.getApi();
    Bukkit.getPluginManager().registerEvents(this, plugin);
    setTemporaryMode(true);
    pocketbase.subscribe("lom2_settings");
    acceptSnapshot(pocketbase.getLifecycleSnapshot());
}

@EventHandler
public void onLifecycle(AsyncPocketbaseLifecycleEvent event) {
    Bukkit.getScheduler().runTask(plugin, () -> acceptSnapshot(event.getCurrent()));
}

// All calls run on the main thread, including initialization.
void acceptSnapshot(PocketbaseLifecycleSnapshot snapshot) {
    if (snapshot.getRevision() <= processedRevision) return;
    processedRevision = snapshot.getRevision();
    if (!snapshot.isCollectionReady("lom2_settings")) {
        setTemporaryMode(true);
        reconciliationGeneration = -1;
        return;
    }
    long generation = snapshot.getGeneration();
    if (generation != pocketbase.getLifecycleSnapshot().getGeneration()) return;
    if (generation == reconciliationGeneration) return;
    reconciliationGeneration = generation;

    pocketbase.getFullList("lom2_settings", 100, "", "")
        .whenComplete((records, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
            PocketbaseLifecycleSnapshot current = pocketbase.getLifecycleSnapshot();
            if (current.getGeneration() != generation || !current.isCollectionReady("lom2_settings")) return;
            if (failure != null) {
                reconciliationGeneration = -1;
                plugin.getLogger().warning("Settings fetch failed; temporary mode remains active");
                // The consumer should schedule a bounded retry for request-specific failures.
                return;
            }
            reconcileRecords(records);
            setTemporaryMode(false); // Only after consumer reconciliation succeeds.
        }));
}
```

Subscription-ready means events can flow again. It does not mean the consumer's cache is reconciled. Realtime notifications do not replay missed changes, so recovery requires fetching current records and reconciling them with consumer-owned edits. If reconciliation itself is asynchronous, check the generation again when it finishes before changing the cache or removing the temporary-mode notice. The consumer also owns ordering between live record events and its recovery fetch.

`whenReady()` keeps its authentication callback semantics. Each registration runs asynchronously after the current handler first authenticates successfully, including after startup retries. It is not a recurring recovery notification, does not promise active subscriptions, and can run while realtime is disconnected. Use lifecycle events and snapshots for recovery. Pending callbacks attached to a replaced handler are cancelled; register against the current API again if that callback is still needed.

## Recovery behavior

Authentication establishes initial API availability. Transport errors, request timeouts, and HTTP 5xx responses mark the API unavailable. HTTP 401 triggers reauthentication. Validation errors and collection permission errors stay request-specific. API calls have a 30-second timeout per request, including each page of a list fetch. Responses and lifecycle reasons never expose server error bodies or credentials.

Startup, authentication and realtime connection failures retry indefinitely. Normal recovery uses exponential backoff with a 1-second initial ceiling and a 60-second cap, with jitter between half and the full ceiling. Rejected credentials use a 30-second initial ceiling and a 5-minute cap; failure logging is limited to once per 5 minutes per handler. A session lasting at least a minute resets connection backoff. Reload cancels the old handler and immediately starts with updated configuration and retained registrations.

Recovery validates an existing token with an authentication refresh request, or authenticates again when credentials are rejected. Healthy sessions do not poll API availability. Failed collections retry independently with a 5-second initial ceiling and a 5-minute cap. Collection access is checked before applying its subscription alongside already accepted collections. Readiness reports application acceptance, not a guarantee that record rules permit every event.

Realtime follows PocketBase's [SSE and set-subscriptions protocol](https://pocketbase.io/docs/api-realtime/). A CONNECT message establishes transport connectivity only. A subscription request replacing the server's topic set must succeed before the corresponding collection becomes ready. SSE termination makes subscriptions pending and reconnects; a failed stream also marks API availability unavailable. Idle SSE reads are bounded to 6 minutes to recover from a half-open connection. Shutdown publishes stopped state before client teardown and drains queued lifecycle events asynchronously, with at most a 1-second wait on the Bukkit thread. Consumers should also handle their own disable lifecycle because Bukkit may unregister their listeners during server shutdown.

Consumers must not use implementation classes or types from:

- `PBRunnable`
- `SneakyPocketbase.asyncScope`
- `SneakyPocketbase.pb()`
- `PocketbaseClient`
- `BaseRecord`
- `kotlin.coroutines` or `kotlinx.coroutines` across the plugin seam
- PocketBase Kotlin query, model, serializer, or realtime types across the plugin seam

The implementation may use Kotlin, coroutines, Ktor, serialization, and the PocketBase Kotlin client internally. Those types are intentionally absent from the consumer interface because Paper plugins have isolated classloaders; equal class names loaded separately are not equal JVM classes.

## Paper dependency

Consumers must declare SneakyPocketbase as a required dependency and join its classpath so the Java-compatible interface classes are visible:

```yaml
dependencies:
  server:
    SneakyPocketbase:
      load: BEFORE
      required: true
      join-classpath: true
```

Compile against the same SneakyPocketbase artifact that will be deployed:

```kotlin
dependencies {
    compileOnly("io.github.team-sneakymouse:sneakypocketbase-api:<date-hash>")
}
```

Add the Maven repository URL hosting that release to your consumer's `repositories` block, and declare the supported Paper API as `compileOnly` as well. Do not shade or bundle SneakyPocketbase API classes into consumers; the server plugin supplies them through the joined classpath.

Consumers may choose their own internal Kotlin packaging strategy. Compatibility at the SneakyPocketbase seam depends on keeping Kotlin and PocketBase implementation types out of method parameters, return values, callbacks, events, and shared model inheritance—not on sharing a Kotlin runtime between plugin classloaders.

## Interface verification

Deploy `SneakyPocketbase-<date-hash>.jar` on the server. The `-api.jar` is compile-time only and deliberately contains no Kotlin runtime or implementation classes. Replace `<date-hash>` with the published version you compile against.

`verifyConsumerApi` inspects the compiled Java interface with `javap` and fails if Kotlin, kotlinx, or PocketBase Kotlin implementation types appear. It runs automatically as part of `check`:

```powershell
./gradlew check
```

If a linkage error mentions different class objects for `Function2`, `Continuation`, `CoroutineScope`, or another Kotlin type, search the consumer for calls that bypass `PocketbaseApi` or event/model types that expose an implementation dependency.

## Maven publication

The `consumerApi` publication publishes `io.github.team-sneakymouse:sneakypocketbase-api` using the project version, with API sources and Javadocs. It deliberately excludes plugin implementation dependencies. Install the full shaded plugin JAR separately on the server.

For a repository using username/password authentication, put these properties in your user-level Gradle configuration (`%USERPROFILE%\.gradle\gradle.properties` on Windows), outside this project:

```properties
sneakyrpUsername=YOUR_UPLOAD_USERNAME
sneakyrpPassword=YOUR_UPLOAD_TOKEN
```

Publishing targets `https://maven.sneakyrp.com/releases`. For Reposilite, `sneakyrpUsername` is the token name (the name used when generating it), and `sneakyrpPassword` is its generated secret. The build uses HTTP Basic authentication. Never commit the token or pass it on the command line.

Once configured, publish with:

```powershell
./gradlew publishConsumerApiPublicationToSneakyrpRepository
```

Publication runs `check` first.

GitHub Actions builds and publishes the API on every push to `main`, using the version derived from the checked-out Git commit. The workflow builds with Java 25, then fetches `MAVEN_USERNAME` and `MAVEN_PASSWORD` from `/Maven` in the Infisical `lords-of-minecraft` project, `prod` environment, using the same OIDC setup as OverlayV1. Make `INFISICAL_IDENTITY_ID` and `INFISICAL_DOMAIN` available as GitHub Actions secrets for this repository, and ensure the identity's OIDC policy allows this repository's `main` branch and access to `/Maven`.

MagicSpells is resolved as the compile-only dependency `io.github.team-sneakymouse:magicspells-core:2026.10.09-17f579714ffe` from `https://maven.sneakyrp.com/releases`. No local MagicSpells JAR is required.

Versions use `yyyy.MM.dd-<12-character Git hash>`, with the date taken from the commit timestamp in UTC. Gradle derives this automatically for local builds and CI, and inserts the same version into `paper-plugin.yml`. Each new commit gets its own version; rebuilding the same commit reuses its version. Uncommitted edits keep the current HEAD version, so commit changes before publishing. Builds require a Git checkout.
