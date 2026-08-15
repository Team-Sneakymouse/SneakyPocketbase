# SneakyPocketbase

Paper plugin providing PocketBase access and asynchronous execution facilities for other Sneaky plugins.

## Consumer interface

Consumers must enter through `PocketbaseProvider.getApi()`. The returned `PocketbaseApi` interface uses only JDK types: JSON strings, `Runnable`, collections, and `CompletableFuture`.

```kotlin
val pocketbase = PocketbaseProvider.getApi()

pocketbase.whenReady {
    pocketbase.subscribe("example_collection")
}

val records: List<String> = pocketbase.getFullList(
    "example_collection",
    100,
    "-created",
    "enabled = true",
).join()
```

Realtime updates are published as `AsyncPocketbaseEvent`. Its action is the Java `AsyncPocketbaseEvent.Action` enum, and `recordJson` contains the raw record JSON for the consumer to deserialize.

```kotlin
@EventHandler
fun onPocketbaseUpdate(event: AsyncPocketbaseEvent) {
    if (event.collectionName != "example_collection") return
    val record = Json.decodeFromString<ExampleRecord>(event.recordJson)
}
```

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
    compileOnly(files("../SneakyPocketbase/build/libs/SneakyPocketbase-1.0-api.jar"))
}
```

Consumers may choose their own internal Kotlin packaging strategy. Compatibility at the SneakyPocketbase seam depends on keeping Kotlin and PocketBase implementation types out of method parameters, return values, callbacks, events, and shared model inheritance—not on sharing a Kotlin runtime between plugin classloaders.

## Interface verification

Deploy `SneakyPocketbase-1.0.jar` on the server. The `-api.jar` is compile-time only and deliberately contains no Kotlin runtime or implementation classes.

`verifyConsumerApi` inspects the compiled Java interface with `javap` and fails if Kotlin, kotlinx, or PocketBase Kotlin implementation types appear. It runs automatically as part of `check`:

```powershell
./gradlew check
```

If a linkage error mentions different class objects for `Function2`, `Continuation`, `CoroutineScope`, or another Kotlin type, search the consumer for calls that bypass `PocketbaseApi` or event/model types that expose an implementation dependency.
