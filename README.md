# jig

[![Maven Central](https://img.shields.io/maven-central/v/com.netflix/com.netflix.tools.jig)](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.jig)
![JDK 25+](https://img.shields.io/badge/JDK-25%2B-blue)

`jig` makes the Java module descriptor the source of truth for resolving, compiling, and publishing Java modules.

The module system gives modules stable identities and explicit dependencies, but does not select dependency versions or locate missing modules in artifact repositories. Version requirements sit naturally beside `requires` directives:

```java
module com.example.application {
    requires com.example.framework; // @1.2.3
}
```

From that descriptor, source modules, local binaries, and published Maven artifacts are resolved into one consistent module graph. Mapping between Java module names and Maven coordinates allows existing Maven artifacts to be consumed as modules without changing how they are published.

The same bridge works in reverse. Module artifacts can be installed locally, deployed to a Maven repository, or published to Maven Central as ordinary Maven components, with consumer POMs generated from their module descriptors.

The resolved graph can also drive incremental compilation and produce standard arguments for `javac`, `java`, `javadoc`, `jlink`, and other JDK tools. There is no separate project model to keep in agreement.

- Resolve Maven artifacts by Java module name and version
- Combine source, local, and published modules in one graph
- Compile source modules incrementally and reuse work across tools
- Install or deploy modules with generated Maven consumer metadata
- Produce standard module-system arguments for existing JDK tools

[`ja`](https://github.com/Netflix/ja) uses it for module resolution, compilation, assembly, and publishing. Use it directly to bring the same module model to another tool or build.

> [!IMPORTANT]
> This tool is currently in preview. We are collecting all preview feedback in the [`ja` repository](https://github.com/Netflix/ja): use [Issues](https://github.com/Netflix/ja/issues) to report problems and [Discussions](https://github.com/Netflix/ja/discussions) for feedback, questions, and suggestions.

## Installation

> [!NOTE]
> Netflix engineers should use the internally bundled toolchain rather than installing this tool separately.

Follow the `ja` [Installation Guide](https://github.com/Netflix/ja#installation) to install the bundled tools, including `jig`.

For standalone use, `jar` and `jmod` artifacts for the tool are available on Maven Central.

## Resolve, compile, and run a module

Start with an application JAR already available locally:

```sh
jig --module-path /path/to/com.example.app.jar \
  --module com.example.app \
  --resolve-options module-path,module=main \
  --write-argfile /path/to/launch.args

java @/path/to/launch.args
```

The supplied JAR remains on the module path. Missing dependencies are resolved from Maven repositories using the versions recorded in their module descriptors.

The generated argument file contains the resolved `--module-path` and the application's `--module` argument. It contains ordinary `java` options and can be combined with arguments supplied directly by the caller.

### Complete an existing module path

For example, start with only the JUnit Jupiter root module:

```sh
jig --module-path /path/to/org.junit.jupiter-6.1.3.jar \
  --module org.junit.jupiter \
  --resolve-options module-path
```

The generated module path retains that JAR and adds its missing dependencies from Maven repositories:

```text
--module-path
/path/to/org.junit.jupiter-6.1.3.jar:<repository>/org.junit.jupiter.api-6.1.3.jar:<repository>/org.junit.jupiter.engine-6.1.3.jar:<repository>/org.junit.jupiter.params-6.1.3.jar:<repository>/org.junit.platform.commons-6.1.3.jar:<repository>/org.junit.platform.engine-6.1.3.jar:<repository>/org.opentest4j-1.3.0.jar
```

The root module's descriptor supplies the dependency versions, so the local module path does not need to contain them in advance.

### Compile a source module incrementally

Suppose `src/com.example.app/module-info.java` declares the application's entry point:

```java
/**
 * @mainClass com.example.app.Main
 */
module com.example.app {
}
```

Generate the launch arguments and run the application:

```sh
output=/path/to/output
mkdir -p "$output"

jig --module-source-path src \
  -m com.example.app \
  -r module-path,patch-module,module=main \
  -w "$output/launch.args"

java @"$output/launch.args"
```

Because the requested `module-path` requires the module in binary form and `module-source-path` was not requested, the source module is compiled into managed output. Later invocations reuse unchanged output. An implementation change recompiles the affected source, while a declaration change recompiles the module. Module hashes prevent changes from unnecessarily crossing module boundaries.

The requested options determine how the current module is presented. Requesting only `module-path` produces one complete module-path entry:

```sh
jig --module-source-path src \
  -m com.example.app \
  -r module-path
```

```text
--module-path
<complete-current-module>
```

Requesting `patch-module` allows unchanged content to remain in a reusable base:

```text
--module-path
<reused-module-base>
--patch-module
com.example.app=<changed-module-content>
--module
com.example.app/com.example.app.Main
```

Both forms present the same module. The second avoids copying unchanged content into a new complete module. Classes, resources, and multi-release content are included in either form.

Compilation warnings should be fixed, not hidden by incremental compilation. It enables all standard javac lint warnings and keeps them visible when it reuses existing output. Reused groups begin with `[diagnostics replayed from previous compilation]` so they can be distinguished from feedback about the current change. Use `--no-compile-diagnostics` when the warnings are not useful to the caller, or `--recompile` to check the complete source again.

### Compile with an external compiler

Request both `module-path` and `module-source-path` to keep selected source modules as source input for an external compiler:

```sh
output=/path/to/output
mkdir -p "$output/modules"

jig --module-source-path src \
  -m com.example.app \
  --compile-time \
  -r module-path,module-source-path,module=list,release \
  -w "$output/compile.args"

javac -d "$output/modules" @"$output/compile.args"

jig --module-path "$output/modules" \
  -m com.example.app \
  -r module-path,add-modules \
  -w "$output/launch.args"

java @"$output/launch.args" \
  --module com.example.app/com.example.app.Main
```

The first invocation generates the graph options needed by `javac`, while `-d` is supplied directly to the compiler. `--compile-time` includes dependencies reachable only through `requires static`. The second invocation uses the compiled output as its input and generates the runtime graph options needed by `java`. Each invocation requests only the options consumed by the following command.

## Resolve published modules

Resolve modules directly by Java module name and version, without a source module or local JAR:

```sh
jig --add-requires org.junit.platform.console@6.1.2 \
  --add-requires org.junit.jupiter.engine@6.1.2 \
  -r module-path,add-modules
```

Each `--add-requires` option adds a published root module. It may be repeated or combined with source modules and an existing module path. Transitive dependencies are resolved using the versions recorded in module descriptors.

## Look up a Maven artifact's module name

Find the Java module name provided by a Maven package:

```sh
jig --lookup-module pkg:maven/org.apache.commons/commons-configuration2@2.15.1
```

```text
org.apache.commons.configuration2
```

Omit the version to inspect the latest available version:

```sh
jig --lookup-module pkg:maven/org.apache.commons/commons-configuration2
```

```text
org.apache.commons.configuration2
```

A result is returned only when the artifact can subsequently be resolved by the returned module name. Repository access follows Maven settings.

## Compose arguments for other tools

Use `-r, --resolve-options` to name the standard options accepted by the consuming tool. Option specifications generally match the corresponding JDK option without the leading `--`:

```sh
jig --module-source-path src \
  -m com.example.app \
  -r module-path,module-source-path,release
```

Only requested options that apply to the resolved graph are emitted. The result is written to standard output by default; `-w, --write-argfile <path>` writes it as a Java argument file.

Graph-independent options do not require a root module. For example, `jig -r release` emits `--release` with the current JVM feature version. When a module graph is selected, source-module metadata determines the emitted release instead.

The `--module` option differs between JDK tools, so its output specification includes the required form:

- `module=single` emits one module name
- `module=list` emits a comma-separated list for tools such as `javac` and `javadoc`
- `module=main` emits a module and main class for tools such as `java` and `jpackage`
- `module=roots` emits one root with `--module` or multiple roots with `--add-modules`

`module` is an alias for `module=single`. `module=roots` must be accompanied by `add-modules`. With `module=main` and no `-m`, the only module declaring a main class is selected automatically. Select it explicitly when more than one module declares a main class.

Graph selection is independent of the options being emitted. Add `--compile-time` to include dependencies reachable only through `requires static`:

```sh
jig --module-source-path src \
  -m com.example.app \
  --compile-time \
  -r module-path,module-source-path,module=list,release
```

Use `--validate-runtime-access` when the consuming operation will run code. Validation does not emit runtime access options; request those options explicitly with `-r`.

Invoke `jig` separately for each consuming tool. Use `--help` for the complete list of supported resolve options.

## Resolve for a target platform

Published modules are placed on the generated module path as unclassified JARs by default.

`--target-platform` selects artifacts published with an `os-maven-plugin` classifier, falling back to an artifact without a classifier. Use `--target-platform CURRENT` to select the classifier for the current operating system and architecture. Classified JARs are considered only when this option is present.

`--prefer-jmod` selects a JMOD when one is available, falling back to a JAR. Without an explicit target platform, JMOD lookup tries the current operating system and architecture before an unclassified JMOD. This implicit JMOD target does not enable classified JAR selection.

For example, generate the module path and root modules accepted by `jlink`:

```sh
jig --prefer-jmod \
  --add-requires com.netflix.tools.ja@1.2.3 \
  -r module-path,add-modules
```

A dependency that replaces a system module must be placed on `--upgrade-module-path`. Because `jlink` does not accept that option, such a graph cannot be linked directly.

## Build a Maven deployment

Compile the module and package its binary, sources, and Javadoc as flat, module-named resources:

```sh
module=com.example.app
version=1.2.3
output=/path/to/output
artifacts="$output/artifacts"

mkdir -p "$output/args" "$output/modules" "$artifacts"

jig --module-source-path src \
  -m "$module" \
  --module-version "$version" \
  --compile-time \
  -r module-path,module-source-path,module=list,module-version,release \
  -w "$output/args/compile.args"

javac -d "$output/modules" @"$output/args/compile.args"

jig --module-source-path src \
  -m "$module" \
  --compile-time \
  -r module-path,module-source-path,module=list,release \
  -w "$output/args/javadoc.args"

javadoc @"$output/args/javadoc.args" \
  -tag 'mainClass:a:Main class:' \
  -d "$output/javadoc"

jig --module-source-path src \
  -m "$module" \
  -r main-class \
  -w "$output/args/jar.args"

jar --create --file "$artifacts/$module.jar" \
  @"$output/args/jar.args" \
  -C "$output/modules/$module" .

jar --create --file "$artifacts/$module-sources.jar" \
  -C "src/$module" .

jar --create --file "$artifacts/$module-javadoc.jar" \
  -C "$output/javadoc" .
```

The artifact directory is deliberately not a Maven repository layout:

```text
<output>/artifacts/
|-- com.example.app.jar
|-- com.example.app-sources.jar
`-- com.example.app-javadoc.jar
```

The version in `module-info.class` is authoritative. Module descriptors provide the names, versions, and dependencies used to generate consumer POMs and create the Maven repository layout:

```sh
jig maven install "$artifacts"
jig maven deploy --repository /path/to/repository "$artifacts"
jig maven deploy --repository releases=https://repository.example/releases "$artifacts"
jig maven deploy-central "$artifacts"
```

Put shared consumer metadata in `<artifact-directory>/consumer.pom`. Use `--merge-consumer-pom <file>` to select a different file.

## Source modules

A source module's `module-info.java` declares its direct dependencies and records the metadata needed to resolve, compile, and run it.

The module system does not include dependency versions in `requires` directives. Append `// @version` to record them:

```java
/**
 * @release 21
 * @mainClass com.example.cli.Main
 */
module com.example.cli {
    requires com.example.library; // @1.2.3
}
```

Dependency versions also participate in transitive resolution. Conflicts follow Maven's nearest-wins rule, after which the module system validates the selected graph.

Module Javadoc records settings that cannot be expressed by standard module directives:

- `@release` selects the Java release used for compilation
- `@mainClass` declares the entry point used by `module=main` and `main-class`
- `@enablePreview` enables preview compilation

Without `@release`, compilation uses the consuming toolchain's default release. Preview features require `@release` to match the running JDK.

### Annotation processors

Declare an annotation processor with `requires static` and select it with `@processWith`:

```java
/** @processWith com.example.processor */
module com.example.application {
    requires static com.example.processor; // @1.2.3
}
```

`requires static` makes the processor available during compilation without adding it to the runtime graph.

Requesting `processor-module-path` places the selected processor and its dependencies on `--processor-module-path`. The processor may be a source module or a published module.

The selected module must provide `javax.annotation.processing.Processor` through its module descriptor or service configuration. Resolution fails if the module is absent from the graph or does not provide that service.

### Runtime access

Following [Integrity by Default](https://openjdk.org/jeps/8305968), runtime access that can weaken module integrity must be authorized by a selected root module.

Record access requirements that cannot be expressed by standard module directives with module Javadoc tags:

| Tag | Compiler option | Launcher option |
|---|---|---|
| `@enableNativeAccess module` | - | `--enable-native-access=module` |
| `@enableFinalFieldMutation module` | - | `--enable-final-field-mutation=module` |
| `@addOpens module/package=target` | - | `--add-opens module/package=target` |
| `@addExports module/package=target` | `--add-exports module/package=target` | `--add-exports module/package=target` |

```java
/**
 * @enableNativeAccess com.example.lib
 * @addOpens java.base/java.lang=com.example.framework
 */
module com.example.framework {
    requires com.example.lib; // @1.0.0
}
```

When `jig` compiles a source module, it stores these requirements in the generated `module-info.class` as a `ModuleRuntimeAccess` attribute. The requirements therefore travel with published JARs and JMODs.

Recording a requirement does not authorize it. A published dependency cannot authorize its own access. Authorization must come from an explicitly selected source root or the corresponding command-line option, and is not inherited through `requires` directives. System modules are trusted, so requirements recorded in the current runtime image are applied automatically.

`--validate-runtime-access` checks the requirements of every module in the resolved graph against those authorizations. Resolution fails when a non-system module requirement has not been authorized. Validation does not emit runtime access options; request each required option explicitly with `-r`.

Every operand must refer to modules and, where applicable, packages in the resolved configuration. Broad pseudo-modules such as `ALL-UNNAMED` are not supported.

### Source module paths

Point to a directory whose immediate subdirectories are source modules:

```sh
jig --module-source-path src -m com.example.app
```

Map module names to source directories explicitly:

```sh
jig --module-source-path com.example.app=app/src/main/java \
  --module-source-path com.example.core=core/src/main/java \
  --module-source-path com.example.api=api/src/main/java \
  -m com.example.app
```

A wildcard can substitute the module name:

```sh
jig --module-source-path 'src/*/main/java' -m com.example.app
```

`--module-source-path` may be repeated, and the forms may be combined.

### Source options

Request `source-path` to combine the source directories of selected local modules with `sources` artifacts resolved for published modules. A missing optional sources artifact does not affect module resolution.

Request `module-source-path` to generate the source-module path used by compile-time tools. Source modules cause their `requires static` dependencies to be resolved so they can be compiled. `--compile-time` determines whether those dependencies are included in the generated options.

A module is not selected merely because it is visible on `--module-source-path`. If a selected module depends on an unselected source module, that dependency is resolved from published modules instead.

Use `module=single`, `module=list`, `module=main`, or `module=roots` to choose how selected module names are emitted. The `release` option requires all selected source modules to declare the same release.

### Verify dependency integrity

A `module-info.hash` file alongside `module-info.java` records the expected content hash of each resolved binary dependency:

```text
com.example.lib@1.2.3=module:sha256:a1b2c3d4e5f6...
org.apache.commons.io@2.15.1=module:sha256:f6a7b8c9d0e1...
```

`--update-module-hashes` reconciles the file with the resolved `--module-path`. It verifies retained dependencies, adds new dependencies, and removes entries that are no longer present. Changed content at an existing module and version is rejected.

`--verify-module-hashes` requires every dependency on the resolved binary `--module-path` to have one matching entry. Entries for dependencies that are no longer resolved are permitted; `--update-module-hashes` removes them.

Without either option, the file is not read or written.

The hash file verifies the result of resolution. It does not select versions, record repository origins, or become part of published consumer metadata.

## Maven module namespace

The module system identifies dependencies by Java module name, while Maven repositories locate artifacts by group and artifact coordinates. The module namespace maps between those identities so modules can be requested by name and version.

Artifacts with an explicit module descriptor or `Automatic-Module-Name` retain that identity. When an artifact has no Java module name, a stable automatic-module name is derived without modifying the JAR.

### Module location

Maven group IDs and Java module names often share a reverse-DNS namespace, but a module name does not identify where that namespace ends or how the remainder maps to a Maven artifact ID.

Two coordinates are relevant:

- The **canonical module coordinate** is derived from module identity as `<namespace>:<module-name>`
- The **canonical Maven coordinate** is the artifact's published location and may have a different group ID or artifact ID

The canonical module artifact ID is always the complete Java module name. Consumers request this stable coordinate, which either contains the artifact or redirects to its canonical Maven coordinate.

Within a Java module name, `_` represents `-` in the corresponding Maven namespace. It is translated when deriving Maven group IDs and artifact-name candidates, but the canonical module artifact ID remains the exact Java module name.

| Module name | Canonical module coordinate | Canonical Maven coordinate |
|---|---|---|
| `org.osgi.core` | `org.osgi:org.osgi.core` | `org.osgi:org.osgi.core` |
| `org.slf4j` | `org.slf4j:org.slf4j` | `org.slf4j:slf4j-api` |
| `com.fasterxml.jackson.databind` | `com.fasterxml:com.fasterxml.jackson.databind` | `com.fasterxml.jackson.core:jackson-databind` |
| `com.github.some_author.library` | `com.github.some-author:com.github.some_author.library` | `com.github.some-author:library` |

Module authors can establish the canonical location in either of two ways:

- Publish the artifact directly at its canonical module coordinate
- Publish a Maven relocation POM from the canonical module coordinate to its canonical Maven coordinate

When neither is possible, the next-best convention is to use the complete module name as the artifact ID at the canonical Maven coordinate. For example, `org.eclipse.sisu:org.eclipse.sisu.plexus` unambiguously identifies module `org.eclipse.sisu.plexus` when the group ID belongs to that module's namespace.

Existing artifacts may use other Maven naming conventions. As a compatibility fallback, likely coordinates, published BOMs, and known ecosystem aliases are searched and the resulting module identity is verified. Successful locations are exposed at the canonical module coordinate and retained in the local Maven repository.

A relocation POM makes the mapping explicit:

```xml
<project>
  <groupId>org.slf4j</groupId>
  <artifactId>org.slf4j</artifactId>
  <version>1.0.0</version>
  <packaging>pom</packaging>
  <distributionManagement>
    <relocation>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </relocation>
  </distributionManagement>
</project>
```

A relocation published at the earliest applicable version can direct later versions without requiring a relocation POM for every release.

### Serve the module namespace

Start a Maven repository proxy for the canonical module namespace:

```sh
jig maven serve --listen 127.0.0.1:8080
```

The repository URL is written to standard output and request logs are written to standard error. Configure the URL as a normal Maven repository, then request modules through their canonical coordinates:

```sh
mvn dependency:get \
  -Dartifact=org.slf4j:org.slf4j:2.0.17 \
  -DremoteRepositories=jig::default::http://127.0.0.1:8080/
```

The proxy serves ordinary Maven artifacts and metadata. Clients do not need a custom artifact type or extension.

### Module POMs

Generate a Maven reactor for IDE import and other tools that understand the Maven project model:

```sh
cd /path/to/project

jig --module-source-path src \
  -m com.example.app \
  --generate-module-poms .
```

The root must be a common ancestor of every selected source module. The command merges the reactor into its `pom.xml` and writes `module-info.pom` beside each selected `module-info.java`. Maven accepts these explicit POM paths as reactor modules.

The root defaults to the coordinate `local:<directory-name>-parent:0` when its POM does not provide coordinates. Module POMs use canonical module coordinates with version `0`. Each module directory is configured as its Java source and resource root. Java sources, module metadata, and Maven output are excluded from resource copying. The module's configured release determines the Maven compiler language level.

The command also writes `.mvn/maven.config`, configuring the resolved module repository as `maven.repo.local.tail`. Static dependencies are included and dependency sources are resolved into that repository before import.

### Install and deploy

`jig maven install` and `jig maven deploy` consume a directory of flat, module-named artifacts. The main JAR must contain `module-info.class` with a module version. Optional resources use `-sources.jar`, `-javadoc.jar`, and `.jmod` suffixes.

A consumer POM is generated from each module descriptor. Non-system `requires` directives become Maven dependencies. A plain `requires static` becomes an optional compile dependency. Because Maven cannot express a dependency that is transitive at compile time but optional at runtime, `requires static transitive` remains non-optional so downstream compilation continues to work. `jig` still uses the module descriptor to omit either static form from runtime resolution. Dependencies outside the artifact directory are mapped back to their original Maven coordinates through the same module-location contract used for resolution.

For deployment, `<artifact-directory>/consumer.pom`, or the file selected by `--merge-consumer-pom`, contributes descriptive metadata and `distributionManagement` to each consumer POM. Parent inheritance and property interpolation are supported. Module coordinates, versions, packaging, and dependencies remain authoritative. Deploy accepts a repository path, an explicit `--repository <id=uri>`, or the consumer POM's release repository. A repository path is published through its normalized `file:` URI.

Add `--sign` to deploy detached OpenPGP signatures with every artifact. Signing reads `MAVEN_GPG_KEY`, with optional `MAVEN_GPG_KEY_FINGERPRINT` and `MAVEN_GPG_PASSPHRASE`, from the environment.

`jig maven deploy-central` requires binary, sources, and Javadoc JARs. Snapshots are rejected. Component and POM coordinates, project name, description and URL, licenses, developers, and SCM metadata are validated before upload.

Signing is mandatory and reads `MAVEN_GPG_KEY`, with optional `MAVEN_GPG_KEY_FINGERPRINT` and `MAVEN_GPG_PASSPHRASE`, from the environment. Callers do not provide `.asc` files. Every artifact is signed before the signature set is verified, checksums are generated, and the Maven repository bundle is created.

Generate a user token in the Central Portal and add its username and password to the `central` server in `~/.m2/settings.xml`. These are not account login credentials.

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>token username</username>
      <password>token password</password>
    </server>
  </servers>
</settings>
```

In CI, set both `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` instead. The bundle is submitted for automatic publication and the command waits for completion. Add `--manual` to stop after validation and leave publication for approval in the Central Portal. Portal validation remains authoritative for namespace ownership, established signing keys, and duplicate releases. Validation failures are reported with the deployment details returned by Central. The deployment UUID is written to standard output after publication or validation. `--name` supplies an optional deployment name.

Maven Resolver owns local-repository locking and metadata for install, repository authentication and mirrors from Maven settings, and the repository layout for deploy.

The module proxy also generates consumer POMs without modifying backing JARs. Each POM records the backing Maven package URL in an `origin` property.

### Module naming

Module identity is selected in this order:

1. The name in `module-info.class`
2. `Automatic-Module-Name` in the JAR manifest
3. A Java module name established by the latest published version of the artifact
4. A valid name from the OSGi `Bundle-SymbolicName` manifest header
5. A name derived from the Maven coordinates

Consulting the latest published version allows an older unnamed JAR to use a Java module name adopted by a newer release. OSGi directives and attributes are not part of the module name and do not override an explicit Java module identity.

When no published version or manifest supplies a name, the group ID and artifact ID are normalized using rules [similar to the JDK](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/module/ModuleFinder.html#automatic-modules-heading). Duplicate prefixes are removed:

| Maven coordinate | Derived module name |
|---|---|
| `com.github.ricksbrown:cowsay` | `com.github.ricksbrown.cowsay` |
| `com.netflix.spectator:spectator-api` | `com.netflix.spectator.api` |
| `com.netflix.spectator:spectator-ext-jvm` | `com.netflix.spectator.ext.jvm` |
| `org.ow2.asm:asm` | `org.ow2.asm` |
| `org.apache.commons:commons-lang3` | `org.apache.commons.lang3` |
| `org.junit.platform:junit-platform-commons` | `org.junit.platform.commons` |
| `javax.annotation:jsr-275` | `javax.annotation.jsr275` |
| `commons-cli:commons-cli` | `commons.cli` |

### Maven settings

Repository configuration is read from the distribution settings at:

```text
$JAVA_HOME/conf/com.netflix.tools.jig/settings.xml
```

and merged with user settings from:

```text
~/.m2/settings.xml
```

Normal Maven global and user precedence applies. Distribution settings define repository topology, mirrors, and server IDs. User settings provide credentials, local repository configuration, and other user-specific values. To encrypt credentials, follow Maven 3's [password encryption guide](https://maven.apache.org/guides/mini/guide-encryption.html) and keep the generated master password in `~/.m2/settings-security.xml`.

When no distribution settings file is present, Maven Central and the user settings remain available.

## Command reference

```text
jig [options]
jig maven install <artifact-directory>
jig maven deploy [--merge-consumer-pom <file>] [--repository <id=uri|path>] [--sign] <artifact-directory>
jig maven deploy-central [--merge-consumer-pom <file>] [--name <name>] [--manual] <artifact-directory>
jig maven serve [--listen <host:port>]
```

Resolution accepts the familiar JDK options `--module-path`, `--module-source-path`, and `--module`. Use `--add-modules` to add roots already available locally, or `--add-requires <module>@<version>` to resolve published roots.

With no resolve or generation operation requested, the selected module graph is resolved and validated.

Use `-r, --resolve-options` to write standard JDK options to standard output, or add `-w, --write-argfile` to write them to a Java argument file. Other operations generate module POMs, manage module hashes, select platform artifacts, and look up module names and versions.

Source-module outputs unused for seven days are removed automatically when that module is next compiled.

Use `--help` for the complete option reference.
