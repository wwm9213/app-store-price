package com.hypo.appstoreprice.global;

import com.google.common.cache.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.hypo.appstoreprice.global.Models.*;

@Service
public class AppFetcher {
    record Cached(Region value, Instant expires) {}
    private final Cache<String, Cached> cache = CacheBuilder.newBuilder().maximumSize(10000).build();
    private final Cache<String, App> metadata = CacheBuilder.newBuilder().maximumSize(10000).expireAfterWrite(Duration.ofDays(1)).build();
    private final ConcurrentHashMap<String, CompletableFuture<Region>> inFlight = new ConcurrentHashMap<>();
    private final AppleClient client;
    private final AppStoreParser parser;
    private final Settings settings;
    public AppFetcher(AppleClient client, AppStoreParser parser, Settings settings) {
        this.client = client; this.parser = parser; this.settings = settings;
    }
    public App metadata(String appId, String area) { return metadata.getIfPresent(appId + ":" + area); }
    public CompletableFuture<Region> fetch(String appId, Storefront area, boolean retryFailed) {
        String key = appId + ":" + area.code();
        Cached hit = cache.getIfPresent(key);
        if (hit != null && hit.expires().isAfter(Instant.now()) && !(retryFailed && failed(hit.value())))
            return CompletableFuture.completedFuture(hit.value());
        CompletableFuture<Region> pending = new CompletableFuture<>();
        CompletableFuture<Region> existing = inFlight.putIfAbsent(key, pending);
        if (existing != null) return existing;
        client.submit(() -> load(appId, area)).whenComplete((region, error) -> {
            Region result = error == null ? region : Region.failure(area.code(), Status.FETCH_FAILED, "抓取队列繁忙", url(appId, area));
            Duration ttl = switch (result.status()) {
                case AVAILABLE -> Duration.ofHours(settings.cacheHours);
                case UNAVAILABLE -> Duration.ofDays(1);
                default -> Duration.ofSeconds(30);
            };
            cache.put(key, new Cached(result, Instant.now().plus(ttl)));
            if (result.app() != null) metadata.put(key, result.app());
            pending.complete(result);
            inFlight.remove(key, pending);
        });
        return pending;
    }
    public static boolean failed(Region r) { return r.status() != Status.AVAILABLE && r.status() != Status.UNAVAILABLE; }
    private static String url(String id, Storefront area) { return "https://apps.apple.com/" + area.code() + "/app/id" + id; }
    private Region load(String id, Storefront area) {
        String url = url(id, area);
        try {
            AppleClient.Page page = client.get(url);
            if (!AppStoreParser.sameStorefront(page.url(), area.code())) return Region.failure(area.code(), Status.PARSE_FAILED, "storefront 重定向不一致", page.url());
            if (page.status() == 404 || page.status() == 410) return Region.failure(area.code(), Status.UNAVAILABLE, "该地区没有公开应用页面", page.url());
            if (page.status() == 429) return Region.failure(area.code(), Status.RATE_LIMITED, "Apple 限流，请稍后重试", page.url());
            if (page.status() != 200) return Region.failure(area.code(), Status.FETCH_FAILED, "Apple HTTP " + page.status(), page.url());
            Region region;
            try { region = parser.parse(id, area, page.url(), page.body()); }
            catch (RuntimeException ex) { return Region.failure(area.code(), Status.PARSE_FAILED, "页面解析失败: " + ex.getMessage(), page.url()); }
            if (!region.items().isEmpty() && !region.language().startsWith("en")) {
                try {
                    var english = client.get(url + "?l=en-US");
                    if (english.status() != 200) throw new IllegalStateException("Apple HTTP " + english.status());
                    Region translated = parser.parse(id, area, english.url(), english.body());
                    if (!translated.language().startsWith("en")) throw new IllegalStateException("辅助页未返回英文");
                    region = ProductMatcher.withEnglishNames(region, translated);
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                    List<String> issues = new ArrayList<>(region.issues()); issues.add("英文辅助页面不可用，保留原始项目");
                    region = new Region(region.area(), region.status(), region.message(), region.app(), region.price(), region.items(), region.sourceUrl(), region.fetchedAt(), region.language(), region.iapCoverage(), List.copyOf(issues));
                }
            }
            return region;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); return Region.failure(area.code(), Status.FETCH_FAILED, "请求已取消", url);
        } catch (Exception ex) { return Region.failure(area.code(), Status.FETCH_FAILED, ex.getClass().getSimpleName() + ": " + ex.getMessage(), url); }
    }
}
