/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.netflix.tools.jig.module;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import javax.lang.model.element.ModuleElement;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.version.GenericVersionScheme;
import com.netflix.tools.jig.internal.org.eclipse.aether.version.InvalidVersionSpecificationException;
import com.netflix.tools.jig.internal.org.eclipse.aether.version.VersionScheme;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.TextTree;
import com.sun.source.doctree.UnknownBlockTagTree;
import com.sun.source.tree.*;
import com.sun.source.util.DocTreeScanner;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;

/**
 * A {@link ModuleFinder} that scans a source module path for directories
 * containing {@code module-info.java}, parses them using the Java compiler API,
 * and returns {@link SourceModuleReference}s.
 * <p>
 * Source modules are fixed points. Their versioned requirements become root
 * dependencies for Aether resolution.
 * <p>
 * The {@link ModuleDescriptor} is built from the parsed AST. Compilation
 * settings and runtime access options are extracted from Javadoc tags:
 *
 * <ul>
 *   <li>{@code @release <n>} — JDK release target for compilation</li>
 *   <li>{@code @mainClass <class>} — application entry point</li>
 *   <li>{@code @processWith <module>} — annotation processor module</li>
 *   <li>{@code @enableNativeAccess <module>} — enable native access for a
 *       module</li>
 *   <li>{@code @enableFinalFieldMutation <module>} — enable final-field
 *       mutation for a module</li>
 *   <li>{@code @addOpens <module>/<package>=<target>} — open a package to a
 *       module</li>
 *   <li>{@code @addExports <module>/<package>=<target>} — export a package to a
 *       module</li>
 * </ul>
 *
 * <p>
 * The returned references describe source modules. Jig binds a compiler after
 * resolving their dependency graph; binary consumers can then open the cached
 * compiled module through the same reference.
 */
public final class SourceModuleFinder implements ModuleFinder {

    private final Map<String, SourceModuleReference> modules;

    private SourceModuleFinder(Map<String, SourceModuleReference> modules) {
        this.modules = modules;
    }

