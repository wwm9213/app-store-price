package com.hypo.appstoreprice.global;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Component
public class AppleClient implements AutoCloseable {
    public record Page(int status, String url, String body) {}
    private final Settings settings;
    private final ThreadPoolExecutor pool;
    private final AtomicLong pauseUntil = new AtomicLong();
    public AppleClient(Settings settings) {
        this.settings = settings;
        if (settings.concurrency < 1 || settings.concurrency > 32 || settings.timeout < 1 || settings.retries < 0 || settings.retries > 5)
            throw new IllegalArgumentException("Invalid fetch settings");
        pool = new ThreadPoolExecutor(settings.concurrency, settings.concurrency, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity), Thread.ofPlatform().name("apple-fetch-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
    public <T> CompletableFuture<T> submit(Supplier<T> operation) {
        try { return CompletableFuture.supplyAsync(operation, pool); }
        catch (RejectedExecutionException ex) { return CompletableFuture.failedFuture(new IllegalStateException("抓取队列已满，请稍后重试")); }
    }
    public Page get(String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt <= settings.retries; attempt++) {
            long wait = pauseUntil.get() - System.currentTimeMillis();
            if (wait > 0) sleep(wait);
            try {
                Page page = once(url);
                if (page.status() != 429 && page.status() < 500) return page;
                if (attempt == settings.retries) return page;
            } catch (IOException ex) {
                last = ex;
                if (attempt == settings.retries) throw ex;
            }
            sleep(500L * (1L << attempt) + ThreadLocalRandom.current().nextLong(250));
        }
        throw last == null ? new IOException("抓取失败") : last;
    }
    protected void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
    private Page once(String original) throws IOException {
        URI uri = URI.create(original);
        for (int redirect = 0; redirect < 6; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(settings.timeout);
            connection.setReadTimeout(settings.timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; AppStorePrice/2.0)");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400 && connection.getHeaderField("Location") != null) {
                    URI next = uri.resolve(connection.getHeaderField("Location"));
                    if (!uri.getHost().equalsIgnoreCase(next.getHost()) || !uri.getScheme().equalsIgnoreCase(next.getScheme()))
                        throw new IOException("拒绝跨站重定向");
                    uri = next; continue;
                }
                if (status == 429 || (status >= 500 && connection.getHeaderField("Retry-After") != null)) {
                    long delay = retryAfter(connection.getHeaderField("Retry-After"));
                    pauseUntil.accumulateAndGet(System.currentTimeMillis() + delay, Math::max);
                }
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String body = "";
                if (stream != null) try (stream) {
                    byte[] bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
                    if (bytes.length > 8 * 1024 * 1024) throw new IOException("页面超出大小限制");
                    body = new String(bytes, StandardCharsets.UTF_8);
                }
                return new Page(status, uri.toString(), body);
            } finally { connection.disconnect(); }
        }
        throw new IOException("重定向次数过多");
    }
    static long retryAfter(String value) {
        if (value == null) return 2000;
        try { return Math.max(0, Long.parseLong(value) * 1000); }
        catch (NumberFormatException ignored) {
            try { return Math.max(0, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()); }
            catch (Exception ex) { return 2000; }
        }
    }
    @PreDestroy public void close() { pool.shutdownNow(); }
}
