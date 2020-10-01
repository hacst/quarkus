package io.quarkus.rest.runtime.client;

import java.io.ByteArrayInputStream;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.net.impl.ConnectionBase;

public class QuarkusRestMultiInvoker extends AbstractRxInvoker<Multi<?>> {

    private WebTarget target;

    public QuarkusRestMultiInvoker(WebTarget target) {
        this.target = target;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <R> Multi<R> get(Class<R> responseType) {
        return (Multi<R>) super.get(responseType);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <R> Multi<R> get(GenericType<R> responseType) {
        return (Multi<R>) super.get(responseType);
    }

    @Override
    public <R> Multi<R> method(String name, Entity<?> entity, GenericType<R> responseType) {
        QuarkusRestAsyncInvoker invoker = (QuarkusRestAsyncInvoker) target.request().rx();
        // FIXME: backpressure setting?
        return Multi.createFrom().emitter(emitter -> {
            InvocationState invocationState = invoker.performRequestInternal(name, entity, responseType, false);
            invocationState.getResult().handle((response, connectionError) -> {
                if (connectionError != null) {
                    emitter.fail(connectionError);
                } else {
                    HttpClientResponse vertxResponse = invocationState.getVertxClientResponse();
                    // FIXME: this is probably not good enough
                    if (response.getStatus() == 200
                            && MediaType.SERVER_SENT_EVENTS_TYPE.isCompatible(response.getMediaType())) {
                        registerForSse(emitter, vertxResponse);
                    } else {
                        // read stuff in chunks
                        registerForChunks(emitter, invocationState, responseType, response, vertxResponse);
                    }
                }
                return null;
            });
        });
    }

    @SuppressWarnings("unchecked")
    private <R> void registerForSse(MultiEmitter<? super R> emitter, HttpClientResponse vertxResponse) {
        // honestly, isn't reconnect contradictory with completion?
        // FIXME: Reconnect settings?
        QuarkusRestSseEventSource sseSource = new QuarkusRestSseEventSource(target, 0, null);
        // FIXME: deal with cancellation
        sseSource.register(event -> {
            // FIXME: non-String
            emitter.emit((R) event.readData());
        }, error -> {
            emitter.fail(error);
        }, () -> {
            emitter.complete();
        });
        sseSource.registerAfterRequest(vertxResponse);
    }

    private <R> void registerForChunks(MultiEmitter<? super R> emitter,
            InvocationState invocationState,
            GenericType<R> responseType,
            Response response,
            HttpClientResponse vertxClientResponse) {
        // make sure we get exceptions on the response, like close events, otherwise they
        // will be logged as errors by vertx
        vertxClientResponse.exceptionHandler(t -> {
            if (t == ConnectionBase.CLOSED_EXCEPTION) {
                // we can ignore this one since we registered a closeHandler
            } else {
                emitter.fail(t);
            }
        });
        HttpConnection connection = vertxClientResponse.request().connection();
        // this captures the server closing
        connection.closeHandler(v -> {
            emitter.complete();
        });
        vertxClientResponse.handler(new Handler<Buffer>() {
            @Override
            public void handle(Buffer buffer) {
                try {
                    ByteArrayInputStream in = new ByteArrayInputStream(buffer.getBytes());
                    R item = invocationState.readEntity(in, responseType, response.getMediaType(), response.getMetadata());
                    emitter.emit(item);
                } catch (Throwable t) {
                    // FIXME: probably close the client too? watch out that it doesn't call our close handler
                    // which calls emitter.complete()
                    emitter.fail(t);
                }
            }
        });
        // this captures the end of the response
        // FIXME: won't this call complete twice()?
        vertxClientResponse.endHandler(v -> {
            emitter.complete();
        });
    }

}