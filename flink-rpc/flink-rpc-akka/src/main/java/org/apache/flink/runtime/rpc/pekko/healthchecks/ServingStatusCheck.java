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

import org.apache.pekko.actor.AbstractActor;
import org.apache.pekko.actor.AbstractActor.Receive;
import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.Props;
import org.apache.pekko.japi.pf.ReceiveBuilder;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static org.apache.pekko.pattern.Patterns.ask;

/**
 * The class used to check the serving status of the RPC service, Initialized internally by the
 * management system (see config at {@link PekkoUtils#addHealthConfig}) The serving status is
 * retrieved from the {@link ServingStatusSubscriber} actor.
 */
public class ServingStatusCheck implements Supplier<CompletionStage<Boolean>> {

    // The http response should be returned within external liveness probes timeout,
    // and they would usually not wait longer:
    private static final Duration ASK_IS_SERVING_TIMEOUT = Duration.ofSeconds(30);
    private final ActorRef servingStatusSubscriber;

    public ServingStatusCheck(ActorSystem system) {
        this.servingStatusSubscriber =
                system.actorOf(ServingStatusSubscriber.getProps(), "servingStatusSubscriber");
        system.eventStream().subscribe(servingStatusSubscriber, ServingStatus.class);
    }

    @Override
    public CompletionStage<Boolean> get() {
        return ask(servingStatusSubscriber, new IsServing(), ASK_IS_SERVING_TIMEOUT)
                .toCompletableFuture()
                .thenApply(Boolean.class::cast);
    }

    /** Used to ask the {@link ServingStatusSubscriber} whether the service is serving or not. */
    private class IsServing {}

    /**
     * The actor subscribed to {@link ServingStatus} events of ActorSystem event stream. It
     * maintains the current serving of the rpcService, and responds to {@link IsServing} messages
     * with a booleanic reply.
     */
    private static class ServingStatusSubscriber extends AbstractActor {

        // Status is UNKNOWN by default (interpreted as not serving)
        private volatile ServingStatus currentStatus = ServingStatus.UNKNOWN;

        private ServingStatusSubscriber() {}

        public static Props getProps() {
            return Props.create(ServingStatusSubscriber.class);
        }

        private void setCurrentStatus(ServingStatus newStatus) {
            this.currentStatus = newStatus;
        }

        public ServingStatus getCurrentStatus() {
            return currentStatus;
        }

        @Override
        public Receive createReceive() {
            return new ReceiveBuilder()
                    // Listen on serving status changes and store the current value
                    .match(ServingStatus.class, this::setCurrentStatus)
                    // Reply with the current status when asked if serving
                    .match(
                            IsServing.class,
                            msg -> getSender().tell(this.getCurrentStatus().isServing(), getSelf()))
                    .build();
        }
    }
}
