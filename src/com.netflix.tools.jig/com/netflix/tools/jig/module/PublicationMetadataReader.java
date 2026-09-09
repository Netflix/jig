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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

import com.netflix.tools.jig.internal.org.apache.maven.api.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.v4.MavenStaxReader;

/** Reads the publication metadata contributed by a module POM. */
final class PublicationMetadataReader {
    private static final String MODEL_NAMESPACE = "http://maven.apache.org/POM/4.0.0";
    private static final Set<String> ALLOWED_ELEMENTS = Set.of(
            "modelVersion",
            "name",
            "description",
            "url",
            "licenses",
            "developers",
            "scm");

    private PublicationMetadataReader() {}

    static Model read(Path pom) throws IOException {
        Path absolute = pom.toAbsolutePath().normalize();
        validateElements(absolute);
        try (var input = Files.newInputStream(absolute)) {
            Model model = new MavenStaxReader().read(input);
            if (!"4.0.0".equals(model.getModelVersion())) {
                throw new IllegalArgumentException(pom.getFileName() + " requires modelVersion 4.0.0");
            }
            return model;
        } catch (XMLStreamException e) {
            throw new IOException("Failed to read " + absolute, e);
        }
    }

    private static void validateElements(Path pom) throws IOException {
        var inputFactory = XMLInputFactory.newFactory();
        inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputFactory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (var input = Files.newInputStream(pom)) {
            var reader = inputFactory.createXMLStreamReader(input);
            int depth = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth == 1 && (!"project".equals(reader.getLocalName())
                            || !MODEL_NAMESPACE.equals(reader.getNamespaceURI()))) {
                        throw new IllegalArgumentException(pom.getFileName()
                                + " must be a Maven 4.0.0 project model");
                    }
                    if (depth == 2 && !ALLOWED_ELEMENTS.contains(reader.getLocalName())) {
                        throw new IllegalArgumentException(pom.getFileName()
                                + " contains unsupported element "
                                + reader.getLocalName());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new IOException("Failed to read " + pom, e);
        }
    }
}
