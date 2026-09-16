package com.hypo.appstoreprice.global;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.hypo.appstoreprice.global.Models.*;

class FetchAndRatesTest {
    @TempDir Path temp;
    @Test void limitedExecutorBoundsAllSubmittedOperations() throws Exception {
        Settings settings=new Settings();settings.concurrency=2;settings.queueCapacity=16;
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
        try(AppleClient client=new AppleClient(settings)) {
            var jobs=new ArrayList<CompletableFuture<Integer>>();
            for(int i=0;i<10;i++) jobs.add(client.submit(()->{int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);try{Thread.sleep(20);}catch(InterruptedException ex){Thread.currentThread().interrupt();}return active.decrementAndGet();}));
            CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(5,TimeUnit.SECONDS);assertEquals(2,peak.get());
        }
    }
    @Test void sameStoreRequestIsCoalescedAndSuccessfulCacheIsReused() throws Exception {
        Settings settings=new Settings();settings.concurrency=1;
        AppleClient client=mock(AppleClient.class);CompletableFuture<Region> pending=new CompletableFuture<>();
        when(client.submit(any())).thenAnswer(invocation->pending);
        AppFetcher fetcher=new AppFetcher(client,new AppStoreParser(),settings);var area=new StorefrontRegistry().get("us");
        var first=fetcher.fetch("1",area,false);var second=fetcher.fetch("1",area,false);assertSame(first,second);
        Region value=MatchingTest.region("us",List.of());pending.complete(value);
        assertEquals(value,first.get());assertEquals(value,fetcher.fetch("1",area,true).get());verify(client,times(1)).submit(any());
    }
    @Test void failedCacheHasExplicitRetryWithoutPretendingUnavailable() throws Exception {
        Settings settings=new Settings();AppleClient client=mock(AppleClient.class);
        when(client.submit(any())).thenAnswer(i->CompletableFuture.completedFuture(Region.failure("us",Status.FETCH_FAILED,"timeout","url")));
        AppFetcher fetcher=new AppFetcher(client,new AppStoreParser(),settings);var area=new StorefrontRegistry().get("us");
        assertEquals(Status.FETCH_FAILED,fetcher.fetch("1",area,false).get().status());fetcher.fetch("1",area,false).get();verify(client,times(1)).submit(any());
        fetcher.fetch("1",area,true).get();verify(client,times(2)).submit(any());
    }
    @Test void retries429And5xxAndDoesNotRetry404() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger calls=new AtomicInteger();
        server.createContext("/",exchange->{int n=calls.incrementAndGet();int status=exchange.getRequestURI().getPath().equals("/missing")?404:n==1?429:n==2?503:200;exchange.getResponseHeaders().add("Retry-After","0");byte[] body="ok".getBytes();exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        Settings settings=new Settings();try(AppleClient client=new AppleClient(settings){@Override protected void sleep(long millis){}}){assertEquals(200,client.get("http://127.0.0.1:"+server.getAddress().getPort()+"/").status());assertEquals(3,calls.get());assertEquals(404,client.get("http://127.0.0.1:"+server.getAddress().getPort()+"/missing").status());assertEquals(4,calls.get());}finally{server.stop(0);}
        assertEquals(2000,AppleClient.retryAfter("2"));
    }
    @Test void expiredPricesReloadButUnavailableRemainsNegativeCached() throws Exception {
        Settings settings=new Settings();settings.cacheHours=0;AppleClient client=mock(AppleClient.class);
        when(client.submit(any())).thenAnswer(i->CompletableFuture.completedFuture(MatchingTest.region("us",List.of())));
        var fetcher=new AppFetcher(client,new AppStoreParser(),settings);var area=new StorefrontRegistry().get("us");
        fetcher.fetch("1",area,false).get();fetcher.fetch("1",area,false).get();verify(client,times(2)).submit(any());
        doAnswer(i->CompletableFuture.completedFuture(Region.failure("us",Status.UNAVAILABLE,"404","url"))).when(client).submit(any());
        fetcher.fetch("2",area,false).get();fetcher.fetch("2",area,true).get();verify(client,times(3)).submit(any());
    }
    @Test void fullQueueRejectsClearlyAndDoesNotRunOutsideThePool() throws Exception {
        Settings settings=new Settings();settings.concurrency=1;settings.queueCapacity=1;
        var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        try(AppleClient client=new AppleClient(settings)) {
            var first=client.submit(()->{started.countDown();try{release.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();}return 1;});
            assertTrue(started.await(2,TimeUnit.SECONDS));var second=client.submit(()->2);var third=client.submit(()->3);
            assertThrows(CompletionException.class,third::join);release.countDown();assertEquals(1,first.get());assertEquals(2,second.get());
        } finally {release.countDown();}
    }
    @Test void ratesAreCachedAndFailedRefreshRetainsLastSuccess() throws Exception {
        Settings settings=new Settings();settings.dataDir=temp.toString();settings.rateHours=0;settings.timeout=100;
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{byte[] bytes="{\"date\":\"2026-09-16\",\"usd\":{\"usd\":1,\"cny\":7,\"hkd\":8}}".getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        settings.rateUrl="http://127.0.0.1:"+server.getAddress().getPort()+"/";settings.rateFallbackUrl=settings.rateUrl;
        Rates good=new ExchangeRateService(settings).snapshot();assertFalse(good.stale());server.stop(0);
        Rates stale=new ExchangeRateService(settings).snapshot();assertTrue(stale.stale());assertEquals(good.rates(),stale.rates());
        var local=new LocalPrice(new java.math.BigDecimal("40"),"HKD","HK$40");
        assertEquals(0,new java.math.BigDecimal("35").compareTo(ExchangeRateService.convert(local,stale,"CNY")));
        assertNull(ExchangeRateService.convert(new LocalPrice(java.math.BigDecimal.ONE,"ZZZ","?"),stale,"CNY"));
    }
    @Test void forceRefreshBypassesSuccessCacheAndSharesPendingNetworkWork() throws Exception {
        Settings settings = new Settings(); AppleClient client = mock(AppleClient.class);
        CompletableFuture<Region> firstNetwork = new CompletableFuture<>(), secondNetwork = new CompletableFuture<>();
        when(client.submit(any())).thenReturn((CompletableFuture) firstNetwork, (CompletableFuture) secondNetwork);
        var fetcher = new AppFetcher(client,new AppStoreParser(),settings); var us = new StorefrontRegistry().get("us");
        Region old = MatchingTest.region("us",List.of());
        var first = fetcher.fetch("1",us,false); firstNetwork.complete(old); first.get();
        assertSame(old,fetcher.fetch("1",us,false).get());
        var force = fetcher.fetch("1",us,false,true);
        assertFalse(force.isDone());
        assertSame(force,fetcher.fetch("1",us,false,true));
        assertSame(force,fetcher.fetch("1",us,false,false));
        Region fresh = MatchingTest.region("us",List.of()); secondNetwork.complete(fresh);
        assertSame(fresh,force.get()); verify(client,times(2)).submit(any());
    }
    @Test void staleSuccessRefreshesAndFailureRetainsOriginalObservation() throws Exception {
        Settings settings = new Settings(); AppleClient client = mock(AppleClient.class);
        Region source = MatchingTest.region("us",List.of());
        Region old = new Region(source.area(),source.status(),null,source.app(),source.price(),source.items(),source.sourceUrl(),
                Instant.now().minus(Duration.ofMinutes(16)).toString(),source.language(),source.iapCoverage(),source.issues());
        Region failure = Region.failure("us",Status.RATE_LIMITED,"429",source.sourceUrl());
        when(client.submit(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(old),
                (CompletableFuture) CompletableFuture.completedFuture(failure));
        var fetcher = new AppFetcher(client,new AppStoreParser(),settings); var us = new StorefrontRegistry().get("us");
        fetcher.fetch("1",us,false).get();
        assertSame(failure,fetcher.fetch("1",us,false).get());
        assertSame(old,fetcher.lastAvailable("1","us"));
        assertFalse(AppFetcher.fresh(old)); assertTrue(AppFetcher.fresh(MatchingTest.region("us",List.of())));
        verify(client,times(2)).submit(any());
    }
    @Test void forceRefreshAlsoBypassesUnavailableCache() throws Exception {
        AppleClient client = mock(AppleClient.class);
        when(client.submit(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(Region.failure("us",Status.UNAVAILABLE,"404","url")),
                (CompletableFuture) CompletableFuture.completedFuture(MatchingTest.region("us",List.of())));
        var fetcher = new AppFetcher(client,new AppStoreParser(),new Settings()); var us = new StorefrontRegistry().get("us");
        assertEquals(Status.UNAVAILABLE,fetcher.fetch("1",us,false).get().status());
        assertEquals(Status.AVAILABLE,fetcher.fetch("1",us,false,true).get().status());
    }
}
