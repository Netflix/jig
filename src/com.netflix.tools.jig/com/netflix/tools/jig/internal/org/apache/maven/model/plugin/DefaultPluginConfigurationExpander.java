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
package com.netflix.tools.jig.internal.org.apache.maven.model.plugin;

import com.netflix.tools.jig.internal.javax.inject.Named;
import com.netflix.tools.jig.internal.javax.inject.Singleton;

import java.util.List;

import com.netflix.tools.jig.internal.org.apache.maven.model.Build;
import com.netflix.tools.jig.internal.org.apache.maven.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.Plugin;
import com.netflix.tools.jig.internal.org.apache.maven.model.PluginExecution;
import com.netflix.tools.jig.internal.org.apache.maven.model.PluginManagement;
import com.netflix.tools.jig.internal.org.apache.maven.model.building.ModelBuildingRequest;
import com.netflix.tools.jig.internal.org.apache.maven.model.building.ModelProblemCollector;
import com.netflix.tools.jig.internal.org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Handles expansion of general build plugin configuration into individual executions.
 *
 * @deprecated use {@code com.netflix.tools.jig.internal.org.apache.maven.api.services.ModelBuilder} instead
 */
@Named
@Singleton
@Deprecated(since = "4.0.0")
public class DefaultPluginConfigurationExpander implements PluginConfigurationExpander {

    @Override
    public void expandPluginConfiguration(Model model, ModelBuildingRequest request, ModelProblemCollector problems) {
        Build build = model.getBuild();

        if (build != null) {
            expand(build.getPlugins());

            PluginManagement pluginManagement = build.getPluginManagement();

            if (pluginManagement != null) {
                expand(pluginManagement.getPlugins());
            }
        }
    }

    private void expand(List<Plugin> plugins) {
        for (Plugin plugin : plugins) {
            Xpp3Dom pluginConfiguration = (Xpp3Dom) plugin.getConfiguration();

            if (pluginConfiguration != null) {
                for (PluginExecution execution : plugin.getExecutions()) {
                    Xpp3Dom executionConfiguration = (Xpp3Dom) execution.getConfiguration();

                    executionConfiguration =
                            Xpp3Dom.mergeXpp3Dom(executionConfiguration, new Xpp3Dom(pluginConfiguration));

                    execution.setConfiguration(executionConfiguration);
                }
            }
        }
    }
}
