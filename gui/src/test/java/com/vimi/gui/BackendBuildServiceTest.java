package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes;
import com.vibeminer.builds.BuildCatalog;
import com.vibeminer.builds.CatalogTypes;
import com.vibeminer.builds.Products;
import com.vibeminer.store.History;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic dependency-free adapter contracts; no network, native SQLite or JavaFX toolkit. */
public final class BackendBuildServiceTest {
    private BackendBuildServiceTest() {}
    private static final Instant FIRST = Instant.parse("2025-01-01T00:00:00Z"),
            LAST = Instant.parse("2025-02-01T00:00:00Z");
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }
    private static CatalogTypes.CatalogBuild build(String product,boolean current) {
        return new CatalogTypes.CatalogBuild(product,"exact-"+product,"11.0.7.12345","a".repeat(32),
                "p".repeat(32),Map.of("BuildId","12345"),FIRST,LAST,current,List.of(
                    new CatalogTypes.BuildRegion("us","b".repeat(32),FIRST,LAST,current,null),
                    new CatalogTypes.BuildRegion("eu","c".repeat(32),FIRST,LAST,false,null)));
    }
    private static CatalogTypes.BuildList list(String product) {
        return new CatalogTypes.BuildList(product, product.equals("wow") || product.equals("wow_future")
                ? List.of(build(product,product.equals("wow_future"))) : List.of(),
                product.equals("wow") ? List.of(new CatalogTypes.CatalogProblem("BROKEN_RECORD","Unreadable row",null,3)) : List.of(),LAST);
    }
    private static SourceTypes.ObservationResult observed(boolean success,String code) {
        return new SourceTypes.ObservationResult(success ? SourceTypes.ObjectState.PRESENT : SourceTypes.ObjectState.UNAVAILABLE,
                null,success,code,success?null:"CDN unavailable",LAST);
    }
    private static final class Fake implements BuildCatalog {
        final Thread caller = Thread.currentThread();
        final AtomicInteger lists=new AtomicInteger(),refreshes=new AtomicInteger(),summaries=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1);
        volatile CompletableFuture<Void> listGate=CompletableFuture.completedFuture(null),
                summaryGate=CompletableFuture.completedFuture(null);
        volatile boolean failRefresh,failSummary,failLists;
        volatile CatalogTypes.SummaryRefresh summaryOverride;
        void worker(){require(Thread.currentThread()!=caller,"Lower work must not run on caller/FX thread");}
        public CompletableFuture<CatalogTypes.BuildList> listBuilds(String product,Cancellation token){
            worker(); lists.incrementAndGet();entered.countDown();
            if(failLists) return CompletableFuture.failedFuture(new IllegalStateException("catalog read failed"));
            return listGate.thenApply(ignored->list(product));
        }
        public CompletableFuture<CatalogTypes.CatalogRefresh> refresh(String product,Cancellation token){
            worker();refreshes.incrementAndGet();
            if(failRefresh && product.equals("wow"))return CompletableFuture.failedFuture(new IllegalStateException("product refresh failed"));
            return CompletableFuture.completedFuture(new CatalogTypes.CatalogRefresh(product,
                    observed(true,null),observed(false,"CDNS_DOWN"),list(product)));
        }
        public CompletableFuture<CatalogTypes.SummaryRefresh> refreshSummary(Cancellation token){
            worker();summaries.incrementAndGet();entered.countDown();
            if(failSummary)return CompletableFuture.failedFuture(new IllegalStateException("summary refresh failed"));
            if(summaryOverride!=null)return CompletableFuture.completedFuture(summaryOverride);
            return summaryGate.thenApply(ignored->new CatalogTypes.SummaryRefresh(observed(true,null),
                    List.of("wow_future"),List.of("wow_future"),List.of(new CatalogTypes.CatalogProblem("SUMMARY_BAD","Bad summary row")),"seq",List.of("wow_no_versions")));
        }
    }

    public static void main(String[] args) throws Exception {
        offlineProjectionAndWarnings();
        refreshDiscoveryAndPartialFailure();
        coalescedListAndQueuedRefresh();
        coalescedRefreshAndProtectedClose();
        cancelRefreshKeepsServiceUsable();
        discoveryFailureStillListsReviewedProducts();
        boundedPagedHistoryDiscovery();
        callbackCanSynchronouslyAwaitClose();
        failedProductRetainsLastGoodRows();
        totalInitialFailureRejectsSnapshot();
        readableSummaryRefreshesOnlyVersionedWowProducts();
        System.out.println("BackendBuildService: 11 deterministic contracts passed");
    }
    private static void offlineProjectionAndWarnings() throws Exception {
        var fake=new Fake();var clean=new AtomicInteger();
        var service=new BackendBuildService(fake,token->CompletableFuture.completedFuture(List.of("wow_future","other")),
                ()->{clean.incrementAndGet();return CompletableFuture.completedFuture(null);});
        var values=await(service.listBuilds());
        require(values.size()==2,"Offline discovery must include valid future WoW products");
        require(fake.summaries.get()==0 && fake.refreshes.get()==0,"Listing cannot refresh/network");
        var old=values.stream().filter(b->b.product().equals("wow")).findFirst().orElseThrow();
        require(old.identity().equals("exact-wow") && old.config().equals("a".repeat(32)),"Full catalog identity/config must survive");
        require(old.source()==BuildInfo.BuildSource.SAVED && !old.prepared(),"Catalog evidence is not preparation");
        require(old.totalGiB()==null && old.storedGiB()==0,"No size inference");
        require(old.firstSeen().equals(FIRST) && old.lastSeen().equals(LAST),"Build time evidence");
        require(old.observations().size()==2 && old.observations().get(1).cdnConfig().equals("c".repeat(32)),"Distinct CDN region provenance");
        require(service.problems().stream().anyMatch(p->p.contains("BROKEN_RECORD") && p.contains("row 3")),"All catalog diagnostics survive");
        require(!service.mockMode() && service.status().contains("No network"),"Real offline mode");
        try {await(service.openBuild(old));throw new AssertionError("Opening must remain disconnected");}
        catch(java.util.concurrent.ExecutionException error){require(error.getCause() instanceof UnsupportedOperationException,"Typed disconnected open");}
        await(service.closeAsync());await(service.closeAsync());require(clean.get()==1,"Cleanup exactly once");
    }
    private static void refreshDiscoveryAndPartialFailure() throws Exception {
        var fake=new Fake();fake.failRefresh=true;var service=new BackendBuildService(fake);
        var values=await(service.refreshBuilds());
        require(values.size()==2,"Failed refresh must retain saved rows and include discovered product");
        require(fake.refreshes.get()==Products.IDS.size()+1 && fake.summaries.get()==1,"Refresh summary plus reviewed/discovered products");
        require(service.problems().stream().anyMatch(p->p.contains("product refresh failed")),"Thrown refresh warning");
        require(service.problems().stream().anyMatch(p->p.contains("CDNS_DOWN")),"Observation warning");
        require(service.problems().stream().anyMatch(p->p.contains("SUMMARY_BAD")),"Summary parse warning");
        require(service.problems().stream().noneMatch(p->p.contains("wow_no_versions")),"No-version products are legitimate, not warnings");
        require(service.status().contains("issues") && !service.status().contains("CDNS_DOWN"),"Concise status plus full details");
        await(service.closeAsync());
    }
    private static void coalescedListAndQueuedRefresh() throws Exception {
        var fake=new Fake();fake.listGate=new CompletableFuture<>();var service=new BackendBuildService(fake);
        var first=service.listBuilds();require(fake.entered.await(5,TimeUnit.SECONDS),"List entered");
        var second=service.listBuilds();first.cancel(false);
        var refresh=service.refreshBuilds();var refreshAgain=service.refreshBuilds();
        require(fake.summaries.get()==0,"Refresh must queue after listing");
        var continuation=second.thenApply(ignored->refresh.join());
        fake.listGate.complete(null);
        require(await(continuation).size()==2 && await(refreshAgain).size()==2,"Queued refresh runs before blocked observers can deadlock worker");
        require(fake.summaries.get()==1 && fake.lists.get()==Products.IDS.size(),"Singleflight list and refresh");
        await(service.closeAsync());
    }
    private static void coalescedRefreshAndProtectedClose() throws Exception {
        var fake=new Fake();fake.summaryGate=new CompletableFuture<>();var cleaned=new AtomicInteger();
        var service=new BackendBuildService(fake,token->CompletableFuture.completedFuture(List.of()),
                ()->{cleaned.incrementAndGet();return CompletableFuture.completedFuture(null);});
        var first=service.refreshBuilds();require(fake.entered.await(5,TimeUnit.SECONDS),"Refresh entered");
        var second=service.refreshBuilds();first.cancel(false);
        var closing=service.closeAsync();require(!closing.isDone(),"Close must await accepted lower work");closing.complete(null);
        var realClose=service.closeAsync();require(!realClose.isDone(),"Observer cannot forge private drain");
        fake.summaryGate.complete(null);await(realClose);
        try { await(second); throw new AssertionError("Closed refresh must cancel"); }
        catch (java.util.concurrent.CancellationException | java.util.concurrent.ExecutionException expected) {
            require(cleaned.get()==1,"Cancellation stops refresh before products and cleans");
        }
        require(fake.refreshes.get()==0 && fake.summaries.get()==1,"No overlapping refresh work");
        require(service.listBuilds().isCompletedExceptionally(),"Reject after close");
    }
    private static void cancelRefreshKeepsServiceUsable() throws Exception {
        var fake = new Fake(); fake.summaryGate = new CompletableFuture<>();
        var service = new BackendBuildService(fake);
        var refresh = service.refreshBuilds();
        require(fake.entered.await(5, TimeUnit.SECONDS), "Refresh entered before explicit cancellation");
        service.cancelCatalogRefresh();
        fake.summaryGate.complete(null);
        try { await(refresh); throw new AssertionError("Canceled catalog refresh completed normally"); }
        catch (java.util.concurrent.CancellationException | java.util.concurrent.ExecutionException expected) { /* expected */ }
        require(await(service.listBuilds()).stream().anyMatch(build -> build.product().equals("wow")), "Cancel refresh preserves local catalog service");
        require(await(service.refreshBuilds()).size() == 2, "A later refresh receives a fresh cancellation token");
        await(service.closeAsync());
    }

    private static void discoveryFailureStillListsReviewedProducts() throws Exception {
        var fake=new Fake();fake.failSummary=true;
        var service=new BackendBuildService(fake,token->CompletableFuture.failedFuture(new IllegalStateException("history failure")),
                ()->CompletableFuture.completedFuture(null));
        require(await(service.refreshBuilds()).size()==1,"Known products remain available despite discovery failures");
        require(service.problems().stream().anyMatch(p->p.contains("history failure")) && service.problems().stream().anyMatch(p->p.contains("summary refresh failed")),"Discovery/summary diagnostics both preserved");
        await(service.closeAsync());
    }
    private static void boundedPagedHistoryDiscovery() {
        var calls=new AtomicInteger();var first=new ArrayList<History.Entry>();
        for(int i=0;i<1000;i++)first.add(new History.Entry(1,"wow_future"+(i%100),"us",FIRST.plusSeconds(i),null,null,null));
        ObjectSource source=(ObjectSource)Proxy.newProxyInstance(ObjectSource.class.getClassLoader(),new Class<?>[]{ObjectSource.class},
                (proxy,method,args)->{
                    require(method.getName().equals("readHistory"),"Discovery must only read local history");
                    var query=(History.Query)args[0];require(query.kind()==1 && query.limit()==1000,"Versions-only paged history");
                    if(calls.getAndIncrement()==0){require(query.after()==null,"Initial cursor");return CompletableFuture.completedFuture(first);}
                    require(query.after().equals(first.getLast()),"Continuation must be last history record");
                    return CompletableFuture.completedFuture(List.of());
                });
        var found=BuildApplication.discoverProducts(source,Cancellation.NONE).join();
        require(found.size()==64 && found.getFirst().equals("wow_future0") && found.getLast().equals("wow_future63"),"Bounded natural-order discovery");
        require(calls.get()==2,"Discovery walks next page offline");
    }
    private static void callbackCanSynchronouslyAwaitClose() throws Exception {
        var fake=new Fake();var service=new BackendBuildService(fake);
        var callback=service.listBuilds().thenApply(ignored->{service.closeAsync().join();return true;});
        require(await(callback),"Completion callback must be free to await close");
    }
    private static void failedProductRetainsLastGoodRows() throws Exception {
        var fake=new Fake();var service=new BackendBuildService(fake);
        var before=await(service.listBuilds());fake.failRefresh=true;fake.failLists=true;
        var after=await(service.refreshBuilds());
        var retained=after.stream().filter(b->b.product().equals("wow")).findFirst().orElseThrow();
        require(retained.equals(before.getFirst()),"Failed re-read must retain identical previous evidence");
        require(service.problems().stream().anyMatch(p->p.contains("wow: showing previous catalog snapshot")),"Previous snapshot warning");
        await(service.closeAsync());
    }
    private static void totalInitialFailureRejectsSnapshot() throws Exception {
        var fake=new Fake();fake.failLists=true;var service=new BackendBuildService(fake);
        try{await(service.listBuilds());throw new AssertionError("All product read failures must reject a new snapshot");}
        catch(java.util.concurrent.ExecutionException expected){require(service.problems().size()==Products.IDS.size(),"Preserve all failed-product diagnostics");}
        await(service.closeAsync());
    }
    private static void readableSummaryRefreshesOnlyVersionedWowProducts() throws Exception {
        var fake=new Fake();
        var observation=new SourceTypes.Observation(SourceTypes.ObservationKind.SUMMARY,"","us",java.net.URI.create("https://example.invalid/summary"),LAST,new byte[]{1});
        fake.summaryOverride=new CatalogTypes.SummaryRefresh(new SourceTypes.ObservationResult(
                SourceTypes.ObjectState.PRESENT,observation,true,null,null,LAST),List.of("wow_future"),
                List.of("wow","wow_future","d3"),List.of(),"seq",List.of("wow","d3"));
        var service=new BackendBuildService(fake);
        var rows=await(service.refreshBuilds());
        require(rows.size()==2,"No-version WoW history remains visible offline");
        require(fake.refreshes.get()==1,"Only valid summary's versioned WoW product is refreshed");
        require(fake.lists.get()==Products.IDS.size(),"Other products use local catalog only");
        require(service.problems().stream().noneMatch(p->p.contains("d3") || p.contains("no versions")),"Flag-only products do not create spurious diagnostics");
        await(service.closeAsync());
    }

}
