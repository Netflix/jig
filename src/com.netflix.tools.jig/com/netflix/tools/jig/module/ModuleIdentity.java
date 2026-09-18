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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleFinder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.jar.Attributes.Name;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.lang.model.SourceVersion;

/** Module identity and descriptor metadata observed in a module artifact. */
public record ModuleIdentity(String moduleName, String osgiModuleName, ModuleDescriptor descriptor,
        Set<String> packages, String mainClass, Map<String, List<String>> provides) {

    private static final Pattern VERSIONED_MODULE_INFO = Pattern.compile("META-INF/versions/(\\d+)/module-info\\.class");
    private static final Pattern VERSIONED_ENTRY = Pattern.compile("META-INF/versions/(\\d+)/(.+)");

    public ModuleIdentity {
        if (descriptor != null) {
            moduleName = descriptor.name();
            packages = descriptor.packages();
            mainClass = descriptor.mainClass().orElse(null);
            var descriptorProvides = new LinkedHashMap<String, List<String>>();
            descriptor.provides().forEach(provide -> descriptorProvides.put(provide.service(), provide.providers()));
            provides = descriptorProvides;
        }
        packages = Set.copyOf(packages);
        var copiedProvides = new LinkedHashMap<String, List<String>>();
        provides.forEach((service, providers) -> copiedProvides.put(service, List.copyOf(providers)));
        provides = Map.copyOf(copiedProvides);
    }

    public static ModuleIdentity parseJarEntries(Set<String> entryNames, Function<String, byte[]> reader) {
        Manifest manifest = null;
        if (entryNames.contains(JarFile.MANIFEST_NAME)) {
            var bytes = reader.apply(JarFile.MANIFEST_NAME);
            try {
                manifest = new Manifest(new ByteArrayInputStream(bytes));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        var manifestIdentity = manifestIdentity(manifest);
        var multiRelease = manifest != null && "true".equalsIgnoreCase(manifest.getMainAttributes()
                .getValue(Name.MULTI_RELEASE));
        var moduleInfoEntry = findModuleInfo(entryNames, multiRelease);
        if (moduleInfoEntry != null) {
            var bytes = reader.apply(moduleInfoEntry);
            var descriptor = ModuleDescriptor.read(ByteBuffer.wrap(bytes));
            return new ModuleIdentity(null, null, descriptor, Set.of(), null,
                    Map.of());
        }

        return new ModuleIdentity(manifestIdentity.moduleName(), manifestIdentity.osgiModuleName(), null,
                packages(entryNames, multiRelease), manifestIdentity.mainClass(), serviceProviders(entryNames, reader));
    }

    private static ModuleIdentity manifestIdentity(Manifest manifest) {
        if (manifest == null) {
            return new ModuleIdentity(null, null, null, Set.of(), null,
                    Map.of());
        }
        var attributes = manifest.getMainAttributes();
        return new ModuleIdentity(attributes.getValue("Automatic-Module-Name"), osgiModuleName(attributes.getValue("Bundle-SymbolicName")), null,
                Set.of(), attributes.getValue(Name.MAIN_CLASS), Map.of());
    }

    private static String osgiModuleName(String header) {
        if (header == null) {
            return null;
        }
        var parameters = header.indexOf(';');
        var name = (parameters < 0 ? header : header.substring(0, parameters)).strip();
        return SourceVersion.isName(name) ? name : null;
    }

    private static Map<String, List<String>> serviceProviders(Set<String> entryNames, Function<String, byte[]> reader) {
        var prefix = "META-INF/services/";
        var provides = new TreeMap<String, List<String>>();
        entryNames.stream()
                .filter(name -> name.startsWith(prefix) && name.length() > prefix.length())
                .sorted()
                .forEach(name -> {
                    var providers = Arrays.stream(new String(reader.apply(name), StandardCharsets.UTF_8).split("\\R"))
                            .map(line -> line.replaceFirst("#.*", "").strip())
                            .filter(line -> !line.isEmpty())
                            .distinct()
                            .toList();
                    if (!providers.isEmpty()) {
                        provides.put(name.substring(prefix.length()), providers);
                    }
                });
        return Map.copyOf(provides);
    }

    private static Set<String> packages(Set<String> entryNames, boolean multiRelease) {
        var packages = new TreeSet<String>();
        var runtimeVersion = JarFile.runtimeVersion().feature();
        for (var original : entryNames) {
            var entry = original;
            var versioned = VERSIONED_ENTRY.matcher(entry);
            if (versioned.matches()) {
                var version = Integer.parseInt(versioned.group(1));
                if (!multiRelease || version < JarFile.baseVersion().feature() || version > runtimeVersion) {
                    continue;
                }
                entry = versioned.group(2);
            }
            if (!entry.endsWith(".class") || entry.equals("module-info.class")) {
                continue;
            }
            var separator = entry.lastIndexOf('/');
            if (separator <= 0 || entry.startsWith("META-INF/")) {
                continue;
            }
            packages.add(entry.substring(0, separator)
                              .replace('/', '.'));
        }
        return Set.copyOf(packages);
    }

    public static ModuleIdentity parseJar(Path jarPath) {
        var reference = ModuleFinder.of(jarPath).findAll().stream()
                .findFirst()
                .orElseThrow();
        var descriptor = reference.descriptor();
        if (!descriptor.isAutomatic()) {
            return new ModuleIdentity(null, null, descriptor, Set.of(), null,
                    Map.of());
        }

        var manifestIdentity = manifestIdentity(jarPath);
        var provides = descriptor.provides().stream()
                .collect(Collectors.toMap(Provides::service, Provides::providers));
        return new ModuleIdentity(
                manifestIdentity.moduleName(),
                manifestIdentity.osgiModuleName(),
                null,
                descriptor.packages(),
                descriptor.mainClass().orElse(null),
                provides);
    }

    private static ModuleIdentity manifestIdentity(Path jarPath) {
        try (var jar = new JarFile(jarPath.toFile())) {
            return manifestIdentity(jar.getManifest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ModuleIdentity parseJmod(Path jmodPath) {
        try (var zf = new ZipFile(jmodPath.toFile())) {
            var entryNames = zf.stream()
                    .map(ZipEntry::getName)
                    .collect(Collectors.toSet());
            return parseJmodEntries(entryNames, name -> {
                try (var in = zf.getInputStream(zf.getEntry(name))) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ModuleIdentity parseJmodEntries(Set<String> entryNames, Function<String, byte[]> reader) {
        var prefix = "classes/";
        var classes = entryNames.stream()
                .filter(name -> name.startsWith(prefix))
                .map(name -> name.substring(prefix.length()))
                .collect(Collectors.toSet());
        return parseJarEntries(classes, name -> reader.apply(prefix + name));
    }

    private static String findModuleInfo(Set<String> entryNames, boolean multiRelease) {
        var best = entryNames.contains("module-info.class") ? "module-info.class" : null;
        if (!multiRelease) {
            return best;
        }
        var highestVersion = -1;
        var runtimeVersion = JarFile.runtimeVersion().feature();
        for (var name : entryNames) {
            var matcher = VERSIONED_MODULE_INFO.matcher(name);
            if (!matcher.matches()) {
                continue;
            }
            var version = Integer.parseInt(matcher.group(1));
            if (version >= JarFile.baseVersion().feature() && version <= runtimeVersion && version > highestVersion) {
                highestVersion = version;
                best = name;
            }
        }
        return best;
    }
}
