package com.hypo.appstoreprice.global;

import com.alibaba.fastjson2.*;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static com.hypo.appstoreprice.global.Models.*;

@Component
public class AppStoreParser {
    public Region parse(String appId, Storefront store, String url, String html) {
        if (!sameStorefront(url, store.code()) || !AppInputParser.parse(url).equals(appId))
            throw new IllegalArgumentException("响应跳转到其他 App 或地区");
        var doc = Jsoup.parse(html);
        var script = doc.selectFirst("#serialized-server-data");
        if (script == null) throw new IllegalArgumentException("缺少 serialized-server-data");
        JSONObject data = JSON.parseObject(script.data()).getJSONArray("data").getJSONObject(0).getJSONObject("data");
        JSONObject lockup = data.getJSONObject("lockup");
        JSONObject offer = lockup.getJSONObject("offerDisplayProperties");
        if (!appId.equals(offer.getString("adamId"))) throw new IllegalArgumentException("页面 App ID 不匹配");
        String marker = data.getJSONObject("pageMetrics") == null ? null :
                data.getJSONObject("pageMetrics").getJSONObject("pageFields").getString("storeFront");
        if (marker != null && !store.code().equalsIgnoreCase(marker)) throw new IllegalArgumentException("页面 storefront 不匹配");
        JSONObject structured = null;
        for (var element : doc.select("script[type=application/ld+json]")) {
            JSONObject candidate = JSON.parseObject(element.data());
            if ("SoftwareApplication".equals(candidate.getString("@type"))) structured = candidate;
        }
        JSONObject seoOffer = structured == null ? null : structured.getJSONObject("offers");
        String currency = seoOffer == null ? store.currencyCode() : seoOffer.getString("priceCurrency");
        if (currency == null) throw new IllegalArgumentException("页面缺少可确认的币种");
        Currency.getInstance(currency);
        LocalPrice price = null;
        List<String> issues = new ArrayList<>();
        String formatted = offer.getString("priceFormatted");
        if (seoOffer != null && seoOffer.getBigDecimal("price") != null) {
            price = new LocalPrice(seoOffer.getBigDecimal("price"), currency,
                    formatted == null ? (offer.getBooleanValue("isFree") ? "Free" : seoOffer.getString("price")) : formatted);
        } else if (offer.getBooleanValue("isFree")) {
            price = new LocalPrice(java.math.BigDecimal.ZERO, currency, "Free");
        } else {
            try { price = PriceParser.parse(formatted, currency, store.locale()); }
            catch (IllegalArgumentException ex) { issues.add("App 本体价格: " + ex.getMessage()); }
        }
        List<Item> items = new ArrayList<>();
        var shelves = data.getJSONObject("shelfMapping");
        var information = shelves == null ? null : shelves.getJSONObject("information");
        boolean found = false;
        if (information != null && information.getJSONArray("items") != null) {
            for (Object entry : information.getJSONArray("items")) {
                JSONObject section = (JSONObject) entry;
                JSONArray children = section.getJSONArray("items");
                JSONArray pairs = null;
                if (children != null) for (Object child : children) {
                    JSONArray candidate = ((JSONObject) child).getJSONArray("textPairs");
                    if (candidate != null) pairs = candidate;
                }
                JSONArray v3 = section.getJSONArray("items_V3");
                boolean inapp = section.toJSONString().contains("\"origin\":\"inapp\"");
                // The information shelf's legacy text-pair annotation is the IAP price list.
                if (pairs != null) {
                    found = true;
                    for (Object pair : pairs) {
                        JSONArray p = (JSONArray) pair;
                        add(items, issues, p.getString(0), p.getString(1), null, null, null, currency, store.locale());
                    }
                } else if (v3 != null && inapp) {
                    found = true;
                    for (Object value : v3) {
                        JSONObject p = (JSONObject) value;
                        if ("textPair".equals(p.getString("$kind")))
                            add(items, issues, p.getString("leadingText"), p.getString("trailingText"),
                                    p.getString("productIdentifier"), p.getString("productType"), p.getString("subscriptionPeriod"), currency, store.locale());
                    }
                }
            }
        }
        if (!found && offer.getBooleanValue("hasInAppPurchases")) issues.add("页面声明有内购，但没有公开可解析的项目列表");
        String icon = structured == null ? null : structured.getString("image");
        String language = doc.selectFirst("html") == null ? "" : doc.selectFirst("html").attr("lang");
        if (language.startsWith("en")) items = items.stream().map(i -> new Item(i.name(), i.productId(), i.type(), i.period(), i.local(), i.name(), "页面英文名称")).toList();
        App app = new App(appId, data.getString("title"), lockup.getString("subtitle"),
                data.getJSONObject("developerAction").getString("title"), icon);
        return new Region(store.code(), Status.AVAILABLE, null, app, price, List.copyOf(items), url,
                Instant.now().toString(), language, found ? "PUBLIC_PAGE_ONLY" : "NOT_EXPOSED", List.copyOf(issues));
    }
    private void add(List<Item> items, List<String> issues, String name, String text, String id,
                     String type, String period, String currency, String locale) {
        try {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("项目名称缺失");
            items.add(new Item(name, id, type == null ? "UNKNOWN" : type, period,
                    PriceParser.parse(text, currency, locale), null, null));
        } catch (IllegalArgumentException ex) {
            issues.add((name == null ? "内购" : name) + ": " + ex.getMessage());
            items.add(new Item(name == null ? "未命名项目" : name, id, "UNKNOWN", period,
                    new LocalPrice(null, currency, text), null, "价格解析失败"));
        }
    }
    static boolean sameStorefront(String url, String area) {
        URI uri = URI.create(url);
        return "apps.apple.com".equalsIgnoreCase(uri.getHost()) && uri.getPath().startsWith("/" + area + "/");
    }
}
