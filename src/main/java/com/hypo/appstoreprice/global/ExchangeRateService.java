package com.hypo.appstoreprice.global;

import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Service;
import java.net.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.hypo.appstoreprice.global.Models.*;

@Service
public class ExchangeRateService {
    private final Settings settings;
    private Rates last;
    private Instant nextAttempt = Instant.EPOCH;
    public ExchangeRateService(Settings settings) {
        this.settings = settings;
        try {
            Path path = Path.of(settings.dataDir, "exchange-rates.json");
            if (Files.exists(path)) last = JSON.parseObject(Files.readString(path), Rates.class);
        } catch (Exception ignored) { /* An unreadable cache never prevents local-price queries. */ }
    }
    public synchronized Rates snapshot() {
        Instant now = Instant.now();
        if (last != null && Instant.parse(last.fetchedAt()).plus(Duration.ofHours(settings.rateHours)).isAfter(now)) return last;
        if (nextAttempt.isAfter(now)) return stale();
        nextAttempt = now.plusSeconds(60);
        for (String url : List.of(settings.rateUrl, settings.rateFallbackUrl)) {
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
                c.setConnectTimeout(settings.timeout); c.setReadTimeout(settings.timeout);
                try (var input = c.getInputStream()) {
                    last = parse(input.readAllBytes(), url, now);
                } finally { c.disconnect(); }
                try {
                    Path dir = Path.of(settings.dataDir); Files.createDirectories(dir);
                    Path temp = dir.resolve("exchange-rates.tmp"); Files.writeString(temp, JSON.toJSONString(last));
                    Files.move(temp, dir.resolve("exchange-rates.json"), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) { /* In-memory last success remains usable on read-only disks. */ }
                return last;
            } catch (Exception ignored) { }
        }
        return stale();
    }
    static Rates parse(byte[] bytes, String source, Instant now) {
        var json = JSON.parseObject(bytes);
        var object = json.getJSONObject("usd");
        if (object == null || json.getString("date") == null) throw new IllegalArgumentException("汇率结构不完整");
        LocalDate.parse(json.getString("date"));
        Map<String, BigDecimal> rates = new TreeMap<>();
        object.forEach((key, value) -> {
            if (key.matches("[a-z]{3}")) {
                BigDecimal rate = new BigDecimal(value.toString());
                if (rate.signum() > 0) rates.put(key.toUpperCase(Locale.ROOT), rate);
            }
        });
        if (!BigDecimal.ONE.equals(rates.get("USD")) || !rates.containsKey("CNY")) throw new IllegalArgumentException("缺少 USD/CNY 汇率");
        return new Rates("USD", Map.copyOf(rates), json.getString("date"), now.toString(), source, false, null);
    }
    private Rates stale() {
        return last == null ? new Rates("USD", Map.of(), null, null, null, true, "汇率暂不可用，仅展示当地价格") :
                new Rates(last.base(), last.rates(), last.asOf(), last.fetchedAt(), last.source(), true, "刷新失败，使用最近成功汇率");
    }
    public static BigDecimal convert(LocalPrice price, Rates rates, String target) {
        if (price == null || price.amount() == null) return null;
        if (price.currency().equals(target)) return price.amount();
        BigDecimal from = rates.rates().get(price.currency()), to = rates.rates().get(target);
        if (from == null || to == null) return null;
        return price.amount().divide(from, MathContext.DECIMAL128).multiply(to, MathContext.DECIMAL128);
    }
}
