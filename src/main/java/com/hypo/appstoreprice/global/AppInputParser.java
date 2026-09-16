package com.hypo.appstoreprice.global;

import java.net.URI;
import java.util.regex.Pattern;

public final class AppInputParser {
    private static final Pattern ID = Pattern.compile("(?:^|/)id([0-9]+)(?:/|$)");
    private AppInputParser() {}
    public static String parse(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("请输入 App ID 或 App Store URL");
        String value = input.trim();
        if (value.matches("(?:id)?[0-9]{1,20}")) return value.replaceFirst("^id", "");
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"apps.apple.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443))
                throw new IllegalArgumentException();
            var matcher = ID.matcher(uri.getPath());
            if (matcher.find() && matcher.group(1).length() <= 20) return matcher.group(1);
        } catch (IllegalArgumentException ignored) { }
        throw new IllegalArgumentException("请输入有效的 App ID 或 apps.apple.com 应用链接");
    }
}
