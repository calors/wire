/*
Copyright 2018-2020 Cedric Liegeois

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.

    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.

    * Neither the name of the copyright holder nor the names of other
      contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package io.omam.wire.device;

import static io.omam.wire.io.IoProperties.DEFAULT_RECEIVER_ID;
import static io.omam.wire.io.IoProperties.SENDER_ID;
import static io.omam.wire.io.json.Payloads.parse;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.protobuf.MessageLite;

import io.omam.wire.io.CastChannel.CastMessage;
import io.omam.wire.io.IoProperties;
import io.omam.wire.io.json.Payload;
import io.omam.wire.io.json.Payloads;

/**
 * A {@code Requestor} transmits a request to the device and awaits for a correlated response.
 * <p>
 * This class is not thread-safe.
 *
 * @param <T> the type of the payload
 */
final class Requestor<T> implements ChannelListener {

    /**
     * {@code CastMessage} builder.
     *
     * @param <T> the type of the payload
     */
    @FunctionalInterface
    private static interface CastMessageBuilder<T> {

        /**
         * Returns a new {@code CastMessage} using the given parameters.
         *
         * @param namespace the namespace of the message
         * @param destination the destination of the message
         * @param payload the payload of the message
         * @return a new {@code CastMessage}
         */
        CastMessage build(final String namespace, final String destination, final T payload);

    }

    /** logger. */
    private static final Logger LOGGER = Logger.getLogger(Requestor.class.getName());

    /** standard request/response correlation predicate. */
    private static final BiPredicate<CastMessage, CastMessage> STD_CORRELATOR = (req, resp) -> {
        try {
            final Payload pReq = parse(req);
            final Payload pResp = parse(resp);
            return pResp.requestId().isPresent() && pReq.requestId().equals(pResp.requestId());
        } catch (final IOException e) {
            LOGGER.log(Level.WARNING, "Could not parse request or response", e);
            return false;
        }
    };

    /** always true. */
    private static final BiPredicate<CastMessage, CastMessage> ALWAYS_CORRELATED = (a, b) -> true;

    /** CastMessage builder from binary payload. */
    private static final CastMessageBuilder<MessageLite> BINARY_PAYLOAD =
            (namespace, destination, payload) -> CastMessage
                .newBuilder()
                .setProtocolVersion(CastMessage.ProtocolVersion.CASTV2_1_0)
                .setSourceId(SENDER_ID)
                .setDestinationId(destination)
                .setNamespace(namespace)
                .setPayloadType(CastMessage.PayloadType.BINARY)
                .setPayloadBinary(payload.toByteString())
                .build();

    /** CastMessage builder from String payload. */
    private static final CastMessageBuilder<Payload> STRING_PAYLOAD =
            (namespace, destination, payload) -> Payloads.buildRequest(namespace, destination, payload);

    /** communication channel. */
    private final CastV2Channel channel;

    /** CastMessage builder. */
    private final CastMessageBuilder<T> builder;

    /** request/response correlator. */
    private final BiPredicate<CastMessage, CastMessage> correlator;

    /** the transmitted request. */
    private CastMessage req;

    /** lock. */
    private final Lock lock;

    /** condition signaled when a response is received. */
    private final Condition responseReceived;

    /**
     * last received response, null if no request has been transmitted, no response has been received.
     */
    private volatile CastMessage response;

    /**
     * Constructor.
     *
     * @param aChannel communication channel
     * @param aBuilder CastMessage builder
     * @param aCorrelator a {@code BiPredicate} that accepts a request and a received message and returns
     *            {@code true} if that latter is correlated with the request
     */
    private Requestor(final CastV2Channel aChannel, final CastMessageBuilder<T> aBuilder,
            final BiPredicate<CastMessage, CastMessage> aCorrelator) {
        channel = aChannel;
        builder = aBuilder;
        correlator = aCorrelator;
        req = null;
        lock = new ReentrantLock();
        responseReceived = lock.newCondition();
        response = null;
    }

    /**
     * Returns a new Requestor to send request containing binary payloads.
     *
     * @param channel communication channel
     * @return a new Requestor
     */
    static Requestor<MessageLite> binaryPayload(final CastV2Channel channel) {
        return new Requestor<>(channel, BINARY_PAYLOAD, ALWAYS_CORRELATED);
    }

    /**
     * Returns a new Requestor to send request containing string payloads.
     *
     * @param channel communication channel
     * @return a new Requestor
     */
    static Requestor<Payload> stringPayload(final CastV2Channel channel) {
        return new Requestor<>(channel, STRING_PAYLOAD, STD_CORRELATOR);
    }

    @Override
    public final void messageReceived(final CastMessage message) {
        lock.lock();
        try {
            if (correlator.test(req, message)) {
                LOGGER.fine(() -> "Received response [" + message + "] answering request");
                response = message;
                responseReceived.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public final void socketError() {
        // ignore, handled by connection.
    }

    /**
     * Transmits the given request and returns the received response.
     *
     * @param namespace request namespace
     * @param destination request destination
     * @param payload request payload
     * @param timeout status timeout
     * @return the received response, never null
     * @throws TimeoutException if the timeout has elapsed before a response was received
     */
    final CastMessage request(final String namespace, final String destination, final T payload,
            final Duration timeout) throws TimeoutException {
        return request(builder.build(namespace, destination, payload), timeout);
    }

    /**
     * Transmits the given request using {@link IoProperties#DEFAULT_RECEIVER_ID} as receiver ID and returns the
     * received response.
     *
     * @param namespace request namespace
     * @param payload request payload
     * @param timeout status timeout
     * @return the received response, never null
     * @throws TimeoutException if the timeout has elapsed before a response was received
     */
    final CastMessage request(final String namespace, final T payload, final Duration timeout)
            throws TimeoutException {
        return request(namespace, DEFAULT_RECEIVER_ID, payload, timeout);
    }

    /**
     * Await until a response to the request is received or the given timeout has elapsed which ever occurs first.
     *
     * @param timeout how long to wait before giving up
     */
    private void awaitResponse(final Duration timeout) {
        long nanos = timeout.toNanos();
        lock.lock();
        try {
            while (response == null) {
                if (nanos <= 0L) {
                    return;
                }
                nanos = responseReceived.awaitNanos(nanos);
            }
        } catch (final InterruptedException e) {
            LOGGER.log(Level.WARNING, "Interrupted while waiting for response", e);
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Transmits the given request and returns the received response.
     *
     * @param request the request to transmit
     * @param timeout status timeout
     * @return the received response, never null
     * @throws TimeoutException if the timeout has elapsed before a response was received
     */
    private CastMessage request(final CastMessage request, final Duration timeout) throws TimeoutException {
        try {
            channel.addListener(this, request.getNamespace());
            req = request;
            response = null;
            channel.send(request);
            awaitResponse(timeout);
        } finally {
            channel.removeListener(this);
        }
        if (response == null) {
            final String msg = "No response received within " + timeout;
            LOGGER.info(msg);
            throw new TimeoutException(msg);
        }
        return response;
    }

}
