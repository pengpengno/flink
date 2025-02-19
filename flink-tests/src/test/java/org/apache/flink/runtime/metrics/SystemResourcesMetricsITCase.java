/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.metrics;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MetricOptions;
import org.apache.flink.metrics.CharacterFilter;
import org.apache.flink.metrics.Metric;
import org.apache.flink.metrics.MetricConfig;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.metrics.reporter.MetricReporter;
import org.apache.flink.metrics.reporter.MetricReporterFactory;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.apache.flink.testutils.junit.extensions.ContextClassLoaderExtension;
import org.apache.flink.util.concurrent.FutureUtils;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.apache.flink.configuration.MetricOptions.REPORTERS_LIST;
import static org.apache.flink.configuration.MetricOptions.SYSTEM_RESOURCE_METRICS;
import static org.junit.Assert.assertEquals;

/** Integration tests for proper initialization of the system resource metrics. */
class SystemResourcesMetricsITCase {

    @RegisterExtension
    @Order(1)
    static final ContextClassLoaderExtension CONTEXT_CLASS_LOADER_EXTENSION =
            ContextClassLoaderExtension.builder()
                    .withServiceEntry(
                            MetricReporterFactory.class,
                            SystemResourcesMetricsITCase.TestReporter.class.getName())
                    .build();

    @RegisterExtension
    @Order(2)
    static final MiniClusterExtension MINI_CLUSTER_RESOURCE =
            new MiniClusterExtension(
                    new MiniClusterResourceConfiguration.Builder()
                            .setConfiguration(getConfiguration())
                            .setNumberTaskManagers(1)
                            .setNumberSlotsPerTaskManager(1)
                            .build());

    private static Configuration getConfiguration() {
        Configuration configuration = new Configuration();
        configuration.setBoolean(SYSTEM_RESOURCE_METRICS, true);
        configuration.setString(REPORTERS_LIST, "test_reporter");
        configuration.setString(MetricOptions.SCOPE_NAMING_JM, "jobmanager");
        configuration.setString(MetricOptions.SCOPE_NAMING_TM, "taskmanager");
        configuration.setString(
                "metrics.reporter.test_reporter.factory.class", TestReporter.class.getName());
        return configuration;
    }

    @Test
    void startTaskManagerAndCheckForRegisteredSystemMetrics() throws Exception {
        assertEquals(1, TestReporter.OPENED_REPORTERS.size());
        TestReporter reporter = TestReporter.OPENED_REPORTERS.iterator().next();

        reporter.patternsExhaustedFuture.get(10, TimeUnit.SECONDS);
    }

    private static List<String> getExpectedPatterns() {
        String[] expectedGauges = {
            "System.CPU.Idle",
            "System.CPU.Sys",
            "System.CPU.User",
            "System.CPU.IOWait",
            "System.CPU.Irq",
            "System.CPU.SoftIrq",
            "System.CPU.Steal",
            "System.CPU.Nice",
            "System.Memory.Available",
            "System.Memory.Total",
            "System.Swap.Used",
            "System.Swap.Total",
            "System.Network.*ReceiveRate",
            "System.Network.*SendRate"
        };

        String[] expectedHosts = {"taskmanager.", "jobmanager."};

        List<String> patterns = new ArrayList<>();
        for (String expectedHost : expectedHosts) {
            for (String expectedGauge : expectedGauges) {
                patterns.add(expectedHost + expectedGauge);
            }
        }
        return patterns;
    }

    /** Test metric reporter that exposes registered metrics. */
    public static final class TestReporter implements MetricReporter, MetricReporterFactory {
        public static final Set<TestReporter> OPENED_REPORTERS = ConcurrentHashMap.newKeySet();
        private final Map<String, CompletableFuture<Void>> patternFutures =
                getExpectedPatterns().stream()
                        .collect(
                                Collectors.toMap(
                                        pattern -> pattern, pattern -> new CompletableFuture<>()));
        private final CompletableFuture<Void> patternsExhaustedFuture =
                FutureUtils.waitForAll(patternFutures.values());

        @Override
        public void open(MetricConfig config) {
            OPENED_REPORTERS.add(this);
        }

        @Override
        public void close() {
            OPENED_REPORTERS.remove(this);
        }

        @Override
        public void notifyOfAddedMetric(Metric metric, String metricName, MetricGroup group) {
            final String metricIdentifier =
                    group.getMetricIdentifier(metricName, CharacterFilter.NO_OP_FILTER);
            for (final String expectedPattern : patternFutures.keySet()) {
                if (metricIdentifier.matches(expectedPattern)) {
                    patternFutures.get(expectedPattern).complete(null);
                }
            }
        }

        @Override
        public void notifyOfRemovedMetric(Metric metric, String metricName, MetricGroup group) {}

        @Override
        public MetricReporter createMetricReporter(Properties properties) {
            return this;
        }
    }
}