    /**
     * Scans the given source module path for modules containing
     * {@code module-info.java}, either at the root or in a multi-release
     * version section. If a descriptor is found in the given path, it is
     * treated as a single-module source root. Otherwise, its module
     * subdirectories are scanned (the convention layout).
     *
     * @param sourceModulePath root directory — either a module itself or a
     *                         parent of module directories
     */
    public static SourceModuleFinder of(Path sourceModulePath) {
        Objects.requireNonNull(sourceModulePath, "sourceModulePath");
        try {
            if (moduleInfo(sourceModulePath) != null) {
                return ofSourceDirectory(sourceModulePath);
            }
            return ofModuleSourcePath(sourceModulePath.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SourceModuleFinder ofSourceDirectory(Path sourceDirectory) {
        var finder = new SourceModuleFinder(new LinkedHashMap<>());
        try (var fileManager = COMPILER.getStandardFileManager(null, null, null)) {
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of(sourceDirectory));
            var moduleInfo = moduleInfo(sourceDirectory);
            var reference = parseModuleInfo(sourceDirectory, moduleInfo, sourcePackages(fileManager, StandardLocation.SOURCE_PATH));
            finder.modules.put(reference.descriptor().name(), reference);
            return finder;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Creates a finder using javac's {@code --module-source-path} syntax. */
    public static SourceModuleFinder ofModuleSourcePath(String moduleSourcePath) {
        Objects.requireNonNull(moduleSourcePath, "moduleSourcePath");
        var finder = new SourceModuleFinder(new LinkedHashMap<>());
        try (var fileManager = COMPILER.getStandardFileManager(null, null, null)) {
            if (!fileManager.handleOption("--module-source-path", List.of(moduleSourcePath).iterator())) {
                throw new IllegalArgumentException("Invalid module source path: " + moduleSourcePath);
            }
            if (!moduleSourcePath.contains("=")) {
                // javac only discovers conventional modules with a root descriptor.
                // Register explicit MR roots before asking it for module locations.
                for (var element : moduleSourcePath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                    int wildcard = element.indexOf('*');
                    var parent = Path.of(wildcard < 0 ? element : element.substring(0, wildcard));
                    if (!Files.isDirectory(parent)) {
                        continue;
                    }
                    try (var children = Files.list(parent)) {
                        for (var child : children.filter(Files::isDirectory).toList()) {
                            var name = child.getFileName().toString();
                            var root = wildcard < 0 ? child : Path.of(element.replace("*", name));
                            if (moduleInfo(root) != null && !Files.isRegularFile(root.resolve("module-info.java"))) {
                                fileManager.setLocationForModule(StandardLocation.MODULE_SOURCE_PATH, name, List.of(root));
                            }
                        }
                    }
                }
            }
            for (var locations : fileManager.listLocationsForModules(StandardLocation.MODULE_SOURCE_PATH)) {
                for (var location : locations) {
                    var moduleInfo = fileManager.getJavaFileForInput(location, "module-info", JavaFileObject.Kind.SOURCE);
                    Path moduleInfoPath = moduleInfo == null ? null : fileManager.asPath(moduleInfo);
                    Path sourceDirectory = null;
                    for (Path path : fileManager.getLocationAsPaths(location)) {
                        var candidate = moduleInfo(path);
                        if (candidate != null) {
                            sourceDirectory = path;
                            moduleInfoPath = candidate;
                            break;
                        }
                    }
                    if (moduleInfoPath == null) {
                        continue;
                    }
                    if (sourceDirectory == null) {
                        sourceDirectory = moduleInfoPath.getParent();
                    }
                    var reference = parseModuleInfo(sourceDirectory, moduleInfoPath, sourcePackages(fileManager, location));
                    var expectedName = fileManager.inferModuleName(location);
                    var actualName = reference.descriptor().name();
                    if (expectedName != null && !expectedName.equals(actualName)) {
                        throw new IllegalArgumentException("Expected module " + expectedName + " at " + sourceDirectory + " but module-info.java declares " + actualName);
                    }
                    finder.modules.put(actualName, reference);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return finder;
    }

    private static Path moduleInfo(Path root) throws IOException {
        var descriptor = root.resolve("module-info.java");
        if (Files.isRegularFile(descriptor)) {
            return descriptor;
        }
        var versions = root.resolve("META-INF/versions");
        if (Files.isDirectory(versions)) {
            try (var entries = Files.list(versions)) {
                var descriptors = entries.map(path -> path.resolve("module-info.java"))
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.comparingInt(path -> Integer.parseInt(path.getParent().getFileName().toString())))
                        .toList();
                if (!descriptors.isEmpty()) {
                    return descriptors.getFirst();
                }
            }
        }
        return null;
    }

    private static Set<String> sourcePackages(JavaFileManager fileManager, JavaFileManager.Location location) throws IOException {
        var packages = new LinkedHashSet<String>();
        for (var source : fileManager.list(location, "", Set.of(JavaFileObject.Kind.SOURCE), true)) {
            var binaryName = fileManager.inferBinaryName(location, source);
            if (binaryName == null || binaryName.equals("module-info"))
                continue;
            var separator = binaryName.lastIndexOf('.');
            if (separator > 0)
                packages.add(binaryName.substring(0, separator));
        }
        return Set.copyOf(packages);
    }

    @Override
    public Optional<ModuleReference> find(String name) {
        return Optional.ofNullable(modules.get(name));
    }

    @Override
    public Set<ModuleReference> findAll() {
        return Set.copyOf(modules.values());
    }

    // -- parsing --

    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

    private static SourceModuleReference parseModuleInfo(Path moduleDir, Path moduleInfoPath, Set<String> packages) {
        try (var fileManager = COMPILER.getStandardFileManager(null, null, null)) {
            var fileObjects = fileManager.getJavaFileObjects(moduleInfoPath);

            var output = new StringWriter();
            var task =
                    (JavacTask) COMPILER.getTask(output,
                                    fileManager,
                                    diagnostic -> {},
                                    List.of("-proc:only"),
                                    null,
                                    fileObjects);

            var docTrees = DocTrees.instance(task);
            var unit =
                    task.parse()
                            .iterator()
                            .next();
            var moduleTree = unit.getModule();

            var versionOpinions = extractVersionOpinions(unit, moduleTree, docTrees);
            var docComment = readDocComment(task, docTrees, moduleTree);
            var settings = buildModuleSettings(docComment);
            var mainClass = extractMainClass(docComment);
            if (Objects.equals(settings.release(), 8)) {
                if (!moduleInfoPath.equals(moduleDir.resolve("META-INF/versions/9/module-info.java"))) {
                    throw new IllegalArgumentException("@release 8 requires META-INF/versions/9/module-info.java: " + moduleInfoPath);
                }
                var manifestPath = moduleDir.resolve("META-INF/MANIFEST.MF");
                if (!Files.isRegularFile(manifestPath)) {
                    throw new IllegalArgumentException("@release 8 requires Multi-Release: true: " + manifestPath);
                }
                try (var input = Files.newInputStream(manifestPath)) {
                    if (!"true".equalsIgnoreCase(new Manifest(input).getMainAttributes().getValue(Attributes.Name.MULTI_RELEASE))) {
                        throw new IllegalArgumentException("@release 8 requires Multi-Release: true: " + manifestPath);
                    }
                }
            }

            var descriptor = buildModuleDescriptor(moduleTree, versionOpinions, mainClass, packages);
            var sourceModule =
                    new SourceModule(descriptor,
                                     settings.release(),
                                     settings.preview(),
                                     settings.processors(),
                                     settings.runtimeAccessOptions(),
                                     moduleDir);

            return SourceModuleReference.uncompiled(sourceModule);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DocCommentTree readDocComment(JavacTask task, DocTrees docTrees, ModuleTree moduleTree) {
        try {
            ModuleElement moduleElement = task.getElements().getModuleElement(moduleTree.getName().toString());
            if (moduleElement != null) {
                return docTrees.getDocCommentTree(moduleElement);
            }
        } catch (Exception ignored) {
            // doc comments are best-effort during parse-only
        }
        return null;
    }

    private static String extractMainClass(DocCommentTree docComment) {
        if (docComment == null)
            return null;
        String[] result = {null};
        docComment.accept(new DocTreeScanner<Void, Void>() {
            @Override
            public Void visitUnknownBlockTag(UnknownBlockTagTree node, Void v) {
                if ("mainClass".equals(node.getTagName())) {
                    var content =
                            node.getContent().stream()
                                    .filter(TextTree.class::isInstance)
                                    .map(t ->
                                            ((TextTree) t).getBody().trim())
                                    .collect(Collectors.joining());
                    if (!content.isEmpty())
                        result[0] = content;
                }
                return null;
            }
        },
                null);
        return result[0];
    }

    private static ModuleDescriptor buildModuleDescriptor(ModuleTree moduleTree,
            Map<String, ModuleDescriptor.Version> versionOpinions,
            String mainClass,
            Set<String> sourcePackages) {
        var moduleName = moduleTree.getName().toString();
        var builder = moduleTree.getModuleType() == ModuleTree.ModuleKind.OPEN ? ModuleDescriptor.newOpenModule(moduleName) : ModuleDescriptor.newModule(moduleName);
        var packages = new LinkedHashSet<>(sourcePackages);
        for (var directive : moduleTree.getDirectives()) {
            switch (directive) {
                case RequiresTree requires -> {
                    var name = requires.getModuleName().toString();
                    var modifiers = EnumSet.noneOf(ModuleDescriptor.Requires.Modifier.class);
                    if (requires.isStatic()) {
                        modifiers.add(ModuleDescriptor.Requires.Modifier.STATIC);
                    }
                    if (requires.isTransitive()) {
                        modifiers.add(ModuleDescriptor.Requires.Modifier.TRANSITIVE);
                    }
                    var version = versionOpinions.get(name);
                    if (version == null)
                        builder.requires(modifiers, name);
                    else
                        builder.requires(modifiers, name, version);
                }
                case ExportsTree exports -> {
                    var packageName = exports.getPackageName().toString();
                    packages.add(packageName);
                    var targets = exports.getModuleNames();
                    if (targets == null || targets.isEmpty()) {
                        builder.exports(packageName);
                    } else {
                        builder.exports(packageName,
                                        targets.stream()
                                                .map(Object::toString)
                                                .collect(Collectors.toSet()));
                    }
                }
                case OpensTree opens -> {
                    var packageName = opens.getPackageName().toString();
                    packages.add(packageName);
                    var targets = opens.getModuleNames();
                    if (targets == null || targets.isEmpty()) {
                        builder.opens(packageName);
                    } else {
                        builder.opens(packageName,
                                      targets.stream()
                                              .map(Object::toString)
                                              .collect(Collectors.toSet()));
                    }
                }
                case UsesTree uses -> builder.uses(uses.getServiceName().toString());
                case ProvidesTree provides -> {
                    var providers =
                            provides.getImplementationNames().stream()
                                    .map(Object::toString)
                                    .toList();
                    providers.stream()
                            .map(SourceModuleFinder::packageName)
                            .filter(packageName -> !packageName.isEmpty())
                            .forEach(packages::add);
                    builder.provides(provides.getServiceName().toString(), providers);
                }
                default -> {}
            }
        }
        if (mainClass != null) {
            builder.mainClass(mainClass);
            var mainPackage = packageName(mainClass);
            if (!mainPackage.isEmpty())
                packages.add(mainPackage);
        }
        builder.packages(packages);
        return builder.build();
    }

    private static String packageName(String className) {
        var separator = className.lastIndexOf('.');
        return separator > 0 ? className.substring(0, separator) : "";
    }

    private static final VersionScheme VERSION_SCHEME = new GenericVersionScheme();

    /**
     * Extracts version opinions from {@code // @version} trailing comments
     * on {@code requires} directives.
     */
    private static Map<String, ModuleDescriptor.Version> extractVersionOpinions(CompilationUnitTree unit, ModuleTree moduleTree, DocTrees docTrees) {
        CharSequence source;
        try {
            source = unit.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var positions = docTrees.getSourcePositions();
        var versions = new LinkedHashMap<String, ModuleDescriptor.Version>();
        for (DirectiveTree directive : moduleTree.getDirectives()) {
            if (!(directive instanceof RequiresTree requires))
                continue;
            long endPos = positions.getEndPosition(unit, requires);
            if (endPos < 0 || endPos >= source.length())
                continue;
            String rest = source.subSequence((int) endPos, source.length()).toString();
            int lineEnd = rest.indexOf('\n');
            String trailing = (lineEnd < 0 ? rest : rest.substring(0, lineEnd)).trim();
            if (!trailing.startsWith("//"))
                continue;
            String comment = trailing.substring(2).trim();
            if (!comment.startsWith("@"))
                continue;
            String moduleName = requires.getModuleName().toString();
            String versionString = comment.substring(1).trim();
            requireConcreteVersion(versionString, moduleName, unit.getSourceFile().getName());
            versions.put(moduleName, ModuleDescriptor.Version.parse(versionString));
        }
        return versions;
    }

    private static void requireConcreteVersion(String version, String moduleName, String source) {
        try {
            var constraint = VERSION_SCHEME.parseVersionConstraint(version);
            if (constraint.getRange() != null) {
                throw new IllegalArgumentException("Invalid version '" + version + "' for " + moduleName + " in " + source);
            }
        } catch (InvalidVersionSpecificationException e) {
            throw new IllegalArgumentException("Invalid version '" + version + "' for " + moduleName + " in " + source,
                    e);
        }
    }

    private record ModuleSettings(Integer release,
                                  boolean preview,
                                  List<String> processors,
                                  ModuleRuntimeAccessOptions runtimeAccessOptions) {}

    private static ModuleSettings buildModuleSettings(DocCommentTree docComment) {
        if (docComment == null) {
            return new ModuleSettings(null, false, List.of(), ModuleRuntimeAccessOptions.EMPTY);
        }
        Integer[] release = {null};
        boolean[] preview = {false};
        var processors = new LinkedHashSet<String>();
        var builder = ModuleRuntimeAccessOptions.newBuilder();
        docComment.accept(new DocTreeScanner<Void, ModuleRuntimeAccessOptions.Builder>() {
            @Override
            public Void visitUnknownBlockTag(UnknownBlockTagTree node, ModuleRuntimeAccessOptions.Builder b) {
                String tag = node.getTagName();
                String content =
                        node.getContent().stream()
                                .filter(TextTree.class::isInstance)
                                .map(t ->
                                        ((TextTree) t).getBody().trim())
                                .collect(Collectors.joining());
                switch (tag) {
                    case "release" -> {
                        if (!content.isEmpty())
                            release[0] = Integer.parseInt(content);
                    }
                    case "enablePreview" -> preview[0] = true;
                    case "processWith" -> {
                        try {
                            ModuleDescriptor.newModule(content);
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException("@processWith requires a module name: " + content, e);
                        }
                        processors.add(content);
                    }
                    case "enableNativeAccess" -> b.enableNativeAccess(content);
                    case "enableFinalFieldMutation" -> b.enableFinalFieldMutation(content);
                    case "addOpens" -> {
                        var value = parsePackageAccess(tag, content);
                        b.addOpens(value.sourceModule(),
                                   value.packageName(),
                                   value.targetModule());
                    }
                    case "addExports" -> {
                        var value = parsePackageAccess(tag, content);
                        b.addExports(value.sourceModule(),
                                     value.packageName(),
                                     value.targetModule());
                    }
                    default -> {}
                }
                return null;
            }
        },
                builder);
        return new ModuleSettings(release[0], preview[0], List.copyOf(processors), builder.build());
    }

    private static ModuleRuntimeAccessOptions.PackageAccess parsePackageAccess(String tag, String value) {
        int slash = value.indexOf('/');
        int equals = value.indexOf('=', slash + 1);
        if (slash <= 0
                || equals <= slash + 1
                || equals == value.length() - 1
                || value.indexOf('=', equals + 1) >= 0) {
            throw new IllegalArgumentException("@" + tag + " requires <module>/<package>=<target-module>");
        }
        return new ModuleRuntimeAccessOptions.PackageAccess(value.substring(0, slash),
                value.substring(slash + 1, equals),
                value.substring(equals + 1));
    }
}
