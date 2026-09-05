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
package com.netflix.tools.jig.internal.org.apache.maven.model.management;

import com.netflix.tools.jig.internal.javax.inject.Named;
import com.netflix.tools.jig.internal.javax.inject.Singleton;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.netflix.tools.jig.internal.org.apache.maven.model.Dependency;
import com.netflix.tools.jig.internal.org.apache.maven.model.DependencyManagement;
import com.netflix.tools.jig.internal.org.apache.maven.model.Exclusion;
import com.netflix.tools.jig.internal.org.apache.maven.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.building.ModelBuildingRequest;
import com.netflix.tools.jig.internal.org.apache.maven.model.building.ModelProblemCollector;
import com.netflix.tools.jig.internal.org.apache.maven.model.merge.MavenModelMerger;

/**
 * Handles injection of dependency management into the model.
 *
 * @deprecated use {@code com.netflix.tools.jig.internal.org.apache.maven.api.services.ModelBuilder} instead
 */
@SuppressWarnings({"checkstyle:methodname"})
@Named
@Singleton
@Deprecated(since = "4.0.0")
public class DefaultDependencyManagementInjector implements DependencyManagementInjector {

    private ManagementModelMerger merger = new ManagementModelMerger();

    @Override
    public void injectManagement(Model model, ModelBuildingRequest request, ModelProblemCollector problems) {
        merger.mergeManagedDependencies(model);
    }

    /**
     * ManagementModelMerger
     */
    protected static class ManagementModelMerger extends MavenModelMerger {

        public void mergeManagedDependencies(Model model) {
            DependencyManagement dependencyManagement = model.getDependencyManagement();
            if (dependencyManagement != null) {
                Map<Object, Dependency> dependencies = new HashMap<>();
                Map<Object, Object> context = Collections.emptyMap();

                for (Dependency dependency : model.getDependencies()) {
                    Object key = getDependencyKey(dependency);
                    dependencies.put(key, dependency);
                }

                for (Dependency managedDependency : dependencyManagement.getDependencies()) {
                    Object key = getDependencyKey(managedDependency);
                    Dependency dependency = dependencies.get(key);
                    if (dependency != null) {
                        mergeDependency(dependency, managedDependency, false, context);
                    }
                }
            }
        }

        @Override
        protected void mergeDependency_Optional(
                Dependency target, Dependency source, boolean sourceDominant, Map<Object, Object> context) {
            // optional flag is not managed
        }

        @Override
        protected void mergeDependency_Exclusions(
                Dependency target, Dependency source, boolean sourceDominant, Map<Object, Object> context) {
            List<Exclusion> tgt = target.getExclusions();
            if (tgt.isEmpty()) {
                List<Exclusion> src = source.getExclusions();

                for (Exclusion element : src) {
                    Exclusion clone = element.clone();
                    target.addExclusion(clone);
                }
            }
        }
    }
}
