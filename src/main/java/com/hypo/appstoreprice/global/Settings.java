package com.hypo.appstoreprice.global;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Settings {
    @Value("${APPSTORE_FETCH_CONCURRENCY:8}") public int concurrency = 8;
    @Value("${APPSTORE_REQUEST_TIMEOUT:10000}") public int timeout = 10000;
    @Value("${APPSTORE_RETRY_COUNT:2}") public int retries = 2;
    @Value("${APPSTORE_CACHE_HOURS:6}") public long cacheHours = 6;
    @Value("${EXCHANGE_RATE_CACHE_HOURS:6}") public long rateHours = 6;
    @Value("${APPSTORE_QUEUE_CAPACITY:2048}") public int queueCapacity = 2048;
    @Value("${APPSTORE_MAX_QUERIES:16}") public int maxQueries = 16;
    @Value("${EXCHANGE_RATE_URL:https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.json}")
    public String rateUrl;
    @Value("${EXCHANGE_RATE_FALLBACK_URL:https://latest.currency-api.pages.dev/v1/currencies/usd.json}")
    public String rateFallbackUrl;
    @Value("${APPSTORE_DATA_DIR:./data}") public String dataDir = "./data";
}
