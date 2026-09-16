package com.hypo.appstoreprice.controller;

import com.hypo.appstoreprice.global.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import static com.hypo.appstoreprice.global.Models.*;

@RestController
@RequestMapping("/api/v2")
public class AppV2Controller {
    private final QueryService queries;
    private final StorefrontRegistry registry;
    private final ExchangeRateService exchange;
    public AppV2Controller(QueryService queries, StorefrontRegistry registry, ExchangeRateService exchange) {
        this.queries = queries; this.registry = registry; this.exchange = exchange;
    }
    @GetMapping("/storefronts") public Object storefronts() {
        return Map.of("total", registry.all().size(), "verifiedAt", registry.verifiedAt(), "storefronts", registry.all(), "mainstream", StorefrontRegistry.PRIORITY);
    }
    @GetMapping("/exchange-rates") public Rates rates() { return exchange.snapshot(); }
    @GetMapping({"/apps/{appId}", "/apps/{appId}/prices"}) public Snapshot prices(@PathVariable String appId,
            @RequestParam(defaultValue="") List<String> priority, @RequestParam(defaultValue="false") boolean retryFailed,
            @RequestParam(defaultValue="") List<String> areas, @RequestParam(defaultValue="mainstream") String scope,
            @RequestParam(defaultValue="false") boolean refresh) {
        return queries.query(appId, clean(priority), retryFailed, clean(areas), scope, refresh).snapshot();
    }
    @GetMapping("/apps/{appId}/prices/{productKey}") public Object product(@PathVariable String appId, @PathVariable String productKey,
            @RequestParam(defaultValue="") List<String> areas, @RequestParam(defaultValue="mainstream") String scope,
            @RequestParam(defaultValue="false") boolean refresh) {
        Snapshot snapshot = queries.query(appId, List.of(), false, clean(areas), scope, refresh).snapshot();
        return Map.of("progress", snapshot.progress(), "products", snapshot.products().stream().filter(p -> p.productKey().equals(productKey)).toList(),
                "queryId", snapshot.queryId(), "areas", snapshot.areas(), "regions", snapshot.regions(), "retainedRegions", snapshot.retainedRegions());
    }
    @GetMapping(value="/apps/{appId}/prices/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String appId, @RequestParam(defaultValue="") List<String> priority,
            @RequestParam(defaultValue="false") boolean retryFailed, @RequestParam(defaultValue="") List<String> areas,
            @RequestParam(defaultValue="mainstream") String scope, @RequestParam(defaultValue="false") boolean refresh,
            @RequestParam(defaultValue="") String queryId) {
        QueryService.Query query = queryId.isBlank() ? queries.query(appId, clean(priority), retryFailed, clean(areas), scope, refresh)
                : queries.resume(appId, clean(areas), scope, queryId);
        return ResponseEntity.ok().header("Cache-Control", "no-cache").header("X-Accel-Buffering", "no")
                .body(query.subscribe());
    }
    private List<String> clean(List<String> values) { return values.stream().filter(s -> !s.isBlank()).toList(); }
}
