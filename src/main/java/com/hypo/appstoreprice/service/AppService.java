package com.hypo.appstoreprice.service;

import com.alibaba.fastjson2.*;
import com.google.common.cache.*;
import com.hypo.appstoreprice.global.*;
import com.hypo.appstoreprice.global.Models.*;
import com.hypo.appstoreprice.pojo.bean.Money;
import com.hypo.appstoreprice.pojo.request.GetAppListReqDTO;
import com.hypo.appstoreprice.pojo.response.*;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** Legacy API adapter: preserve wire fields and its original thirteen storefronts. */
@Service
public class AppService {
    private final StorefrontRegistry registry;
    private final AppFetcher fetcher;
    private final AppleClient client;
    private final ExchangeRateService exchange;
    private final ProductMatcher matcher;
    private final Cache<String, List<GetAppListResDTO>> searchCache = CacheBuilder.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofDays(1)).build();
    private final ConcurrentHashMap<String, CompletableFuture<List<GetAppListResDTO>>> searches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> popular = new ConcurrentHashMap<>();
    public AppService(StorefrontRegistry registry, AppFetcher fetcher, AppleClient client, ExchangeRateService exchange, ProductMatcher matcher) {
        this.registry = registry; this.fetcher = fetcher; this.client = client; this.exchange = exchange; this.matcher = matcher;
    }
    public List<AreaResDTO> getAreaList() {
        return StorefrontRegistry.LEGACY.stream().map(registry::get).map(s -> { AreaResDTO dto = new AreaResDTO(); dto.setCode(s.code()); dto.setName(s.nameZh()); return dto; }).toList();
    }
    public List<String> getPopularSearchWordList() {
        return popular.entrySet().stream().sorted(Comparator.<Map.Entry<String, LongAdder>>comparingLong(e -> e.getValue().sum()).reversed()).limit(10).map(Map.Entry::getKey).toList();
    }
    public List<GetAppListResDTO> getAppList(GetAppListReqDTO request) {
        Storefront area = registry.get(request.getAreaCode());
        String term = request.getAppName().trim(), key = area.code() + ":" + term;
        if (popular.size() < 1000 || popular.containsKey(term)) popular.computeIfAbsent(term, k -> new LongAdder()).increment();
        List<GetAppListResDTO> cached = searchCache.getIfPresent(key);
        if (cached != null) return cached;
        CompletableFuture<List<GetAppListResDTO>> pending = new CompletableFuture<>();
        var existing = searches.putIfAbsent(key, pending);
        if (existing != null) return existing.join();
        try {
            List<CompletableFuture<List<GetAppListResDTO>>> tasks = List.of("iphone", "ipad", "mac", "tv").stream().map(platform -> client.submit(() -> search(area.code(), term, platform))).toList();
            LinkedHashMap<String, GetAppListResDTO> found = new LinkedHashMap<>();
            int successful = 0;
            for (var task : tasks) {
                try { for (var app : task.join()) found.putIfAbsent(app.getAppId(), app); successful++; }
                catch (CompletionException ignored) { }
            }
            if (successful == 0) throw new IllegalStateException("Apple 搜索暂不可用，请稍后重试");
            List<GetAppListResDTO> result = found.values().stream().sorted(Comparator.comparingInt(a -> a.getAppName().equalsIgnoreCase(term) ? 0 : 1)).toList();
            if (successful == tasks.size()) searchCache.put(key, result);
            pending.complete(result); return result;
        } catch (RuntimeException ex) { pending.completeExceptionally(ex); throw ex; }
        finally { searches.remove(key, pending); }
    }
    private List<GetAppListResDTO> search(String area, String term, String platform) {
        String url = "https://apps.apple.com/" + area + "/" + platform + "/search?term=" + URLEncoder.encode(term, StandardCharsets.UTF_8);
        try {
            var page = client.get(url);
            if (page.status() != 200) throw new IllegalStateException("Apple HTTP " + page.status());
            var script = Jsoup.parse(page.body()).selectFirst("#serialized-server-data");
            if (script == null) throw new IllegalStateException("Apple 搜索结构发生变化");
            var data = JSON.parseObject(script.data()).getJSONArray("data").getJSONObject(0).getJSONObject("data");
            List<GetAppListResDTO> result = new ArrayList<>();
            JSONArray shelves = data.getJSONArray("shelves");
            if (shelves == null) throw new IllegalStateException("缺少搜索结果 shelves");
            for (Object s : shelves) {
                JSONArray items = ((JSONObject) s).getJSONArray("items");
                if (items == null) continue;
                for (Object entry : items) {
                    JSONObject item = (JSONObject) entry, lock = item.getJSONObject("lockup");
                    if (lock == null || "bundle".equals(item.getString("resultType"))) continue;
                    GetAppListResDTO dto = new GetAppListResDTO(); dto.setAppId(lock.getString("adamId")); dto.setAppName(lock.getString("title")); dto.setAppDesc(lock.getString("subtitle")); dto.setPlatform(platform);
                    JSONObject icon = lock.getJSONObject("icon");
                    if (icon != null && icon.getString("template") != null) dto.setAppImage(icon.getString("template").replace("{w}x{h}{c}.{f}", "512x512bb.jpg"));
                    if (dto.getAppId() != null) result.add(dto);
                }
            }
            return result;
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("搜索已取消", ex); }
        catch (Exception ex) { throw new IllegalStateException(ex.getMessage(), ex); }
    }
    private List<Region> regions(String input) {
        String id = AppInputParser.parse(input);
        var futures = StorefrontRegistry.LEGACY.stream().map(registry::get).map(a -> fetcher.fetch(id, a, false)).toList();
        return futures.stream().map(CompletableFuture::join).filter(r -> r.status() == Status.AVAILABLE).toList();
    }
    public List<GetAppInfoResDTO> getAppInfo(String id) {
        List<Region> regions = regions(id); Rates rates = exchange.snapshot();
        return regions.stream().map(r -> {
            GetAppInfoResDTO dto = new GetAppInfoResDTO(); dto.setAppId(r.app().appId()); dto.setArea(r.area()); dto.setAreaName(registry.get(r.area()).nameZh());
            dto.setName(r.app().name()); dto.setSubtitle(r.app().subtitle()); dto.setDeveloper(r.app().developer()); dto.setAppStoreUrl(r.sourceUrl()); dto.setPrice(money(r.area(), r.price(), rates));
            dto.setInAppPurchaseList(r.items().stream().map(i -> { InAppPurchaseDTO p = new InAppPurchaseDTO(); p.setObject(i.name()); p.setPrice(money(r.area(), i.local(), rates)); return p; }).toList());
            return dto;
        }).toList();
    }
    public List<GetAppInfoComparisonResDTO> getAppInfoComparison(String id) {
        List<Region> regions = regions(id); Rates rates = exchange.snapshot();
        return matcher.products(regions, rates).stream().map(p -> {
            GetAppInfoComparisonResDTO dto = new GetAppInfoComparisonResDTO(); dto.setObject(p.name());
            dto.setPriceList(p.prices().stream().map(v -> money(v.area(), v.local(), rates)).toList()); return dto;
        }).toList();
    }
    private Money money(String code, LocalPrice local, Rates rates) {
        if (local == null) return null;
        Storefront area = registry.get(code);
        return new Money(code, area.nameZh(), area.currencySymbol(), local.currency(), area.locale(), local.amount(), ExchangeRateService.convert(local, rates, "CNY"));
    }
}
