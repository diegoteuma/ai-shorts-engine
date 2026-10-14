package com.aishorts.engine.claude;

import com.anthropic.backends.Backend;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpRequestBody;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.errors.AnthropicIoException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Transporte del SDK de Anthropic sobre un OkHttp propio, porque el builder
 * del SDK no deja elegir protocolo ni pasar un OkHttpClient:
 *
 * - Solo HTTP/1.1: con HTTPS, OkHttp negocia HTTP/2 por ALPN, y en una red
 *   con inspección TLS se observó que cortaban las llamadas largas.
 * - readTimeout = tiempo máximo SIN recibir bytes (cada evento o ping del
 *   stream lo reinicia): es el timeout de inactividad, también para recibir
 *   los headers. callTimeout en 0: no hay tope de duración total mientras
 *   sigan llegando eventos.
 *
 * Se ignoran los timeouts de RequestOptions: los de este cliente son los
 * únicos que aplican.
 *
 * Igual que el transporte propio del SDK, cada request pasa por el Backend
 * (prepareRequest agrega anthropic-version, authorizeRequest la
 * autenticación) y cada respuesta por prepareResponse.
 */
final class Http11OkHttpClient implements HttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    private final OkHttpClient okHttp;
    private final Backend backend;
    private final HttpClient replaced;

    /**
     * @param backend  el mismo Backend que usa el cliente del SDK
     * @param replaced el transporte del SDK que este reemplaza; solo se cierra junto con este
     */
    Http11OkHttpClient(Duration idleTimeout, Backend backend, HttpClient replaced) {
        this.backend = backend;
        this.replaced = replaced;
        this.okHttp = new OkHttpClient.Builder()
                .protocols(List.of(Protocol.HTTP_1_1))
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(idleTimeout)
                .writeTimeout(idleTimeout)
                .callTimeout(Duration.ZERO)
                .retryOnConnectionFailure(false)
                .build();
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
        Call call = okHttp.newCall(toOkHttp(prepare(request)));
        try {
            return backend.prepareResponse(new OkHttpResponse(call.execute()));
        } catch (IOException e) {
            throw new AnthropicIoException("Request failed", e);
        }
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions requestOptions) {
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        Call call = okHttp.newCall(toOkHttp(prepare(request)));
        call.enqueue(new Callback() {
            @Override
            public void onResponse(Call c, Response response) {
                try {
                    future.complete(backend.prepareResponse(new OkHttpResponse(response)));
                } catch (RuntimeException e) {
                    response.close();
                    future.completeExceptionally(e);
                }
            }

            @Override
            public void onFailure(Call c, IOException e) {
                future.completeExceptionally(new AnthropicIoException("Request failed", e));
            }
        });
        future.whenComplete((r, e) -> {
            if (future.isCancelled()) {
                call.cancel();
            }
        });
        return future;
    }

    @Override
    public void close() {
        okHttp.dispatcher().executorService().shutdown();
        okHttp.connectionPool().evictAll();
        replaced.close();
    }

    private HttpRequest prepare(HttpRequest request) {
        return backend.authorizeRequest(backend.prepareRequest(request));
    }

    private static Request toOkHttp(HttpRequest request) {
        Request.Builder builder = new Request.Builder().url(request.url());
        Headers headers = request.headers();
        for (String name : headers.names()) {
            for (String value : headers.values(name)) {
                builder.addHeader(name, value);
            }
        }
        HttpRequestBody body = request.body();
        String method = request.method().name();
        boolean needsBody = method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
        if (body != null) {
            builder.method(method, new SdkRequestBody(body));
        } else {
            builder.method(method, needsBody ? RequestBody.create(new byte[0]) : null);
        }
        return builder.build();
    }

    /** El cuerpo del SDK escrito directo en la conexión de OkHttp. */
    private static final class SdkRequestBody extends RequestBody {
        private final HttpRequestBody body;

        SdkRequestBody(HttpRequestBody body) {
            this.body = body;
        }

        @Override
        public MediaType contentType() {
            return body.contentType() != null ? MediaType.parse(body.contentType()) : null;
        }

        @Override
        public long contentLength() {
            return body.contentLength();
        }

        @Override
        public boolean isOneShot() {
            return !body.repeatable();
        }

        @Override
        public void writeTo(BufferedSink sink) {
            body.writeTo(sink.outputStream());
        }
    }

    private static final class OkHttpResponse implements HttpResponse {
        private final Response response;
        private final Headers headers;

        OkHttpResponse(Response response) {
            this.response = response;
            Headers.Builder builder = Headers.builder();
            for (String name : response.headers().names()) {
                builder.put(name, response.headers(name));
            }
            this.headers = builder.build();
        }

        @Override
        public int statusCode() {
            return response.code();
        }

        @Override
        public Headers headers() {
            return headers;
        }

        @Override
        public InputStream body() {
            return response.body() != null ? response.body().byteStream() : InputStream.nullInputStream();
        }

        @Override
        public void close() {
            response.close();
        }
    }
}
