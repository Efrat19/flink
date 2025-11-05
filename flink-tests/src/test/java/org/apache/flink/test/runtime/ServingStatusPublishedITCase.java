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

package org.apache.flink.test.runtime;

import static org.apache.flink.runtime.testutils.CommonTestUtils.waitUntilCondition;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.BindException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.apache.flink.api.common.time.Deadline;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.configuration.NettyShuffleEnvironmentOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.core.fs.AutoCloseableRegistry;
import org.apache.flink.core.plugin.PluginUtils;
import org.apache.flink.runtime.taskexecutor.TaskExecutorResourceUtils;
import org.apache.flink.runtime.taskexecutor.TaskManagerRunner;
import org.apache.flink.shaded.netty4.io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.flink.test.runtime.utils.HttpTestClient;
import org.apache.flink.testutils.junit.RetryOnException;
import org.apache.flink.testutils.junit.extensions.retry.RetryExtension;
import org.apache.flink.util.NetUtils;
import org.apache.flink.util.TestLoggerExtension;
import org.apache.flink.util.function.ThrowingRunnable;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test verifying that the TaskManager publishes the correct serving status via its
 * health endpoint.
 */
@
@ExtendWith({TestLoggerExtension.class, RetryExtension.class})
public class ServingStatusPublishedITCase {

    private static final String LOCALHOST = "localhost";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);

    @TestTemplate
    @RetryOnException(times = 3, exception = RetryableException.class)
    public void testPublishedByTaskManager() throws Exception {

        try (AutoCloseableRegistry autoCloseableRegistry = new AutoCloseableRegistry()) {

            final NetUtils.Port rpcPort = NetUtils.getAvailablePort();
            autoCloseableRegistry.registerCloseable(rpcPort);
            final NetUtils.Port healthPort = NetUtils.getAvailablePort();
            autoCloseableRegistry.registerCloseable(healthPort);
            Configuration taskManagerConfig =
                    createTaskManagerConfig()
                            .set(TaskManagerOptions.RPC_PORT, 0)
                            .set(TaskManagerOptions.RPC_PORT, String.valueOf(rpcPort.getPort()))
                            .set(TaskManagerOptions.HEALTH_ENABLED, true)
                            .set(
                                    TaskManagerOptions.HEALTH_PORT,
                                    healthPort.getPort());
            try {
                TaskManagerRunner taskManagerRunner =
                        new TaskManagerRunner(
                                taskManagerConfig,
                                PluginUtils.createPluginManagerFromRootFolder(taskManagerConfig),
                                TaskManagerRunner::createTaskExecutorService);
                autoCloseableRegistry.registerCloseable(taskManagerRunner::close);
                new Thread(ThrowingRunnable.unchecked(taskManagerRunner::start)).start();
                waitUntilCondition(() -> healthEndpointIsUp(LOCALHOST, healthPort.getPort()));

                // Should report a serving status
                assertThat(livenessCheck(LOCALHOST, healthPort.getPort()))
                        .isEqualTo(HttpResponseStatus.OK);

                // Should report a not_serving status
                taskManagerRunner.onFatalError(new RuntimeException("Simulated fatal error"));
                try {
                    waitUntilCondition(
                            () ->
                                    livenessCheck(LOCALHOST, healthPort.getPort())
                                            .equals(HttpResponseStatus.INTERNAL_SERVER_ERROR));
                } catch (TimeoutException e) {
                    // Endpoint might close before the check,
                    // which is ok for real world, but here
                    // we want to make sure the NOT_SERVING code was published
                    throw new RetryableException(e.getMessage());
                }
            } catch (BindException e) {
                throw new RetryableException(e.getMessage());
            }
        }
    }

    private static boolean healthEndpointIsUp(String healthHost, int healthPort) {
        try {
            livenessCheck(healthHost, healthPort);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static HttpResponseStatus livenessCheck(String healthHost, int healthPort)
            throws Exception {
        final Deadline deadline = Deadline.fromNow(HTTP_TIMEOUT);
        try (HttpTestClient client = new HttpTestClient(healthHost, healthPort)) {
            client.sendGetRequest("/alive", deadline.timeLeft());
            HttpTestClient.SimpleHttpResponse response =
                    client.getNextResponse(deadline.timeLeft());
            return response.getStatus();
        }
    }

    private static Configuration createTaskManagerConfig() {
        Configuration config = new Configuration();

        // Task manager configuration
        config.set(TaskManagerOptions.MANAGED_MEMORY_SIZE, MemorySize.parse("4m"));
        config.set(TaskManagerOptions.NUM_TASK_SLOTS, 1);
        config.set(TaskManagerOptions.NETWORK_MEMORY_MIN, MemorySize.parse("3200k"));
        config.set(TaskManagerOptions.NETWORK_MEMORY_MAX, MemorySize.parse("3200k"));
        config.set(NettyShuffleEnvironmentOptions.NETWORK_SORT_SHUFFLE_MIN_BUFFERS, 16);
        config.set(TaskManagerOptions.TASK_HEAP_MEMORY, MemorySize.parse("128m"));
        config.set(TaskManagerOptions.CPU_CORES, 1.0);
        TaskExecutorResourceUtils.adjustForLocalExecution(config);
        return config;
    }

    private static class RetryableException extends Exception {
        public RetryableException(String msg) {
            super(msg);
        }
    }
}
