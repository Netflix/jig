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

import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactDescriptorException;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactResolutionException;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactResult;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.DependencyResolutionException;

public record ModuleResolutionFailure(String message, Throwable cause) {
    public static ModuleResolutionFailure from(DependencyResolutionException failure) {
        var causesByMessage = new LinkedHashMap<String, Throwable>();
        if (failure.getResult() != null) {
            for (Exception exception : failure.getResult().getCollectExceptions()) {
                collect(exception, causesByMessage);
            }
            for (ArtifactResult result : failure.getResult().getArtifactResults()) {
                if (!result.isResolved()) {
                    collect(result, causesByMessage);
                }
            }
        }
        if (causesByMessage.isEmpty()) {
            Throwable cause = rootCause(failure);
            return new ModuleResolutionFailure("Failed to resolve module dependencies: " + message(cause), cause);
        }

        StringBuilder message = new StringBuilder("Failed to resolve module dependencies:");
        causesByMessage.keySet().forEach(detail -> message.append("\n  ").append(detail));
        return new ModuleResolutionFailure(message.toString(),
                causesByMessage.values()
                               .iterator()
                               .next());
    }

    private static void collect(Exception exception, Map<String, Throwable> causes) {
        if (exception instanceof ArtifactDescriptorException descriptor && descriptor.getResult() != null) {
            for (Exception nested : descriptor.getResult().getExceptions()) {
                collect(nested, causes);
            }
        } else if (exception instanceof ArtifactResolutionException resolution) {
            for (ArtifactResult result : resolution.getResults()) {
                if (!result.isResolved()) {
                    collect(result, causes);
                }
            }
        } else {
            add(rootCause(exception), causes);
        }
    }

    private static void collect(ArtifactResult result, Map<String, Throwable> causes) {
        result.getMappedExceptions().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().getId()))
                .map(Entry::getValue)
                .flatMap(List::stream)
                .map(ModuleResolutionFailure::rootCause)
                .forEach(cause -> add(cause, causes));
    }

    private static void add(Throwable cause, Map<String, Throwable> causes) {
        causes.putIfAbsent(message(cause), cause);
    }

    private static Throwable rootCause(Throwable failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable current = failure;
        while (current.getCause() != null && visited.add(current.getCause())) {
            current = current.getCause();
        }
        return current;
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.toString()
                : message;
    }
}
