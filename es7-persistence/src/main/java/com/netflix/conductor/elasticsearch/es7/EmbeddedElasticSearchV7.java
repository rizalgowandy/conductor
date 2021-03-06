/*
 * Copyright 2020 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.elasticsearch.es7;

import static java.util.Collections.EMPTY_MAP;
import static java.util.Collections.singletonList;

import com.netflix.conductor.elasticsearch.ElasticSearchConfiguration;
import com.netflix.conductor.elasticsearch.EmbeddedElasticSearch;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Map;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.node.InternalSettingsPreparer;
import org.elasticsearch.node.Node;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.transport.Netty4Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmbeddedElasticSearchV7 implements EmbeddedElasticSearch {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedElasticSearchV7.class);

    private final String clusterName;
    private final String host;
    private final int port;

    private Node instance;
    private File dataDir;

    public EmbeddedElasticSearchV7(String clusterName, String host, int port) {
        this.clusterName = clusterName;
        this.host = host;
        this.port = port;
    }

    private class PluginConfigurableNode extends Node {
        public PluginConfigurableNode(Settings preparedSettings, Collection<Class<? extends Plugin>> classpathPlugins) {
            super(InternalSettingsPreparer.prepareEnvironment(preparedSettings, EMPTY_MAP,null,() -> "default_node_name"), classpathPlugins, false);
        }
    }

    @Override
    public void start() throws Exception {
        start(clusterName, host, port);
    }

    public synchronized void start(String clusterName, String host, int port) throws Exception {

        if (instance != null) {
            String msg = String.format(
                    "An instance of this Embedded Elastic Search server is already running on port: %s.  " +
                            "It must be stopped before you can call start again.",
                    getPort()
            );
            logger.error(msg);
            throw new IllegalStateException(msg);
        }

        final Settings settings = getSettings(clusterName, host, port);
        dataDir = setupDataDir(settings.get(ElasticSearchConfiguration.EMBEDDED_DATA_PATH_DEFAULT_VALUE));

        logger.info("Starting ElasticSearch for cluster {} ", settings.get("cluster.name"));
        instance = new PluginConfigurableNode(settings, singletonList(Netty4Plugin.class));
        instance.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (instance != null) {
                    instance.close();
                }
            } catch (IOException e) {
                logger.error("Error closing ElasticSearch");
            }
        }));
        logger.info("ElasticSearch cluster {} started in local mode on port {}", instance.settings().get("cluster.name"), getPort());
    }

    private Settings getSettings(String clusterName, String host, int port) throws IOException {
        dataDir = Files.createTempDirectory(clusterName + "_" + System.currentTimeMillis() + "data").toFile();
        File homeDir = Files.createTempDirectory(clusterName + "_" + System.currentTimeMillis() + "-home").toFile();
        Settings.Builder settingsBuilder = Settings.builder()
                .put("cluster.name", clusterName)
                .put("http.host", host)
                .put("http.port", port)
                .put("transport.tcp.port", port + 100)
                .put(ElasticSearchConfiguration.EMBEDDED_DATA_PATH_DEFAULT_VALUE, dataDir.getAbsolutePath())
                .put(ElasticSearchConfiguration.EMBEDDED_HOME_PATH_DEFAULT_VALUE, homeDir.getAbsolutePath())
                //.put("http.enabled", true) //setting remove in ES7
                //.put("node.data", true)
                .put("http.type", "netty4")
                .put("transport.type", "netty4");

        return settingsBuilder.build();
    }

    private String getPort() {
        return instance.settings().get("http.port");
    }

    @Override
    public synchronized void stop() throws Exception {
        if (instance != null && !instance.isClosed()) {
            String port = getPort();
            logger.info("Stopping Elastic Search");
            instance.close();
            instance = null;
            logger.info("Elastic Search on port {} stopped", port);
        }
    }

    @Override
    public void waitForGreenCluster() {
        long startTime = System.currentTimeMillis();
        ClusterHealthRequest healthRequest = new ClusterHealthRequest();
        healthRequest.waitForGreenStatus().timeout("30s");
        instance.client().admin().cluster().health(healthRequest);
        logger.info("Elasticsearch Cluster ready in {} ms", System.currentTimeMillis() - startTime);
    }
}
