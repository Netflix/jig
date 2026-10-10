# jig

[![Maven Central](https://img.shields.io/maven-central/v/com.netflix/com.netflix.tools.jig)](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.jig)
![JDK 25+](https://img.shields.io/badge/JDK-25%2B-blue)

`jig` resolves arguments for standard Java tools, including `javac`, `java`, `javadoc` and `jshell`.

Given a module source path, it reads `module-info.java` to resolve dependencies and compiles source modules when a tool needs binaries. Dependency versions and other metadata can be supplied in source comments.

For Maven and Gradle projects, it uses the build tool's own dependency resolution. You can run JDK tools with the project's dependencies without maintaining a separate classpath, duplicating dependency declarations, or opening an IDE.

The source module model supports additional metadata through Javadoc tags and version comments on `requires` directives:

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

## Interoperability

### Gradle

Discover projects and source sets from the build root. Project paths also address included builds, such as `:build-logic:plugin`:

```sh
jig gradle --root-project-dir . --list-project-paths

jig gradle --root-project-dir . \
  --project-path :app --list-source-sets
```

For a project `:app` with a source set `main`, resolve runtime arguments and launch your main class:

```sh
jig gradle --root-project-dir . \
  --project-path :app --source-set main --classpath runtime \
  --resolve-options class-path,module-path,add-modules \
  --write-argfile runtime.args

java @runtime.args com.example.Main
```

For compilation, resolve compiler options separately:

```sh
jig gradle --root-project-dir . \
  --project-path :app --source-set main \
  --resolve-compiler-options --write-argfile compile.args

javac @compile.args @sources.args
```

Replace the project, source set and main class with those of your application. Prepare `sources.args` with the source filenames to compile, including generated sources and `module-info.java` where applicable, and use a `javac` matching the build's configured toolchain.

### Maven

Discover projects from the Maven project base directory:

```sh
jig maven --project-base-dir . --list-projects
```

Select a project by `groupId:artifactId` or the shorter `:artifactId` form, then request its `compile`, `runtime` or `test` scope. For an application project with artifact ID `app`:

```sh
jig maven --project-base-dir . \
  --project :app --scope runtime \
  --resolve-options class-path,module-path,add-modules \
  --write-argfile runtime.args

java @runtime.args com.example.Main
```

## Documentation

The [wiki](https://github.com/Netflix/jig/wiki) covers:

- [Getting Started](https://github.com/Netflix/jig/wiki/Getting-Started)
- [Tool Arguments](https://github.com/Netflix/jig/wiki/Tool-Arguments)
- [Source Modules](https://github.com/Netflix/jig/wiki/Source-Modules)
- [Maven Modules](https://github.com/Netflix/jig/wiki/Maven-Modules)
- [Publishing Modules](https://github.com/Netflix/jig/wiki/Publishing-Modules)
- [Command Reference](https://github.com/Netflix/jig/wiki/Command-Reference)

Use `jig --help` for native module resolution, `jig gradle --help` for Gradle interoperability, and `jig maven --help` for Maven interoperability.
