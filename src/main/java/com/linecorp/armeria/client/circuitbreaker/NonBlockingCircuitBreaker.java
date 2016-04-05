/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;

/**
 * A non-blocking implementation of circuit breaker pattern.
 */
final class NonBlockingCircuitBreaker implements CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(NonBlockingCircuitBreaker.class);

    private static final AtomicLong seqNo = new AtomicLong(0);

    private enum CircuitState {
        /**
         * Initial state. All requests are sent to the remote service.
         */
        CLOSED,
        /**
         * The circuit is tripped. All requests fail immediately without calling the remote service.
         */
        OPEN,
        /**
         * Only one trial request is sent at a time until at least one request succeeds or fails.
         * If it doesn't complete within a certain time, another trial request will be sent again.
         * All other requests fails immediately same as OPEN.
         */
        HALF_OPEN
    }

    private final String name;

    private final CircuitBreakerConfig config;

    private final AtomicReference<State> state;

    private final Ticker ticker;

    /**
     * Creates a new {@link NonBlockingCircuitBreaker} with the specified {@link Ticker} and
     * {@link CircuitBreakerConfig}.
     */
    NonBlockingCircuitBreaker(Ticker ticker, CircuitBreakerConfig config) {
        this.ticker = requireNonNull(ticker, "ticker");
        this.config = requireNonNull(config, "config");
        name = config.name().orElseGet(() -> "circuit-breaker-" + seqNo.getAndIncrement());
        state = new AtomicReference<>(newClosedState());
        logStateTransition(CircuitState.CLOSED, null);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onSuccess() {
        final State currentState = state.get();
        if (currentState.isClosed()) {
            // fires success event
            currentState.counter().onSuccess();
        } else if (currentState.isHalfOpen()) {
            // changes to CLOSED if at least one request succeeds during HALF_OPEN
            if (state.compareAndSet(currentState, newClosedState())) {
                logStateTransition(CircuitState.CLOSED, null);
            }
        }
    }

    @Override
    public void onFailure(Throwable cause) {
        try {
            if (cause != null && !config.exceptionFilter().shouldDealWith(cause)) {
                return;
            }
        } catch (Exception e) {
            logger.error("an exception has occured when calling an ExceptionFilter", e);
        }
        onFailure();
    }

    @Override
    public void onFailure() {
        final State currentState = state.get();
        if (currentState.isClosed()) {
            // fires failure event
            currentState.counter().onFailure();
            final EventCount count = currentState.counter().count();
            if (checkIfExceedingFailureThreshold(count)) {
                // changes to OPEN if failure rate exceeds the threshold
                if (state.compareAndSet(currentState, newOpenState())) {
                    logStateTransition(CircuitState.OPEN, count);
                }
            }
        } else if (currentState.isHalfOpen()) {
            // returns to OPEN if a request fails during HALF_OPEN
            if (state.compareAndSet(currentState, newOpenState())) {
                logStateTransition(CircuitState.OPEN, null);
            }
        }
    }

    private boolean checkIfExceedingFailureThreshold(EventCount count) {
        return 0 < count.total() &&
               config.minimumRequestThreshold() <= count.total() &&
               config.failureRateThreshold() < count.failureRate();
    }

    @Override
    public boolean canRequest() {
        final State currentState = state.get();
        if (currentState.isClosed()) {
            // all requests are allowed during CLOSED
            return true;
        } else if (currentState.isHalfOpen() || currentState.isOpen()) {
            if (currentState.checkTimeout() && state.compareAndSet(currentState, newHalfOpenState())) {
                // changes to HALF_OPEN if OPEN state has timed out
                logStateTransition(CircuitState.HALF_OPEN, null);
                return true;
            }
            // all other requests are refused
            return false;
        }
        return true;
    }

    private State newOpenState() {
        return new State(CircuitState.OPEN, config.circuitOpenWindow(), NoOpCounter.INSTANCE);
    }

    private State newHalfOpenState() {
        return new State(CircuitState.HALF_OPEN, config.trialRequestInterval(), NoOpCounter.INSTANCE);
    }

    private State newClosedState() {
        return new State(
                CircuitState.CLOSED,
                Duration.ZERO,
                new SlidingWindowCounter(ticker, config.counterSlidingWindow(),
                                         config.counterUpdateInterval()));
    }

    private void logStateTransition(CircuitState circuitState, @Nullable EventCount count) {
        if (logger.isInfoEnabled()) {
            final int capacity = name.length() + circuitState.name().length() + 32;
            final StringBuilder builder = new StringBuilder(capacity);
            builder.append("name:");
            builder.append(name);
            builder.append(" state:");
            builder.append(circuitState.name());
            if (count != null) {
                builder.append(" fail:");
                builder.append(count.failure());
                builder.append(" total:");
                builder.append(count.total());
            }
            logger.info(builder.toString());
        }
    }

    @VisibleForTesting
    State state() {
        return state.get();
    }

    @VisibleForTesting
    CircuitBreakerConfig config() {
        return config;
    }

    /**
     * The internal state of the circuit breaker.
     */
    final class State {
        private final CircuitState circuitState;
        private final EventCounter counter;
        private final long timedOutTimeNanos;

        /**
         * Creates a new instance.
         *
         * @param circuitState The circuit state
         * @param timeoutDuration The max duration of the state
         * @param counter The event counter to use during the state
         */
        private State(CircuitState circuitState, Duration timeoutDuration, EventCounter counter) {
            this.circuitState = circuitState;
            this.counter = counter;

            if (timeoutDuration.isZero() || timeoutDuration.isNegative()) {
                timedOutTimeNanos = 0L;
            } else {
                timedOutTimeNanos = ticker.read() + timeoutDuration.toNanos();
            }
        }

        private EventCounter counter() {
            return counter;
        }

        /**
         * Returns {@code true} if this state has timed out.
         */
        private boolean checkTimeout() {
            return 0 < timedOutTimeNanos && timedOutTimeNanos <= ticker.read();
        }

        boolean isOpen() {
            return circuitState == CircuitState.OPEN;
        }

        boolean isHalfOpen() {
            return circuitState == CircuitState.HALF_OPEN;
        }

        boolean isClosed() {
            return circuitState == CircuitState.CLOSED;
        }
    }

    private static class NoOpCounter implements EventCounter {

        private static final NoOpCounter INSTANCE = new NoOpCounter();

        @Override
        public EventCount count() {
            return EventCount.ZERO;
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onFailure() {
        }
    }
}