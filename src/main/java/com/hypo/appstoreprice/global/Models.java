package com.hypo.appstoreprice.global;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Immutable source observations are kept separate from calculated comparisons. */
public final class Models {
    private Models() {}
    public enum Status { AVAILABLE, UNAVAILABLE, FETCH_FAILED, PARSE_FAILED, RATE_LIMITED }
    public record Storefront(String code, String nameZh, String nameEn, String region,
                             String currencyCode, String currencySymbol, String locale) {}
    public record LocalPrice(BigDecimal amount, String currency, String formatted) {}
    public record Item(String name, String productId, String type, String period,
                       LocalPrice local, String englishName, String matchEvidence) {}
    public record App(String appId, String name, String subtitle, String developer, String icon) {}
    public record Region(String area, Status status, String message, App app, LocalPrice price,
                         List<Item> items, String sourceUrl, String fetchedAt, String language,
                         String iapCoverage, List<String> issues) {
        public static Region failure(String area, Status status, String message, String url) {
            return new Region(area, status, message, null, null, List.of(), url,
                    java.time.Instant.now().toString(), null, "UNKNOWN", List.of());
        }
    }
    public record Rates(String base, Map<String, BigDecimal> rates, String asOf, String fetchedAt,
                        String source, boolean stale, String message) {}
    public record Price(String area, String areaName, String region, String name,
                        LocalPrice local, BigDecimal cny, BigDecimal usd, BigDecimal exchangeRate,
                        String rateAsOf, String sourceUrl, String fetchedAt, String matchStatus,
                        String matchEvidence, boolean anomalousLow) {}
    public record Product(String productKey, String name, String type, String period,
                          String matchStatus, Map<String, String> names, List<Price> prices) {}
    public record Progress(int total, int completed, int available, int unavailable, int failed,
                           boolean complete) {}
    public record Snapshot(String appId, App app, List<Region> regions, List<Product> products,
                           Progress progress, Rates exchangeRates, String startedAt, List<String> areas) {}
}
