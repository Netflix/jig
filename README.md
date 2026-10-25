# jig

[![Maven Central](https://img.shields.io/maven-central/v/com.netflix/com.netflix.tools.jig)](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.jig)
![JDK 25+](https://img.shields.io/badge/JDK-25%2B-blue)

`jig` resolves arguments for standard Java tools, including `javac`, `java`, `javadoc` and `jshell` given a module source path or Gradle or Maven project.

Given a module source path, it reads `module-info.java` to resolve dependencies and compiles source modules when a tool needs binaries. Dependency versions and other metadata can be supplied in source comments:

```java
/**
 * @mainClass com.example.application.Main
 */
module com.example.application {
    requires com.example.framework; // @1.2.3
}
```

For Maven and Gradle projects, it uses the build tool's own dependency resolution.

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

Resolved options are emitted using Java argument-file syntax, both on stdout and with `--write-argfile`. Quoting preserves spaces and special characters for tools that support `@file` arguments. Prefer writing an argument file and passing it to the consuming tool; argument-file support varies between JDK tools (`jdeps`, for example, does not support it in JDK 25).

Shell command substitution, such as `tool $(jig ...)`, is only a convenience for simple arguments. The shell does not interpret quotes emitted by `jig`, so this form does not preserve arguments that require quoting and may perform filename expansion.

### Gradle

Resolve arguments from Gradle projects and source sets, including composite builds. Use `--list-project-paths` and `--list-source-sets` to discover selections.

For a project `:app` with a source set `main`, resolve runtime arguments and launch your main class:

```sh
jig gradle --root-project-dir . \
  --project-path :app --source-set main --classpath runtime \
  --resolve-options class-path,module-path,add-modules \
  --write-argfile runtime.args

java @runtime.args com.example.Main
```

Use `--resolve-compiler-options` for the source set's effective compiler arguments. See [Build Tool Projects](https://github.com/Netflix/jig/wiki/Build-Tool-Projects) for compilation examples and supported options.

### Maven

Resolve arguments from a Maven project's `compile`, `runtime` or `test` scope. Use `--list-projects` to discover project selectors. For an application with artifact ID `app`:

```sh
jig maven --project-base-dir . \
  --project :app --scope runtime \
  --resolve-options class-path,module-path,add-modules \
  --write-argfile runtime.args

java @runtime.args com.example.Main
```

### Cached build queries

Discovery and resolved arguments are cached across branches and worktrees. Cache hits skip the build tool and output preparation; use `--refresh` to query the build again. Unused entries are pruned automatically. See [Build Tool Projects](https://github.com/Netflix/jig/wiki/Build-Tool-Projects#caching) for details.

### Publishing module artifacts

Install module artifacts locally or publish them to Maven repositories and Maven Central with `jig maven install`, `deploy` and `deploy-central`. See [Publishing Modules](https://github.com/Netflix/jig/wiki/Publishing-Modules).

### Module proxy

Start the module proxy as a standalone command:

```sh
jig serve --listen 127.0.0.1:8080
```

Without `--listen`, it binds to `127.0.0.1` on a random available port. It prints the repository URL to standard output and traces proxy and backend activity to standard error.

## Documentation

The [wiki](https://github.com/Netflix/jig/wiki) covers:

- [Getting Started](https://github.com/Netflix/jig/wiki/Getting-Started)
- [Tool Arguments](https://github.com/Netflix/jig/wiki/Tool-Arguments)
- [Build Tool Projects](https://github.com/Netflix/jig/wiki/Build-Tool-Projects)
- [Source Modules](https://github.com/Netflix/jig/wiki/Source-Modules)
- [Maven Modules](https://github.com/Netflix/jig/wiki/Maven-Modules)
- [Publishing Modules](https://github.com/Netflix/jig/wiki/Publishing-Modules)
- [Command Reference](https://github.com/Netflix/jig/wiki/Command-Reference)

Use `jig --help` for native module resolution, `jig gradle --help` for Gradle interoperability, and `jig maven --help` for Maven interoperability.
