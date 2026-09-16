package com.hypo.appstoreprice.global;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.hypo.appstoreprice.global.Models.*;

class MatchingTest {
    static LocalPrice price(String value){return new LocalPrice(new BigDecimal(value),"USD","$"+value);}
    static Item item(String name,String value){return new Item(name,null,"UNKNOWN",null,price(value),name,"English");}
    static Region region(String area,List<Item> items){return new Region(area,Status.AVAILABLE,null,new App("1","Test","","Dev",null),price("0"),items,"https://apps.apple.com/"+area+"/app/id1","2026-09-16T00:00:00Z","en","PUBLIC_PAGE_ONLY",List.of());}
    static Rates rates(){return new Rates("USD",Map.of("USD",BigDecimal.ONE,"CNY",new BigDecimal("7")),"2026-09-16","2026-09-16T00:00:00Z","fixture",false,null);}
    @Test void orderDoesNotControlIdentityAndNamesRemainInferred() throws Exception {
        var matcher=new ProductMatcher(new StorefrontRegistry());
        var result=matcher.products(List.of(region("us",List.of(item("Lifetime","5"),item("Quarterly","2"))),region("au",List.of(item("Quarterly","3"),item("Lifetime","8")))),rates());
        var lifetime=result.stream().filter(p->p.name().equals("Lifetime")).findFirst().orElseThrow();
        assertEquals("INFERRED",lifetime.matchStatus());assertEquals(2,lifetime.prices().size());
        assertEquals(new BigDecimal("8"),lifetime.prices().getLast().local().amount());
        assertEquals("au",lifetime.prices().getLast().area()); // USD must never turn Australia into US.
    }
    @Test void duplicateNamesAreNeverSortedIntoFalseIdentities() throws Exception {
        var result=new ProductMatcher(new StorefrontRegistry()).products(List.of(region("us",List.of(item("Premium","5"),item("Premium","10"))),region("hk",List.of(item("Premium","6")))),rates());
        assertEquals(3,result.stream().filter(p->p.matchStatus().equals("MATCH_UNCERTAIN")).count());
    }
    @Test void localEnglishAliasesRequireUniqueWithinStorePrices() {
        var local=region("jp",List.of(item("永久会員","800"),item("3か月会員","300")));
        var english=region("jp",List.of(item("Quarterly","300"),item("Lifetime","800")));
        assertEquals("Lifetime",ProductMatcher.withEnglishNames(local,english).items().getFirst().englishName());
        var duplicate=region("jp",List.of(item("A","800"),item("B","800")));
        assertEquals("A",ProductMatcher.withEnglishNames(duplicate,english).items().getFirst().englishName());
        assertEquals(local,ProductMatcher.withEnglishNames(local,region("us",english.items())));
    }
    @Test void exactIdsOutrankNamesAndConflictingKnownTypesStaySeparate() throws Exception {
        Item a=new Item("A","sku-1","AUTO_RENEWABLE","P1M",price("5"),null,null);
        Item b=new Item("月会員","sku-1","AUTO_RENEWABLE","P1M",price("8"),null,null);
        var matcher=new ProductMatcher(new StorefrontRegistry());
        var products=matcher.products(List.of(region("us",List.of(a)),region("jp",List.of(b))),rates());
        assertEquals(2,products.size());assertEquals("EXACT",products.getLast().matchStatus());
        Item conflict=new Item("A",null,"CONSUMABLE",null,price("6"),"A",null);
        Item subscription=new Item("A",null,"AUTO_RENEWABLE","P1M",price("5"),"A",null);
        assertEquals(2,matcher.products(List.of(region("us",List.of(subscription)),region("hk",List.of(conflict))),rates()).stream().filter(p->p.matchStatus().equals("MATCH_UNCERTAIN")).count());
    }
    @Test void anomalyRequiresEnoughPositiveComparableObservations() throws Exception {
        var areas=List.of("us","hk","jp","tr","in");var regions=new ArrayList<Region>();
        for(int n=0;n<areas.size();n++)regions.add(region(areas.get(n),List.of(item("Lifetime",n==0?"1":"10"))));
        var products=new ProductMatcher(new StorefrontRegistry()).products(regions,rates());
        assertTrue(products.getLast().prices().getFirst().anomalousLow());
        assertEquals(5,products.getLast().prices().size());
    }
}
