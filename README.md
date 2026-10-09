# jig

[![Maven Central](https://img.shields.io/maven-central/v/com.netflix/com.netflix.tools.jig)](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.jig)
![JDK 25+](https://img.shields.io/badge/JDK-25%2B-blue)

`jig` provides module version resolution, compilation and assembly for the Java Module System. It resolves source modules, local binaries and artifacts published to Maven repositories together, then produces the standard module system arguments accepted by `javac`, `java`, `javadoc`, `jlink` and other tools. The same module graph and compilation work can be reused across tools.

The same module model extends to Maven repositories. Existing artifacts can be located by Java module name, while modules can be installed or published with consumer POMs generated from their descriptors. A repository proxy makes the module namespace available to ordinary Maven clients.

For Gradle builds, `jig gradle` obtains tool arguments from an explicitly selected project and source set. Gradle owns the build model and dependency resolution; `jig` makes its paths and compiler options composable with standalone JDK tools, without requiring module-system adoption first.

Source modules are provided metadata that isn't supported by the source module descriptor with Javadoc tags and version comments on `requires` directives:

```java
/**
 * @mainClass com.example.application.Main
 */
module com.example.application {
    requires com.example.framework; // @1.2.3
}
```

[`ja`](https://github.com/Netflix/ja) uses `jig` for module resolution, compilation, assembly and publishing. Prefer `ja` for module development tasks, using `jig` directly to bring the same module model to another tool or build.

> [!IMPORTANT]
> This tool is currently in preview. We are collecting feedback for all of the tools together in [Discussions](https://github.com/Netflix/ja/discussions).

## Installation

> [!NOTE]
> Netflix engineers should use the internally bundled toolchain rather than installing this tool separately.

Follow the `ja` [Installation Guide](https://github.com/Netflix/ja#installation) to install the bundled tools, including `jig`.

For standalone use, download the modular JAR from [Maven Central](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.jig). JDK 25 or later is required. Run it directly or as module `com.netflix.tools.jig`:

```sh
java -jar com.netflix.tools.jig-VERSION.jar --help
java --module-path com.netflix.tools.jig-VERSION.jar --module com.netflix.tools.jig --help
```

JMOD artifacts are also published for building custom runtime images.

## Quick start

### Look up a Maven artifact's module name

```sh
jig --lookup-module \
  pkg:maven/org.junit.platform/junit-platform-console@6.1.3
```

```text
org.junit.platform.console
```

### List a module's available versions

```sh
jig --list-module-versions org.junit.platform.console
```

```text
...
6.1.0
6.1.1
6.1.2
6.1.3
```

### Run a published module

Resolve the JUnit Platform Console Launcher by module name and write its `java` arguments to a file:

```sh
jig --add-requires org.junit.platform.console@6.1.3 \
  --resolve-options module-path,module \
  --write-argfile junit.args

java @junit.args --help
```

```text
Usage: junit [OPTIONS] COMMAND
Launches the JUnit Platform for test discovery and execution.
```

## Gradle builds

### Discover projects and source sets

Start with the build root, then use Gradle's project paths and source-set names:

```sh
jig gradle --root-project-dir . --list-project-paths

jig gradle --root-project-dir . \
  --project-path :app --list-source-sets
```

`:` selects the root project; `:app` and `:app:library` select subprojects. Locations are explicit: `jig` does not infer a build or source set from the working directory. The commands below assume a project `:app` with a source set `main`; `test` and custom Java source sets use the same contract.

Project discovery does not run source producers. Unknown projects and source sets are errors; projects without Java source sets return an empty source-set list.

### Resolve arguments for a tool

Select `compile` or `runtime` to use the source set's `compileClasspath` or `runtimeClasspath`, then request only the options your tool accepts:

```sh
jig gradle --root-project-dir . \
  --project-path :app --source-set main --classpath runtime \
  --resolve-options class-path,module-path,add-modules \
  --write-argfile runtime.args

java @runtime.args com.example.Main
```

Replace `com.example.Main` with your application's main class. Arguments are printed to stdout unless `--write-argfile` is supplied; Gradle diagnostics go to stderr. `-r` and `-w` are the corresponding short options.

The resolver supports the native `jig` option vocabulary plus `class-path`, `processor-path`, `source`, `target`, `encoding`, `system` and `add-reads`. Unconfigured options are omitted. Source-path resolution retains generator dependencies, and classpath resolution materializes required dependency outputs. Runtime resolution may compile the selected source set to provide its own outputs.

Gradle supplies the classpath/module-path split according to its inference settings and module-detection rules. Filename-derived automatic module names alone do not move JARs onto the module path. `jig` does not re-resolve or re-mediate the build's dependencies.

### Resolve compiler options

Compiler options are a separate operation, not a preset of general tool options:

```sh
jig gradle --root-project-dir . \
  --project-path :app --source-set main \
  --resolve-compiler-options --write-argfile compile.args

javac @compile.args @sources.args
```

Prepare `sources.args` with the source filenames to compile, including generated Java files and `module-info.java` where applicable. Use a `javac` matching the build's configured compiler/toolchain. The caller selects the executable and source files, and can supply destination overrides such as `-d classes` after the captured options.

`--resolve-compiler-options` implies the compile classpath and is mutually exclusive with `--resolve-options`. It captures the selected `JavaCompile` task's actual prepared spec through Gradle's argument builder, including typed options, compiler argument providers, output directories and annotation-processing settings. Source filenames and launcher JVM options are excluded. The flag is also available for native `jig` module resolution.

The selected compilation task's producers and dependencies run, but its compiler actions do not. For this query, `jig` replaces its execution predicates/disabled state and removes its finalizers. Custom `doFirst`/`doLast` mutations are not executed or reflected in the options. Existing compilation outputs and build history are preserved; spec preparation may create Gradle temporary directories.

### Compatibility and caching

`jig` runs on JDK 25 or later. The Gradle process uses the root project's wrapper, or `gradle` on PATH when no wrapper is present, and must run on a JDK supported by that Gradle version. Compiler toolchains remain Gradle's responsibility; resolving options does not select the compiler executable for a subsequent standalone invocation.

Integration tests parameterize these Gradle/JDK combinations:

| Gradle versions | Gradle runtime JDK |
| --- | --- |
| 3.5.1, 4.10.3 | 8 |
| 5.6.4, 6.6.1, 6.9.4 | 11 |
| 7.6.6, 8.5, 9.6.1, 9.7.1 | 17 |

Tests cover generated sources, annotation processing, native headers, task overrides, module inference and configuration-cache replay. Real-build integration tests currently run on Linux/macOS; Windows wrapper launching is implemented but not covered by those tests. Argument providers follow Gradle's lifecycle, including early evaluation during dependency discovery in older versions.

Every query invokes Gradle. Stable requests reuse its configuration cache when enabled, and Gradle owns dependency/output freshness; `jig` does not maintain an independent build-model snapshot. Queries currently disable isolated-projects mode. Module detection and compiler-spec capture use Gradle internal APIs, so compatibility is tested rather than assumed; compiler capture fails rather than substituting guessed options when its API bridge is unsupported.

## Documentation

The [wiki](https://github.com/Netflix/jig/wiki) covers:

- [Getting Started](https://github.com/Netflix/jig/wiki/Getting-Started)
- [Tool Arguments](https://github.com/Netflix/jig/wiki/Tool-Arguments)
- [Source Modules](https://github.com/Netflix/jig/wiki/Source-Modules)
- [Maven Modules](https://github.com/Netflix/jig/wiki/Maven-Modules)
- [Publishing Modules](https://github.com/Netflix/jig/wiki/Publishing-Modules)
- [Command Reference](https://github.com/Netflix/jig/wiki/Command-Reference)

Use `jig --help` for the native option reference and `jig gradle --help` for the Gradle contract.
