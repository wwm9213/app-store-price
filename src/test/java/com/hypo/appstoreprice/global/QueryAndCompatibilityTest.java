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
        when(fetcher.fetch(anyString(),any(),anyBoolean(),anyBoolean())).thenAnswer(i->pending.computeIfAbsent(((Storefront)i.getArgument(1)).code(),k->new CompletableFuture<>()));
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
        verify(fetcher,times(3)).fetch(eq("1"),any(),eq(false),eq(false));
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
        verify(fetcher,times(1)).fetch(eq("1"),argThat(a->a.code().equals("au")),eq(true),eq(false));
        verify(fetcher,never()).fetch(eq("1"),argThat(a->!a.code().equals("au")),eq(true),eq(false));
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
        verify(fetcher,times(3)).fetch(eq("1"),any(),eq(false),eq(false));
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
    @Test void forceRefreshRetainsPricesButCountsOnlyNewResultsAndResumesByQueryId() throws Exception {
        var first=service.query("1",List.of(),false); waitFor(()->pending.size()==3);
        pending.forEach((area,future)->future.complete(MatchingTest.region(area,List.of())));
        waitFor(()->first.snapshot().progress().complete());
        // Wait until orchestration has published the completed snapshot to the shared cache.
        waitFor(()->service.query("1",List.of(),false)==first);
        pending.clear();
        var refreshed=service.query("1",List.of(),false,List.of(),"mainstream",true);
        waitFor(()->pending.size()==3);
        assertNotSame(first,refreshed); assertSame(refreshed,service.query("1",List.of(),false,List.of(),"mainstream",true));
        assertEquals(0,refreshed.snapshot().progress().completed()); assertEquals(3,refreshed.snapshot().retainedRegions().size());
        String queryId=refreshed.snapshot().queryId();
        assertSame(refreshed,service.resume("1",List.of(),"mainstream",queryId));
        assertThrows(IllegalArgumentException.class,()->service.resume("2",List.of(),"mainstream",queryId));
        pending.get("us").complete(Region.failure("us",Status.FETCH_FAILED,"offline","url"));
        pending.get("cn").complete(MatchingTest.region("cn",List.of()));
        pending.get("au").complete(Region.failure("au",Status.UNAVAILABLE,"404","url"));
        waitFor(()->refreshed.snapshot().progress().complete());
        var snapshot=refreshed.snapshot();
        assertEquals(1,snapshot.progress().failed()); assertEquals(1,snapshot.progress().available());
        assertEquals(List.of("us"),snapshot.retainedRegions().stream().map(Region::area).toList());
        assertEquals(first.snapshot().regions().stream().filter(r->r.area().equals("us")).findFirst().orElseThrow().fetchedAt(),snapshot.retainedRegions().getFirst().fetchedAt());
        assertEquals(2,snapshot.products().getFirst().prices().size());
        mvc.perform(get("/api/v2/apps/1/prices/app"))
                .andExpect(jsonPath("$.data.queryId").value(queryId))
                .andExpect(jsonPath("$.data.retainedRegions[0].area").value("us"))
                .andExpect(jsonPath("$.data.progress.failed").value(1));
        var stream=mvc.perform(get("/api/v2/apps/1/prices/stream").param("queryId",queryId).param("refresh","true"))
                .andExpect(request().asyncStarted()).andReturn();
        stream.getAsyncResult(5000);
        String body=mvc.perform(asyncDispatch(stream)).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("event:complete"));
        verify(fetcher,times(3)).fetch(eq("1"),any(),eq(false),eq(true));
    }
    @Test void httpRefreshBypassesCompletedQueryAndOnlyUsesRequestedAreas() throws Exception {
        doCallRealMethod().when(registry).selected(anyList(),anyString(),anyList());
        var initial=service.query("1",List.of(),false,List.of("us"),"custom"); waitFor(()->pending.size()==1);
        pending.get("us").complete(MatchingTest.region("us",List.of())); waitFor(()->initial.snapshot().progress().complete());
        pending.clear();
        mvc.perform(get("/api/v2/apps/1/prices").param("scope","custom").param("areas","us").param("refresh","true"))
                .andExpect(jsonPath("$.data.queryId").isString()).andExpect(jsonPath("$.data.progress.total").value(1));
        waitFor(()->pending.size()==1); pending.get("us").complete(MatchingTest.region("us",List.of()));
        verify(fetcher,times(1)).fetch(eq("1"),argThat(a->a.code().equals("us")),eq(false),eq(true));
    }
    @Test void ordinaryQueryRefreshesCompletedSnapshotOlderThanFifteenMinutes() throws Exception {
        var first=service.query("1",List.of(),false); waitFor(()->pending.size()==3);
        pending.forEach((area,future)->{
            Region r=MatchingTest.region(area,List.of());
            future.complete(new Region(r.area(),r.status(),r.message(),r.app(),r.price(),r.items(),r.sourceUrl(),
                    java.time.Instant.now().minusSeconds(16*60).toString(),r.language(),r.iapCoverage(),r.issues()));
        });
        waitFor(()->first.snapshot().progress().complete()); pending.clear();
        waitFor(()->service.query("1",List.of(),false)!=first);
        var refreshed=service.query("1",List.of(),false); waitFor(()->pending.size()==3);
        assertEquals(3,refreshed.snapshot().retainedRegions().size());
        assertEquals(0,refreshed.snapshot().progress().completed());
        pending.forEach((area,future)->future.complete(MatchingTest.region(area,List.of())));
        waitFor(()->refreshed.snapshot().progress().complete());
        assertTrue(refreshed.snapshot().retainedRegions().isEmpty());
        verify(fetcher,times(6)).fetch(eq("1"),any(),eq(false),eq(false));
    }
    @Test void forceDuringOrdinaryQueryDoesNotInheritAlreadyCachedResults() throws Exception {
        var ordinary=service.query("1",List.of(),false); waitFor(()->pending.size()==3);
        pending.get("us").complete(MatchingTest.region("us",List.of()));
        waitFor(()->ordinary.snapshot().progress().completed()==1);
        var refreshed=service.query("1",List.of(),false,List.of(),"mainstream",true);
        assertNotSame(ordinary,refreshed);
        waitFor(()->mockingDetails(fetcher).getInvocations().stream()
                .filter(i->i.getMethod().getName().equals("fetch") && Boolean.TRUE.equals(i.getArguments()[3])).count()==3);
        pending.get("cn").complete(MatchingTest.region("cn",List.of()));
        pending.get("au").complete(MatchingTest.region("au",List.of()));
        waitFor(()->ordinary.snapshot().progress().complete() && refreshed.snapshot().progress().complete());
        verify(fetcher,times(1)).fetch(eq("1"),argThat(a->a.code().equals("us")),eq(false),eq(true));
    }
    @Test void confirmedUnavailablePriceIsNotRevivedInTheNextRefresh() throws Exception {
        var first=service.query("1",List.of(),false); waitFor(()->pending.size()==3);
        pending.forEach((area,future)->future.complete(MatchingTest.region(area,List.of())));
        waitFor(()->first.snapshot().progress().complete()); pending.clear();
        var unavailable=service.query("1",List.of(),false,List.of(),"mainstream",true); waitFor(()->pending.size()==3);
        pending.forEach((area,future)->future.complete(Region.failure(area,Status.UNAVAILABLE,"404","url")));
        waitFor(()->unavailable.snapshot().progress().complete()); pending.clear();
        waitFor(()->service.query("1",List.of(),false,List.of(),"mainstream",true)!=unavailable);
        var refreshed=service.query("1",List.of(),false,List.of(),"mainstream",true); waitFor(()->pending.size()==3);
        assertTrue(refreshed.snapshot().retainedRegions().isEmpty());
        assertTrue(refreshed.snapshot().products().isEmpty());
        pending.forEach((area,future)->future.complete(MatchingTest.region(area,List.of())));
        waitFor(()->refreshed.snapshot().progress().complete());
    }
}
