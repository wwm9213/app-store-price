package com.hypo.appstoreprice.global;

import com.hypo.appstoreprice.controller.*;
import com.hypo.appstoreprice.handler.*;
import com.hypo.appstoreprice.service.AppService;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static com.hypo.appstoreprice.global.Models.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QueryAndCompatibilityTest {
    StorefrontRegistry registry;
    AppFetcher fetcher;
    ExchangeRateService exchange;
    QueryService service;
    Map<String,CompletableFuture<Region>> pending;
    MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        var actual=new StorefrontRegistry(); registry=spy(actual);
        var subset=List.of(actual.get("us"),actual.get("cn"),actual.get("au"));
        doReturn(subset).when(registry).all(); doReturn(subset).when(registry).selected(anyList(),anyString(),anyList());
        fetcher=mock(AppFetcher.class); exchange=mock(ExchangeRateService.class);
        when(exchange.snapshot()).thenReturn(new Rates("USD",Map.of("USD",BigDecimal.ONE,"CNY",BigDecimal.valueOf(7)),"2026-09-16","2026-09-16T00:00:00Z","test",false,null));
        pending=new ConcurrentHashMap<>();
        when(fetcher.fetch(anyString(),any(),anyBoolean())).thenAnswer(i->pending.computeIfAbsent(((Storefront)i.getArgument(1)).code(),k->new CompletableFuture<>()));
        service=new QueryService(registry,fetcher,exchange,new ProductMatcher(registry),new Settings());
        mvc=MockMvcBuilders.standaloneSetup(new AppV2Controller(service,registry,exchange))
                .setControllerAdvice(new CommonResultHandler(),new CommonExceptionHandler()).build();
    }
    @AfterEach void close() { service.close(); }
    void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime()<until) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(),"query did not reach expected state");
    }
    @Test void sseReconnectReturnsCurrentSnapshotWithoutRestartAndCompletesUnwrapped() throws Exception {
        var first=mvc.perform(get("/api/v2/apps/1/prices/stream")).andExpect(request().asyncStarted()).andReturn();
        waitFor(()->pending.size()==3);
        pending.get("us").complete(MatchingTest.region("us",List.of()));
        var query=service.query("1",List.of(),false);
        waitFor(()->query.snapshot().progress().completed()==1);
        var second=mvc.perform(get("/api/v2/apps/1/prices/stream")).andExpect(request().asyncStarted()).andReturn();
        assertTrue(second.getResponse().getContentAsString().contains("event:snapshot"));
        assertTrue(second.getResponse().getContentAsString().contains("\"completed\":1"));
        query.ping();
        assertTrue(second.getResponse().getContentAsString().contains("event:heartbeat"));
        pending.get("cn").complete(MatchingTest.region("cn",List.of()));
        pending.get("au").complete(MatchingTest.region("au",List.of()));
        waitFor(()->query.snapshot().progress().complete());
        first.getAsyncResult(5000); second.getAsyncResult(5000);
        String body=mvc.perform(asyncDispatch(second)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("event:complete")); assertFalse(body.contains("\"code\":0"));
        verify(fetcher,times(3)).fetch(eq("1"),any(),eq(false));
        assertSame(query,service.query("1",List.of(),false));
    }
    @Test void onlyFailedRegionsAreRetriedAndUnexpectedFailureCompletes() throws Exception {
        var first=service.query("1",List.of(),false);waitFor(()->pending.size()==3);
        pending.get("us").complete(MatchingTest.region("us",List.of()));
        pending.get("cn").complete(Region.failure("cn",Status.UNAVAILABLE,"404","url"));
        pending.get("au").completeExceptionally(new IllegalStateException("network"));
        waitFor(()->first.snapshot().progress().complete());assertEquals(1,first.snapshot().progress().failed());
        pending.put("au",CompletableFuture.completedFuture(MatchingTest.region("au",List.of())));
        QueryService.Query retry=null;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(retry==null && System.nanoTime()<deadline){ var next=service.query("1",List.of(),true);if(next!=first)retry=next;else Thread.sleep(5); }
        assertNotNull(retry); final var result=retry;waitFor(()->result.snapshot().progress().complete());
        assertEquals(0,result.snapshot().progress().failed()); assertEquals(1,result.snapshot().progress().unavailable());
        verify(fetcher,times(1)).fetch(eq("1"),argThat(a->a.code().equals("au")),eq(true));
        verify(fetcher,never()).fetch(eq("1"),argThat(a->!a.code().equals("au")),eq(true));
    }
    @Test void invalidV2InputIs400WithJsonEnvelope() throws Exception {
        mvc.perform(get("/api/v2/apps/not-an-id/prices")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1));
    }
    @Test void selectedRegionsDefineQueryIdentityAndProgress() throws Exception {
        doCallRealMethod().when(registry).selected(anyList(),anyString(),anyList());
        var first=service.query("1",List.of("us"),false,List.of("us","cn"),"custom");
        assertSame(first,service.query("1",List.of("cn"),false,List.of("cn","US","cn"),"custom"));
        var other=service.query("1",List.of(),false,List.of("hk"),"custom");
        assertNotSame(first,other);
        assertEquals(2,first.snapshot().progress().total());
        assertEquals(List.of("us","cn"),first.snapshot().areas());
        waitFor(()->pending.size()==3);
        pending.forEach((area,future)->future.complete(MatchingTest.region(area,List.of())));
        waitFor(()->first.snapshot().progress().complete() && other.snapshot().progress().complete());
        assertEquals(Set.of("us","cn"),first.snapshot().regions().stream().map(Region::area).collect(java.util.stream.Collectors.toSet()));
        verify(fetcher,times(3)).fetch(eq("1"),any(),eq(false));
    }
    @Test void defaultHttpQueryUsesMainstreamAndInvalidSelectionIsRejected() throws Exception {
        doCallRealMethod().when(registry).selected(anyList(),anyString(),anyList());
        mvc.perform(get("/api/v2/apps/1/prices"))
                .andExpect(jsonPath("$.data.progress.total").value(14)).andExpect(jsonPath("$.data.areas.length()").value(14));
        waitFor(()->pending.size()==14);
        pending.forEach((area,future)->future.complete(MatchingTest.region(area,List.of())));
        waitFor(()->service.query("1",List.of(),false).snapshot().progress().complete());
        mvc.perform(get("/api/v2/apps/1/prices").param("scope","custom"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/apps/1/prices").param("scope","custom").param("areas","zz"))
                .andExpect(status().isBadRequest());
    }
    @Test void legacyFiveEndpointsKeepEnvelopeAndThirteenStorefronts() throws Exception {
        var actual=new StorefrontRegistry();
        doAnswer(i->CompletableFuture.completedFuture(MatchingTest.region(((Storefront)i.getArgument(1)).code(),List.of()))).when(fetcher).fetch(anyString(),any(),anyBoolean());
        AppleClient apple=mock(AppleClient.class);
        when(apple.submit(any())).thenAnswer(i->CompletableFuture.completedFuture(List.of()));
        var legacy=new AppService(actual,fetcher,apple,exchange,new ProductMatcher(actual));
        var oldMvc=MockMvcBuilders.standaloneSetup(new AppController(legacy)).setControllerAdvice(new CommonResultHandler(),new CommonExceptionHandler()).build();
        oldMvc.perform(post("/app/getAreaList")).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.length()").value(13));
        oldMvc.perform(post("/app/getPopularSearchWordList")).andExpect(jsonPath("$.data").isArray());
        oldMvc.perform(post("/app/getAppList").contentType("application/json").content("{\"appName\":\"Clock\",\"areaCode\":\"us\"}")).andExpect(jsonPath("$.data").isArray());
        oldMvc.perform(post("/app/getAppInfo").contentType("application/json").content("{\"appId\":\"1\"}")).andExpect(jsonPath("$.data.length()").value(13)).andExpect(jsonPath("$.data[0].area").value("us")).andExpect(jsonPath("$.data[0].price.currencyCode").value("USD"));
        oldMvc.perform(post("/app/getAppInfoComparison").contentType("application/json").content("{\"appId\":\"1\"}")).andExpect(jsonPath("$.data[0].priceList.length()").value(13));
    }
}
