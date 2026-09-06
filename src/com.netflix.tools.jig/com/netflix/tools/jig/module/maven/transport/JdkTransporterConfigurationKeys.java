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
 *//*
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
    *//*
          * Originally from Apache Maven Resolver, maven-resolver-transport-jdk-11 2.0.8.
          * Modified: package relocated to com.netflix.tools.jig.module.maven.transport.
          */
package com.netflix.tools.jig.module.maven.transport;

import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;

/**
 * JDK Transport configuration keys.
 *
 * @since 2.0.0
 */
public final class JdkTransporterConfigurationKeys {
    private JdkTransporterConfigurationKeys() {}

    private static final String CONFIG_PROPS_PREFIX = "aether.transport." + JdkTransporterFactory.NAME + ".";

    /**
     * Use string representation of HttpClient version enum "HTTP_2" or
     * "HTTP_1_1" to set default HTTP protocol to use.
     *
     * @configurationSource
     *     {@link RepositorySystemSession#getConfigProperties()}
     * @configurationType {@link java.lang.String}
     * @configurationDefaultValue {@link #DEFAULT_HTTP_VERSION}
     * @configurationRepoIdSuffix Yes
     */
    public static final String CONFIG_PROP_HTTP_VERSION = CONFIG_PROPS_PREFIX + "httpVersion";

    public static final String DEFAULT_HTTP_VERSION = "HTTP_1_1";

    /**
     * Whether HTTP clients and their connection pools are shared by transporters
     * in one repository session.
     *
     * @configurationSource
     *     {@link RepositorySystemSession#getConfigProperties()}
     * @configurationType {@link java.lang.Boolean}
     * @configurationDefaultValue {@link #DEFAULT_CACHE_STATE}
     */
    public static final String CONFIG_PROP_CACHE_STATE = CONFIG_PROPS_PREFIX + "cacheState";

    public static final boolean DEFAULT_CACHE_STATE = true;

    /**
     * The hard limit of maximum concurrent requests JDK transport can do. This
     * is a workaround for the fact, that in HTTP/2 mode, JDK HttpClient
     * initializes this value to Integer.MAX_VALUE (!) and lowers it on first
     * response from the remote server (but it may be too late). See JDK bug <a
     * href="https://bugs.openjdk.org/browse/JDK-8225647">JDK-8225647</a> for
     * details.
     *
     * @configurationSource
     *     {@link RepositorySystemSession#getConfigProperties()}
     * @configurationType {@link java.lang.Integer}
     * @configurationDefaultValue {@link #DEFAULT_MAX_CONCURRENT_REQUESTS}
     * @configurationRepoIdSuffix Yes
     */
    public static final String CONFIG_PROP_MAX_CONCURRENT_REQUESTS = CONFIG_PROPS_PREFIX + "maxConcurrentRequests";

    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 100;
}
