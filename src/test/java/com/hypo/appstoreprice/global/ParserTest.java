package com.hypo.appstoreprice.global;

import com.alibaba.fastjson2.*;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.hypo.appstoreprice.global.Models.*;

class ParserTest {
    static String fixture(String code) throws Exception {
        try (var in = ParserTest.class.getResourceAsStream("/fixtures/" + code + ".html")) {
            return new String(Objects.requireNonNull(in, "fixture " + code).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @ParameterizedTest
    @CsvSource(delimiter='|', value={"$4.99|USD|4.99", "HK$38.00|HKD|38.00", "NT$150|TWD|150", "¥600|JPY|600", "₩6,600|KRW|6600", "₹399|INR|399", "₺199,99|TRY|199.99", "R$ 19,90|BRL|19.90", "1.299,99 €|EUR|1299.99", "1,299.99|USD|1299.99", "1 299,99 €|EUR|1299.99", "￦7,700|KRW|7700", "1,23,456.78|INR|123456.78", "1.234|KWD|1.234", "Rp 99ribu|IDR|99000", "Rp 29ribu|IDR|29000", "149.000đ|VND|149000"})
    void parsesRealCurrencyFormats(String text, String currency, String expected) {
        LocalPrice p=PriceParser.parse(text,currency,"en");
        assertEquals(0,new BigDecimal(expected).compareTo(p.amount())); assertEquals(text,p.formatted()); assertEquals(currency,p.currency());
    }
    @Test void missingPriceIsNotFree() {
        assertThrows(IllegalArgumentException.class,()->PriceParser.parse(null,"USD","en"));
        assertThrows(IllegalArgumentException.class,()->PriceParser.parse("unavailable","USD","en"));
    }
    @Test void inputParsingAndUrlHostBoundary() {
        for(String s:List.of("1546947240","id1546947240","https://apps.apple.com/hk/app/xxx/id1546947240?l=en-US","https://apps.apple.com/us/app/id1546947240")) assertEquals("1546947240",AppInputParser.parse(s));
        for(String s:List.of("https://evil.test/app/id1546947240","https://apps.apple.com.evil.test/app/id1","https://apps.apple.com@evil.test/app/id1","https://apps.apple.com/app/id123x","https://apps.apple.com:8080/app/id1","hello")) assertThrows(IllegalArgumentException.class,()->AppInputParser.parse(s));
    }
    @Test void registryUsesAppleInventoryAndRetainsSharedCurrencyRegions() throws Exception {
        var registry=new StorefrontRegistry(); assertEquals(175,registry.all().size()); assertNotNull(registry.get("xk"));
        assertThrows(IllegalArgumentException.class,()->registry.get("zz"));
        assertEquals("hk",registry.ordered(List.of("hk","us")).getFirst().code());
        assertEquals(175,registry.ordered(List.of("hk","us")).stream().map(Storefront::code).distinct().count());
    }
    @Test void readsActualPublicPageAndNeverUsesAppIdAsProductId() throws Exception {
        var region=new AppStoreParser().parse("1546947240",new StorefrontRegistry().get("us"),"https://apps.apple.com/us/app/id1546947240",fixture("us"));
        assertEquals(Status.AVAILABLE,region.status()); assertEquals(0,region.price().amount().signum());
        assertEquals("USD",region.price().currency()); assertEquals(2,region.items().size());
        assertTrue(region.items().stream().allMatch(i->i.productId()==null && i.type().equals("UNKNOWN")));
        assertEquals("Lifetime Membership",region.items().getFirst().name());
    }
    @Test void v3FallbackAndMissingInformationStayDistinct() throws Exception {
        var doc=Jsoup.parse(fixture("us")); var script=doc.selectFirst("#serialized-server-data");
        var data=JSON.parseObject(script.data()); var root=data.getJSONArray("data").getJSONObject(0).getJSONObject("data");
        var info=root.getJSONObject("shelfMapping").getJSONObject("information").getJSONArray("items");
        for(Object value:info) ((JSONObject)value).remove("items");
        script.html(data.toJSONString()); var parser=new AppStoreParser(); var store=new StorefrontRegistry().get("us");
        assertEquals(2,parser.parse("1546947240",store,"https://apps.apple.com/us/app/id1546947240",doc.html()).items().size());
        root.getJSONObject("shelfMapping").remove("information"); script.html(data.toJSONString());
        var missing=parser.parse("1546947240",store,"https://apps.apple.com/us/app/id1546947240",doc.html());
        assertEquals("NOT_EXPOSED",missing.iapCoverage()); assertFalse(missing.issues().isEmpty());
    }
    @Test void structuredPaidPriceAndMissingPrice() throws Exception {
        var doc=Jsoup.parse(fixture("us"));
        var ld=doc.selectFirst("script[type=application/ld+json]"); var json=JSON.parseObject(ld.data());
        json.getJSONObject("offers").put("price",new BigDecimal("8.99")); ld.html(json.toJSONString());
        var parser=new AppStoreParser();var store=new StorefrontRegistry().get("us");
        assertEquals(0,new BigDecimal("8.99").compareTo(parser.parse("1546947240",store,"https://apps.apple.com/us/app/id1546947240",doc.html()).price().amount()));
        ld.remove();var script=doc.selectFirst("#serialized-server-data");var data=JSON.parseObject(script.data());
        var offer=data.getJSONArray("data").getJSONObject(0).getJSONObject("data").getJSONObject("lockup").getJSONObject("offerDisplayProperties");
        offer.put("isFree",false);offer.remove("priceFormatted");script.html(data.toJSONString());
        assertNull(parser.parse("1546947240",store,"https://apps.apple.com/us/app/id1546947240",doc.html()).price());
    }
    @ParameterizedTest
    @CsvSource({"cn,CNY,36.80", "hk,HKD,38", "tw,TWD,150", "jp,JPY,800", "kr,KRW,7700", "tr,TRY,249.99", "au,AUD,2.99", "br,BRL,12.90", "id,IDR,99000"})
    void currencyComesFromActualStorePage(String code,String currency,String expected) throws Exception {
        var r=new AppStoreParser().parse("1546947240",new StorefrontRegistry().get(code),"https://apps.apple.com/"+code+"/app/id1546947240",fixture(code));
        assertEquals(currency,r.items().getFirst().local().currency());
        assertEquals(0,new BigDecimal(expected).compareTo(r.items().getFirst().local().amount()));
        assertEquals(code,r.area()); assertEquals(2,r.items().size());
    }
    @Test void oneBrokenItemDoesNotDiscardOtherPublicPrices() throws Exception {
        var doc=Jsoup.parse(fixture("us"));var script=doc.selectFirst("#serialized-server-data");var data=JSON.parseObject(script.data());
        var sections=data.getJSONArray("data").getJSONObject(0).getJSONObject("data").getJSONObject("shelfMapping").getJSONObject("information").getJSONArray("items");
        for(Object section:sections) {
            var children=((JSONObject)section).getJSONArray("items"); if(children==null)continue;
            for(Object child:children) {
                var pairs=((JSONObject)child).getJSONArray("textPairs");if(pairs!=null)pairs.getJSONArray(0).set(1,"Unavailable");
            }
        }
        script.html(data.toJSONString());
        var r=new AppStoreParser().parse("1546947240",new StorefrontRegistry().get("us"),"https://apps.apple.com/us/app/id1546947240",doc.html());
        assertEquals(Status.AVAILABLE,r.status()); assertEquals(2,r.items().size());
        assertNull(r.items().getFirst().local().amount());assertNotNull(r.items().getLast().local().amount());assertFalse(r.issues().isEmpty());
    }
    @Test void rejectsCrossStoreAndChangedSchema() throws Exception {
        var parser=new AppStoreParser();var store=new StorefrontRegistry().get("hk");String html=fixture("us");
        assertThrows(IllegalArgumentException.class,()->parser.parse("1546947240",store,"https://apps.apple.com/us/app/id1546947240",html));
        assertThrows(IllegalArgumentException.class,()->parser.parse("1546947240",store,"https://apps.apple.com/hk/app/id1546947240","<html>maintenance</html>"));
    }
}
