package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.Build;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.builds.Builds;
import com.vibeminer.network.CdnKey;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Headless opening/lifetime contracts: no live network, JavaFX toolkit or native SQLite. */
public final class BuildOpeningTest {
    private BuildOpeningTest() {}
    private static final BuildInfo.RegionEvidence US=new BuildInfo.RegionEvidence("us","b".repeat(32),true,Instant.EPOCH,Instant.EPOCH),
            EU=new BuildInfo.RegionEvidence("eu","c".repeat(32),false,Instant.EPOCH,Instant.EPOCH);
    private static BuildInfo view(List<BuildInfo.RegionEvidence> regions) {
        return new BuildInfo("wow","Retail","Catalog version","us, eu","a".repeat(32),
                BuildInfo.BuildSource.CURRENT,false,null,0,"identity",Instant.EPOCH,Instant.EPOCH,regions);
    }
    private static BuildTypes.BuildRef ref(BuildInfo.RegionEvidence region) {
        return new BuildTypes.BuildRef("wow","a".repeat(32),region.cdnConfig(),region.region());
    }
    private static BuildTypes.BuildOpenResult success(BuildTypes.BuildRef ref) {
        Build model=(Build)Proxy.newProxyInstance(Build.class.getClassLoader(),new Class<?>[]{Build.class},(p,m,a)->{
            if(m.getName().equals("build"))return ref;
            if(m.getName().equals("metadata"))return new BuildTypes.BuildMetadata("Actual metadata name",CdnKey.parse("1".repeat(32)),
                    CdnKey.parse("2".repeat(32)),CdnKey.parse("3".repeat(32)),2,4,List.of("enUS","deDE"));
            throw new AssertionError("Opening must not invoke file/resolver methods");
        });
        return new BuildTypes.BuildOpenResult(ref,BuildTypes.BuildOpenState.OPEN,model,null,null,null,Instant.EPOCH);
    }
    private static void require(boolean test,String message){if(!test)throw new AssertionError(message);}
    private static <T>T await(CompletableFuture<T> future)throws Exception{return future.get(5,TimeUnit.SECONDS);}
    private static Throwable failed(CompletableFuture<?> future)throws Exception {
        try{await(future);throw new AssertionError("Expected failed future");}
        catch(java.util.concurrent.ExecutionException failure){return failure.getCause();}
        catch(java.util.concurrent.CancellationException failure){return failure;}
    }
    private static void argument(Runnable action) {
        try{action.run();throw new AssertionError("Expected invalid argument");}
        catch(IllegalArgumentException expected){require(expected.getMessage()!=null,"Argument diagnostic");}
    }
    public static void main(String[] args)throws Exception {
        exactRegionAndMetadata();
        ambiguityAndUnknownEvidence();
        typedStatesSurvive();
        observerCancellationDoesNotReleaseAdmission();
        explicitCancelDrainsAndAllowsRetry();
        closeDrainsAndCannotBeForged();
        invalidLowerAndSynchronousFailureAllowRetry();
        callbackCanSynchronouslyClose();
        System.out.println("BuildOpening: 8 deterministic contracts passed");
    }
    private static void exactRegionAndMetadata()throws Exception {
        var caller=Thread.currentThread();var seen=new AtomicReference<BuildTypes.BuildRef>();
        var opening=new BuildOpening((build,token)->{require(Thread.currentThread()!=caller,"Lower open off caller/FX thread");seen.set(build);return CompletableFuture.completedFuture(success(build));});
        var result=await(opening.open(view(List.of(US,EU)),EU));
        require(seen.get().equals(ref(EU)),"Exact selected regional CDN identity");
        require(result.opened().metadata().buildName().equals("Actual metadata name"),"Real metadata, never catalog inference");
        require(!opening.active(),"Completed metadata retires admission");await(opening.closeAsync());
    }
    private static void ambiguityAndUnknownEvidence()throws Exception {
        var calls=new AtomicInteger();var opening=new BuildOpening((build,token)->{calls.incrementAndGet();return CompletableFuture.completedFuture(success(build));});
        var saved=new BuildInfo.RegionEvidence("us","d".repeat(32),false,Instant.EPOCH,Instant.EPOCH);
        require(await(opening.open(view(List.of(saved,US)),"US")).build().equals(ref(US)),"Unique current evidence has authority");
        var other=new BuildInfo.RegionEvidence("us","e".repeat(32),false,Instant.EPOCH,Instant.EPOCH);
        argument(()->opening.open(view(List.of(saved,other)),"us"));
        argument(()->opening.open(view(List.of(US)),"kr"));
        argument(()->opening.open(view(List.of(US)),EU));
        require(calls.get()==1,"Unknown/ambiguous evidence must never invoke opener");await(opening.closeAsync());
    }
    private static void typedStatesSurvive()throws Exception {
        for(var state:BuildTypes.BuildOpenState.values()) {
            if(state==BuildTypes.BuildOpenState.OPEN)continue;
            var expected=new BuildTypes.BuildOpenResult(ref(US),state,null,BuildTypes.BuildPart.ROOT,"EXACT_CODE","Exact message",Instant.EPOCH);
            var opening=new BuildOpening((build,token)->CompletableFuture.completedFuture(expected));
            require(await(opening.open(view(List.of(US)),US))==expected,"Preserve state/part/code/message/time: "+state);await(opening.closeAsync());
        }
    }
    private static void observerCancellationDoesNotReleaseAdmission()throws Exception {
        var entered=new CountDownLatch(1);var lower=new CompletableFuture<BuildTypes.BuildOpenResult>();var seen=new AtomicReference<Cancellation>();
        var opening=new BuildOpening((build,token)->{seen.set(token);entered.countDown();return lower;});
        var observer=opening.open(view(List.of(US)),US);require(entered.await(5,TimeUnit.SECONDS),"Lower entered");observer.cancel(false);
        require(opening.active() && !seen.get().isCanceled(),"Observer cancellation is not operation cancellation");
        require(failed(opening.open(view(List.of(US)),US)).getMessage().equals("BUILD_OPEN_ACTIVE"),"Duplicate admission fails fast");
        var close=opening.closeAsync();require(!close.isDone(),"Close awaits real lower completion");lower.complete(success(ref(US)));await(close);
    }
    private static void explicitCancelDrainsAndAllowsRetry()throws Exception {
        var entered=new CountDownLatch(1);var lower=new CompletableFuture<BuildTypes.BuildOpenResult>();var seen=new AtomicReference<Cancellation>();var calls=new AtomicInteger();
        var opening=new BuildOpening((build,token)->{if(calls.incrementAndGet()>1)return CompletableFuture.completedFuture(success(build));seen.set(token);entered.countDown();return lower;});
        var pending=opening.open(view(List.of(US)),US);require(entered.await(5,TimeUnit.SECONDS),"Lower entered");opening.cancel();
        require(seen.get().isCanceled() && opening.active(),"Cancellation propagates but retains admission until lower retires");
        lower.complete(success(ref(US)));require(failed(pending) instanceof java.util.concurrent.CancellationException,"Late success cannot override explicit cancellation");
        require(await(opening.open(view(List.of(US)),US)).state()==BuildTypes.BuildOpenState.OPEN,"Retry after cancellation");await(opening.closeAsync());
    }
    private static void closeDrainsAndCannotBeForged()throws Exception {
        var entered=new CountDownLatch(1);var lower=new CompletableFuture<BuildTypes.BuildOpenResult>();
        var opening=new BuildOpening((build,token)->{entered.countDown();return lower;});var pending=opening.open(view(List.of(US)),US);
        require(entered.await(5,TimeUnit.SECONDS),"Lower entered");var close=opening.closeAsync();require(!close.isDone(),"Async close pending");close.complete(null);
        var real=opening.closeAsync();require(!real.isDone(),"Observer cannot forge private drain");
        lower.complete(success(ref(US)));await(real);require(failed(pending) instanceof java.util.concurrent.CancellationException,"Close cancels opening");
        require(failed(opening.open(view(List.of(US)),US)) instanceof IllegalStateException,"No admission after close");
    }
    private static void invalidLowerAndSynchronousFailureAllowRetry()throws Exception {
        var calls=new AtomicInteger();Builds lower=(build,token)->{
            int call=calls.incrementAndGet();if(call==1)throw new IllegalStateException("sync lower failure");
            if(call==2)return CompletableFuture.completedFuture(new BuildTypes.BuildOpenResult(ref(EU),BuildTypes.BuildOpenState.MISSING,null,null,null,null,null));
            if(call==3)return CompletableFuture.completedFuture(new BuildTypes.BuildOpenResult(build,BuildTypes.BuildOpenState.OPEN,null,null,null,null,null));
            return CompletableFuture.completedFuture(success(build));};
        var opening=new BuildOpening(lower);
        require(failed(opening.open(view(List.of(US)),US)).getMessage().equals("sync lower failure"),"Synchronous lower failure preserved");
        require(failed(opening.open(view(List.of(US)),US)).getMessage().contains("BUILD_OPEN_RESULT_INVALID"),"Reject wrong returned identity");
        require(failed(opening.open(view(List.of(US)),US)).getMessage().contains("BUILD_OPEN_RESULT_INVALID"),"Reject OPEN without metadata");
        require(await(opening.open(view(List.of(US)),US)).state()==BuildTypes.BuildOpenState.OPEN,"Errors retire admission");await(opening.closeAsync());
    }
    private static void callbackCanSynchronouslyClose()throws Exception {
        var opening=new BuildOpening((build,token)->CompletableFuture.completedFuture(success(build)));
        var result=opening.open(view(List.of(US)),US).thenApply(value->{opening.closeAsync().join();return value;});
        require(await(result).state()==BuildTypes.BuildOpenState.OPEN,"Callback can close without retaining operation slot");
    }
}
