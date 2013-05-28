/*
 * Copyright (c) 2008-2012, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.spi;

import com.hazelcast.nio.serialization.Data;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * @ali 5/20/13
 */
public final class ListenerSupport  {

    private final ClientContext context;
    private final EventHandler handler;
    private final Object registrationRequest;
    private Future<?> future;
    private volatile boolean active = true;
    private volatile ResponseStream lastStream;
    private Data key;

    public ListenerSupport(ClientContext context, Object registrationRequest, EventHandler handler) {
        this.context = context;
        this.registrationRequest = registrationRequest;
        this.handler = handler;
    }

    public ListenerSupport(ClientContext context, Object registrationRequest, EventHandler handler, Data key) {
        this(context,registrationRequest, handler);
        this.key = key;
    }

    public String listen() {
        future = context.getExecutionService().submit(new Runnable() {
            public void run() {
                while (active && !Thread.currentThread().isInterrupted()) {
                    try {
                        EventResponseHandler eventResponseHandler = new EventResponseHandler();
                        if (key == null){
                            context.getInvocationService().invokeOnRandomTarget(registrationRequest, eventResponseHandler);
                        } else {
                            context.getInvocationService().invokeOnKeyOwner(registrationRequest, key, eventResponseHandler);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        });
        return UUID.randomUUID().toString();
    }

    public void stop() {
        active = false;
        if (future != null) {
            future.cancel(true);
        }
        final ResponseStream s = lastStream;
        if (s != null) {
            try {
                s.end();
            } catch (IOException ignored) {
            }
        }
    }

    private class EventResponseHandler implements ResponseHandler {

        public void handle(final ResponseStream stream) throws Exception {
            stream.read(); // initial ok response
            lastStream = stream;

            while (active && !Thread.currentThread().isInterrupted()) {
                try {
                    final Object event = stream.read();
                    handler.handle(event);
                } catch (Exception e) {
                    try {
                        stream.end();
                    } catch (IOException ignored) {
                    }
                    if (!(e instanceof IOException)) {
                        active = false;
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

}