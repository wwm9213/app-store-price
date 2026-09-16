package com.hypo.appstoreprice.global;

import org.springframework.stereotype.Component;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;
import static com.hypo.appstoreprice.global.Models.*;

@Component
public class ProductMatcher {
    private final StorefrontRegistry registry;
    public ProductMatcher(StorefrontRegistry registry) { this.registry = registry; }
    static String normalize(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    static String amountKey(Item item) {
        return item.local().amount() == null ? null : item.local().currency() + ":" + item.local().amount().stripTrailingZeros().toPlainString();
    }
    public static Region withEnglishNames(Region local, Region english) {
        if (!local.area().equals(english.area()) || local.items().size() != english.items().size()) return local;
        Map<String, List<Item>> translated = english.items().stream().filter(i -> amountKey(i) != null).collect(Collectors.groupingBy(ProductMatcher::amountKey));
        Map<String, Long> counts = local.items().stream().filter(i -> amountKey(i) != null).collect(Collectors.groupingBy(ProductMatcher::amountKey, Collectors.counting()));
        List<Item> items = new ArrayList<>();
        for (Item i : local.items()) {
            String key = amountKey(i);
            List<Item> candidates = key == null ? List.of() : translated.getOrDefault(key, List.of());
            if (key != null && counts.get(key) == 1 && candidates.size() == 1 && compatible(i, candidates.getFirst())) {
                Item other = candidates.getFirst();
                items.add(new Item(i.name(), i.productId(), i.type(), i.period(), i.local(), other.name(), "同区英文页唯一金额对应: " + english.sourceUrl()));
            } else items.add(i);
        }
        return new Region(local.area(), local.status(), local.message(), local.app(), local.price(), List.copyOf(items), local.sourceUrl(), local.fetchedAt(), local.language(), local.iapCoverage(), local.issues());
    }
    private static boolean compatible(Item a, Item b) {
        return (a.productId() == null || b.productId() == null || a.productId().equals(b.productId()))
                && ("UNKNOWN".equals(a.type()) || "UNKNOWN".equals(b.type()) || a.type().equals(b.type()))
                && (a.period() == null || b.period() == null || a.period().equals(b.period()));
    }
    private record Observed(Region region, Item item, int index) {}
    public List<Product> products(List<Region> regions, Rates rates) {
        List<Product> products = new ArrayList<>();
        List<Price> appPrices = regions.stream().filter(r -> r.status() == Status.AVAILABLE && r.price() != null)
                .map(r -> price(r, "软件本体", r.price(), "EXACT", "App ID", rates)).toList();
        if (!appPrices.isEmpty()) products.add(new Product("app", "软件本体", "APP", null, "EXACT", Map.of(), anomaly(appPrices)));
        Map<String, List<Observed>> groups = new TreeMap<>();
        for (Region r : regions) for (int index = 0; index < r.items().size(); index++) {
            Item i = r.items().get(index);
            String name = i.englishName() == null ? i.name() : i.englishName();
            String key = i.productId() == null ? "name:" + normalize(name) : "id:" + i.productId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new Observed(r, i, index));
        }
        groups.forEach((key, observations) -> {
            boolean duplicate = observations.stream().map(o -> o.region().area()).distinct().count() != observations.size();
            boolean conflict = observations.stream().map(o -> o.item().type()).filter(t -> !"UNKNOWN".equals(t)).distinct().count() > 1
                    || observations.stream().map(o -> o.item().period()).filter(Objects::nonNull).distinct().count() > 1;
            if (duplicate || conflict) {
                for (Observed o : observations) products.add(product("uncertain:" + key + ":" + o.region().area() + ":" + o.index(), List.of(o), "MATCH_UNCERTAIN", rates));
            } else products.add(product(key, observations, key.startsWith("id:") ? "EXACT" : observations.size() > 1 ? "INFERRED" : "MATCH_UNCERTAIN", rates));
        });
        return products;
    }
    private Product product(String key, List<Observed> observations, String status, Rates rates) {
        Observed first = observations.getFirst();
        Map<String, String> names = new TreeMap<>();
        List<Price> prices = new ArrayList<>();
        for (Observed o : observations) {
            names.put(o.region().area(), o.item().name());
            prices.add(price(o.region(), o.item().name(), o.item().local(), status,
                    "EXACT".equals(status) ? "商品 ID" : o.item().matchEvidence() == null ? "唯一显示名称，商品身份未经 ID 确认" : o.item().matchEvidence(), rates));
        }
        return new Product(hash(key), first.item().englishName() == null ? first.item().name() : first.item().englishName(),
                first.item().type(), first.item().period(), status, names, anomaly(prices));
    }
    private Price price(Region r, String name, LocalPrice local, String status, String evidence, Rates rates) {
        Storefront area = registry.get(r.area());
        BigDecimal cny = ExchangeRateService.convert(local, rates, "CNY"), usd = ExchangeRateService.convert(local, rates, "USD");
        BigDecimal rate = local.amount() == null || local.amount().signum() == 0 ? null : cny == null ? null : cny.divide(local.amount(), MathContext.DECIMAL128);
        return new Price(r.area(), area.nameZh(), area.region(), name, local, cny, usd, rate, rates.asOf(), r.sourceUrl(), r.fetchedAt(), status, evidence, false);
    }
    private List<Price> anomaly(List<Price> prices) {
        List<BigDecimal> values = prices.stream().map(Price::cny).filter(p -> p != null && p.signum() > 0).sorted().toList();
        BigDecimal threshold = null;
        if (values.size() >= 5) {
            int mid = values.size() / 2;
            BigDecimal median = values.size() % 2 == 0 ? values.get(mid - 1).add(values.get(mid)).divide(BigDecimal.TWO) : values.get(mid);
            threshold = median.divide(BigDecimal.valueOf(4));
        }
        final BigDecimal limit = threshold;
        return prices.stream().map(p -> new Price(p.area(), p.areaName(), p.region(), p.name(), p.local(), p.cny(), p.usd(), p.exchangeRate(), p.rateAsOf(), p.sourceUrl(), p.fetchedAt(), p.matchStatus(), p.matchEvidence(), limit != null && p.cny() != null && p.cny().signum() > 0 && p.cny().compareTo(limit) < 0))
                .sorted(Comparator.comparing(Price::cny, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Price::area)).toList();
    }
    private static String hash(String input) {
        try { return "iap-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
