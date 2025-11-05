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

package org.apache.flink.runtime.rpc.pekko.healthchecks;

import org.apache.flink.runtime.rpc.health.ServingStatus;

import org.apache.pekko.actor.ActorSystem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;

import static org.apache.flink.core.testutils.FlinkAssertions.assertThatFuture;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests for the {@link ServingStatusCheck}. */
class ServingStatusCheckTest {
    private static ActorSystem system;

    @BeforeAll
    static void setup() {
        system = ActorSystem.create("test-system");
    }

    @AfterAll
    static void teardown() {
        if (system != null) {
            system.terminate();
        }
    }

    @Test
    void testGetReturnsServingStatus() throws ExecutionException, InterruptedException {
        ServingStatusCheck servingStatusCheck = new ServingStatusCheck(system);

        // Initially, should be UNKNOWN (interpreted as not serving)
        assertThat(servingStatusCheck.get().toCompletableFuture().get()).isFalse();

        // Publish SERVING status
        system.eventStream().publish(ServingStatus.SERVING);
        assertThatFuture(servingStatusCheck.get()).eventuallySucceeds().isEqualTo(true);

        // Publish NOT_SERVING status
        system.eventStream().publish(ServingStatus.NOT_SERVING);
        assertThatFuture(servingStatusCheck.get()).eventuallySucceeds().isEqualTo(false);
    }
}
