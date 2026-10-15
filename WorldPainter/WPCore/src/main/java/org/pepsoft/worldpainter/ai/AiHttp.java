package org.pepsoft.worldpainter.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Shared bounded transport. Deadlines cover body reads; redirects and diagnostic body echoes are disabled. */
final class AiHttp {
    AiHttp(Duration connectTimeout, Duration deadline, int maximumBytes) {
        this.deadline = deadline;
        this.maximumBytes = maximumBytes;
        client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    Reply exchange(HttpRequest request) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("AI request canceled");
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, info -> new LimitedBody(maximumBytes));
        try {
            HttpResponse<byte[]> response = pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            return new Reply(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new IOException("AI request timed out");
        } catch (ExecutionException e) {
            pending.cancel(true);
            Throwable cause = e.getCause();
            while (cause != null && !(cause instanceof TooLarge)) cause = cause.getCause();
            throw new IOException(cause == null ? "AI request failed; check the endpoint and connection"
                    : maximumBytes == 1_048_576 ? "AI response exceeds the 1 MiB limit" : "AI response exceeds its byte limit");
        }
    }

    record Reply(int status, byte[] body) { }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) value.cancel();
            else { subscription = value; value.request(1); }
        }
        @Override public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer buffer : items) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new TooLarge());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable value) { result.completeExceptionally(value); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
    }
    private static final class TooLarge extends IOException { }
    private final HttpClient client;
    private final Duration deadline;
    private final int maximumBytes;
}
