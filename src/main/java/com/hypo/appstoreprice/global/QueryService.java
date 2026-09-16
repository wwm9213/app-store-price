package com.hypo.appstoreprice.global;

import com.google.common.cache.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.hypo.appstoreprice.global.Models.*;

@Service
public class QueryService {
    private final StorefrontRegistry registry;
    private final AppFetcher fetcher;
    private final ExchangeRateService exchange;
    private final ProductMatcher matcher;
    private final Settings settings;
    private final Map<String, Query> active = new HashMap<>();
    private final Cache<String, Query> completed;
    private final ExecutorService orchestration = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();

    public QueryService(StorefrontRegistry registry, AppFetcher fetcher, ExchangeRateService exchange, ProductMatcher matcher, Settings settings) {
        this.registry = registry; this.fetcher = fetcher; this.exchange = exchange; this.matcher = matcher; this.settings = settings;
        completed = CacheBuilder.newBuilder().maximumSize(64).expireAfterWrite(Duration.ofHours(settings.cacheHours)).build();
        heartbeat.scheduleAtFixedRate(this::heartbeat, 15, 15, TimeUnit.SECONDS);
    }
    public synchronized Query query(String input, List<String> priority, boolean retryFailed) {
        return query(input, priority, retryFailed, List.of(), "mainstream");
    }
    public synchronized Query query(String input, List<String> priority, boolean retryFailed, List<String> areas, String scope) {
        String id = AppInputParser.parse(input);
        List<Storefront> order = registry.selected(areas, scope, priority);
        String key = id + ":" + String.join(",", order.stream().map(Storefront::code).sorted().toList());
        if (active.containsKey(key)) return active.get(key);
        Query previous = completed.getIfPresent(key);
        if (previous != null && !retryFailed) return previous;
        if (active.size() >= settings.maxQueries) throw new IllegalStateException("同时查询过多，请稍后重试");
        Query q = new Query(id, order.stream().map(Storefront::code).toList());
        if (previous != null) synchronized (previous) {
            previous.regions.values().stream().filter(r -> !AppFetcher.failed(r)).forEach(r -> q.regions.put(r.area(), r));
        }
        active.put(key, q);
        orchestration.submit(() -> {
            try {
                q.rates = exchange.snapshot();
                List<CompletableFuture<Void>> tasks = new ArrayList<>();
                for (Storefront area : order) {
                    synchronized (q) { if (q.regions.containsKey(area.code())) continue; }
                    tasks.add(fetcher.fetch(id, area, retryFailed).handle((region, error) -> {
                        q.accept(error == null ? region : failed(id, area, error));
                        return null;
                    }));
                }
                CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
            } catch (Exception ex) {
                synchronized (q) {
                    for (Storefront area : order) if (!q.regions.containsKey(area.code())) q.accept(failed(id, area, ex));
                }
            } finally {
                q.finish();
                synchronized (QueryService.this) { completed.put(key, q); active.remove(key, q); }
            }
        });
        return q;
    }
    private static Region failed(String id, Storefront area, Throwable error) {
        return Region.failure(area.code(), Status.FETCH_FAILED, "查询任务失败: " + error.getClass().getSimpleName(), "https://apps.apple.com/" + area.code() + "/app/id" + id);
    }
    public class Query {
        private final String id;
        private final List<String> areas;
        private final String startedAt = Instant.now().toString();
        private final LinkedHashMap<String, Region> regions = new LinkedHashMap<>();
        private final Set<SseEmitter> subscribers = new CopyOnWriteArraySet<>();
        private volatile Rates rates = new Rates("USD", Map.of(), null, null, null, true, "正在获取汇率");
        private boolean done;
        Query(String id, List<String> areas) { this.id = id; this.areas = List.copyOf(areas); }
        public synchronized Snapshot snapshot() {
            List<Region> values = List.copyOf(regions.values());
            int available = (int) values.stream().filter(r -> r.status() == Status.AVAILABLE).count();
            int unavailable = (int) values.stream().filter(r -> r.status() == Status.UNAVAILABLE).count();
            App app = values.stream().filter(r -> r.app() != null).sorted(Comparator.comparingInt(r -> r.area().equals("cn") ? 0 : r.area().equals("us") ? 1 : 2)).map(Region::app).findFirst().orElse(null);
            return new Snapshot(id, app, values, matcher.products(values, rates), new Progress(areas.size(), values.size(), available, unavailable, values.size() - available - unavailable, done), rates, startedAt, areas);
        }
        synchronized void accept(Region region) { regions.put(region.area(), region); publish("region", snapshot()); }
        synchronized void finish() { done = true; publish("complete", snapshot()); subscribers.forEach(SseEmitter::complete); subscribers.clear(); }
        public synchronized SseEmitter subscribe() {
            SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
            Runnable remove = () -> { synchronized (this) { subscribers.remove(emitter); } };
            emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError(e -> remove.run());
            if (!send(emitter, done ? "complete" : "snapshot", snapshot())) return emitter;
            if (done) emitter.complete(); else subscribers.add(emitter);
            return emitter;
        }
        synchronized void ping() { subscribers.removeIf(e -> !send(e, "heartbeat", Map.of("time", Instant.now().toString()))); }
        private void publish(String event, Object value) { subscribers.removeIf(e -> !send(e, event, value)); }
        private boolean send(SseEmitter emitter, String event, Object value) {
            try { emitter.send(SseEmitter.event().name(event).data(value)); return true; }
            catch (Exception ex) { emitter.complete(); return false; }
        }
    }
    private void heartbeat() {
        List<Query> copy;
        synchronized (this) { copy = List.copyOf(active.values()); }
        copy.forEach(Query::ping);
    }
    @PreDestroy public void close() { heartbeat.shutdownNow(); orchestration.shutdownNow(); }
}
