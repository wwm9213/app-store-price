package com.hypo.appstoreprice.global;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.regex.Pattern;
import static com.hypo.appstoreprice.global.Models.*;

public final class PriceParser {
    private static final Pattern NUMBER = Pattern.compile("[0-9]+(?:[.,][0-9]+)*");
    private PriceParser() {}
    public static LocalPrice parse(String text, String currency, String locale) {
        if (text == null || text.isBlank() || currency == null)
            throw new IllegalArgumentException("价格或币种缺失");
        String cleaned = text.replaceAll("[\\s\\u00a0\\u202f'’]", "");
        if (cleaned.matches(".*[-−][0-9].*")) throw new IllegalArgumentException("价格不能为负数: " + text);
        var matcher = NUMBER.matcher(cleaned);
        if (!matcher.find()) throw new IllegalArgumentException("无法解析价格: " + text);
        String number = matcher.group();
        if (matcher.find()) throw new IllegalArgumentException("包含多个金额: " + text);
        int comma = number.lastIndexOf(','), dot = number.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            char decimal = comma > dot ? ',' : '.';
            number = number.replace(decimal == ',' ? "." : ",", "").replace(decimal, '.');
        } else if (comma >= 0 || dot >= 0) {
            char sep = comma >= 0 ? ',' : '.';
            int tail = number.length() - number.lastIndexOf(sep) - 1;
            int digits = Currency.getInstance(currency).getDefaultFractionDigits();
            long count = number.chars().filter(c -> c == sep).count();
            if (count > 1 || (tail == 3 && digits != 3)) number = number.replace(String.valueOf(sep), "");
            else number = number.replace(sep, '.');
        }
        BigDecimal amount = new BigDecimal(number);
        // Apple abbreviates Indonesian thousands as "ribu", including on its English page.
        if ("IDR".equals(currency) && cleaned.toLowerCase(java.util.Locale.ROOT).endsWith("ribu"))
            amount = amount.multiply(BigDecimal.valueOf(1000));
        return new LocalPrice(amount, currency, text);
    }
}
