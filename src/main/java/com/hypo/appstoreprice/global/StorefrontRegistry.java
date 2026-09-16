package com.hypo.appstoreprice.global;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;
import static com.hypo.appstoreprice.global.Models.*;

@Component
public class StorefrontRegistry {
    public static final List<String> LEGACY = List.of("us", "cn", "tw", "hk", "jp", "kr", "ph", "tr", "ng", "in", "pk", "br", "eg");
    public static final List<String> PRIORITY = List.of("us", "cn", "hk", "tw", "jp", "kr", "sg", "tr", "in", "gb", "ca", "au", "de", "fr");
    private final LinkedHashMap<String, Storefront> storefronts = new LinkedHashMap<>();
    private final String verifiedAt;
    public StorefrontRegistry() throws IOException {
        JSONObject data;
        try (var in = new ClassPathResource("storefronts.json").getInputStream()) {
            data = JSON.parseObject(in.readAllBytes());
        }
        verifiedAt = data.getString("verifiedAt");
        for (Storefront s : data.getJSONArray("storefronts").toJavaList(Storefront.class)) {
            if (!s.code().matches("[a-z]{2}") || storefronts.putIfAbsent(s.code(), s) != null)
                throw new IllegalStateException("Invalid or duplicate storefront: " + s.code());
        }
        if (storefronts.isEmpty()) throw new IllegalStateException("Empty storefront registry");
    }
    public List<Storefront> all() { return List.copyOf(storefronts.values()); }
    public String verifiedAt() { return verifiedAt; }
    public Storefront get(String code) {
        Storefront value = storefronts.get(code.toLowerCase(Locale.ROOT));
        if (value == null) throw new IllegalArgumentException("不支持的地区: " + code);
        return value;
    }
    public List<Storefront> ordered(List<String> preferred) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String code : preferred) { get(code); codes.add(code.toLowerCase(Locale.ROOT)); }
        codes.addAll(PRIORITY); codes.addAll(storefronts.keySet());
        return codes.stream().map(this::get).toList();
    }
    public List<Storefront> selected(List<String> requested, String scope, List<String> preferred) {
        if (!Set.of("mainstream", "custom", "all").contains(scope))
            throw new IllegalArgumentException("不支持的查询范围: " + scope);
        if (scope.equals("custom") && requested.isEmpty())
            throw new IllegalArgumentException("请至少选择一个查询地区");
        List<String> selection = scope.equals("all") ? List.copyOf(storefronts.keySet())
                : scope.equals("custom") ? requested : PRIORITY;
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String code : selection) codes.add(get(code).code());
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String code : preferred) {
            String valid = get(code).code();
            if (codes.contains(valid)) ordered.add(valid);
        }
        ordered.addAll(codes);
        return ordered.stream().map(this::get).toList();
    }
}
