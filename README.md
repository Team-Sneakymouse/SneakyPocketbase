# SneakyPocketbase

Paper plugin providing shared PocketBase access and asynchronous execution facilities for other Sneaky plugins.

## Consumer setup

SneakyPocketbase owns the server runtime copies of Kotlin, Kotlin coroutines, and the PocketBase client. Consumer plugins compile against the same SneakyPocketbase JAR that will be deployed, but must not package their own copies of these libraries. Two classes with the same Kotlin class name are incompatible when Paper loads them through different plugin classloaders.

SneakyPocketbase currently builds with Kotlin `2.2.21`. Consumers should use the same Kotlin compiler version and disable the Kotlin Gradle plugin's automatic standard-library dependency:

```properties
# gradle.properties
kotlin.stdlib.default.dependency=false
```

Add the deployed SneakyPocketbase artifact as a compile-only dependency. Do not also declare `kotlin-stdlib` as a consumer dependency:

```kotlin
// build.gradle.kts
dependencies {
    compileOnly(files("../SneakyPocketbase/build/libs/SneakyPocketbase-1.0.jar"))
}
```

Exclude Kotlin and kotlinx libraries brought in transitively by other dependencies. Apply these exclusions to every dependency configuration that contributes classes to the consumer JAR, not only to compile-time APIs. For example:

```kotlin
compileOnly("example:another-plugin-api:1.0") {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
}
```

Declare the runtime relationship in `paper-plugin.yml` so Paper loads SneakyPocketbase first and makes its classes visible to the consumer:

```yaml
dependencies:
  server:
    SneakyPocketbase:
      load: BEFORE
      required: true
      join-classpath: true
```

### Packaging invariant

The deployed artifacts must satisfy all of the following:

- SneakyPocketbase contains the unrelocated `kotlin/**` and `kotlinx/coroutines/**` runtime classes.
- A consumer contains no `kotlin/**` or `kotlinx/coroutines/**` classes.
- A consumer compiles against the exact SneakyPocketbase artifact deployed with it.
- Dependencies joined to the consumer's classpath do not provide another unrelocated Kotlin runtime.

After building, these PowerShell checks should report `1` for SneakyPocketbase and `0` for the consumer:

```powershell
(& jar tf '..\SneakyPocketbase\build\libs\SneakyPocketbase-1.0.jar' |
    Select-String '^kotlin/jvm/functions/Function2.class$').Count

(& jar tf 'build\libs\ConsumerPlugin-1.0.jar' |
    Select-String '^(kotlin/|kotlinx/coroutines/)').Count
```

If a consumer throws a `LinkageError` mentioning different `Class` objects for a Kotlin type such as `kotlin.jvm.functions.Function2`, inspect every JAR joined to that consumer's classpath for a second bundled Kotlin runtime.
