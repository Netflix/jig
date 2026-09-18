# jig

[![Maven Central](https://img.shields.io/maven-central/v/com.netflix/com.netflix.tools.jig)](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.jig)
![JDK 25+](https://img.shields.io/badge/JDK-25%2B-blue)

`jig` provides module version resolution, compilation and assembly for the Java Module System. It resolves source modules, local binaries and artifacts published to Maven repositories together, then produces the standard module system arguments accepted by `javac`, `java`, `javadoc`, `jlink` and other tools. The same module graph and compilation work can be reused across tools.

The same module model extends to Maven repositories. Existing artifacts can be located by Java module name, while modules can be installed or published with consumer POMs generated from their descriptors. A repository proxy makes the module namespace available to ordinary Maven clients.

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

For standalone use, `jar` and `jmod` artifacts for the tool are available on Maven Central.

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

## Documentation

The [jig wiki](https://github.com/Netflix/jig/wiki) contains the complete documentation:

- [Getting started](https://github.com/Netflix/jig/wiki/Getting-Started): resolve and run a module, compile source modules, and look up published modules
- [Tool arguments](https://github.com/Netflix/jig/wiki/Tool-Arguments): compose standard options for JDK tools and resolve for a target platform
- [Source modules](https://github.com/Netflix/jig/wiki/Source-Modules): declare version, compilation, runtime access, annotation processing, and dependency integrity metadata
- [Maven modules](https://github.com/Netflix/jig/wiki/Maven-Modules): map Java module names to Maven artifacts, serve the namespace, generate Maven reactors, and configure repositories
- [Publishing modules](https://github.com/Netflix/jig/wiki/Publishing-Modules): assemble, install, and deploy module artifacts
- [Command reference](https://github.com/Netflix/jig/wiki/Command-Reference): command forms and an overview of resolution options

Use `jig --help` for the complete option reference.
