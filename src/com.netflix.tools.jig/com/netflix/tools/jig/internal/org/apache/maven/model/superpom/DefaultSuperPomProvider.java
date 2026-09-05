/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.netflix.tools.jig.internal.org.apache.maven.model.superpom;

import com.netflix.tools.jig.internal.javax.inject.Inject;
import com.netflix.tools.jig.internal.javax.inject.Named;
import com.netflix.tools.jig.internal.javax.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import com.netflix.tools.jig.internal.org.apache.maven.model.InputSource;
import com.netflix.tools.jig.internal.org.apache.maven.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.building.ModelProcessor;

/**
 * Provides the super POM that all models implicitly inherit from.
 *
 * @deprecated use {@code com.netflix.tools.jig.internal.org.apache.maven.api.services.ModelBuilder} instead
 */
@Named
@Singleton
@Deprecated(since = "4.0.0")
public class DefaultSuperPomProvider implements SuperPomProvider {

    /**
     * The cached super POM, lazily created.
     */
    private Model superModel;

    @Inject
    private ModelProcessor modelProcessor;

    public DefaultSuperPomProvider setModelProcessor(ModelProcessor modelProcessor) {
        this.modelProcessor = modelProcessor;
        return this;
    }

    @Override
    public Model getSuperModel(String version) {
        if (superModel == null) {
            String resource = "/com/netflix/tools/jig/internal/org/apache/maven/model/pom-"
                    + version + ".xml";

            InputStream is = getClass().getResourceAsStream(resource);

            if (is == null) {
                throw new IllegalStateException("The super POM " + resource + " was not found"
                        + ", please verify the integrity of your Maven installation");
            }

            try {
                Map<String, Object> options = new HashMap<>();
                options.put("xml:4.0.0", "xml:4.0.0");

                String modelId = "com.netflix.tools.jig.internal.org.apache.maven:maven-model-builder:"
                        + this.getClass().getPackage().getImplementationVersion() + ":super-pom";
                InputSource inputSource = new InputSource();
                inputSource.setModelId(modelId);
                inputSource.setLocation(getClass().getResource(resource).toExternalForm());
                options.put(ModelProcessor.INPUT_SOURCE, inputSource);

                superModel = modelProcessor.read(is, options);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "The super POM " + resource + " is damaged"
                                + ", please verify the integrity of your Maven installation",
                        e);
            }
        }

        return superModel;
    }
}
