package com.vimi.gui;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Authored provider/cancellation lifetimes; no JavaFX, native renderer, assets or network. */
public final class LegacyWorldActivityTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void await(java.util.function.BooleanSupplier ready)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!ready.getAsBoolean()&&System.nanoTime()<until){Thread.sleep(2);}
        require(ready.getAsBoolean(),"Timed out waiting for actual activity");
    }
    private static void fails(CompletableFuture<?> future)throws Exception{
        try{future.get(3,TimeUnit.SECONDS);throw new AssertionError("Expected failed view/barrier");}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.CancellationException expected){ }
    }

    public static void main(String[] args)throws Exception{
        canceledViewIsNotProviderCompletion();
        heldSupplierAndQueuedSkipHaveActualFinally();
        cancellationIsOffCallerAndItsFinallyIsPartOfDrain();
        fourTokenCapacityAndStickyCancellationFailure();
        System.out.println("LegacyWorldActivityTest PASS");
    }

    private static void canceledViewIsNotProviderCompletion()throws Exception{
        var activity=new LegacyWorldActivity("authored-legacy-request",false);
        var original=new CompletableFuture<String>();var called=new CountDownLatch(1);var thread=new AtomicReference<Thread>();
        try{
            var view=activity.invoke(()->{thread.set(Thread.currentThread());called.countDown();return original;});
            require(called.await(2,TimeUnit.SECONDS),"Supplier invoked");
            require(thread.get()!=Thread.currentThread(),"Supplier stays off caller");
            view.cancel(false);
            var drain=activity.pauseAndDrain();
            require(!original.isDone(),"Canceling returned copy cannot complete actual provider");
            require(activity.hasActiveWork()&&!drain.isDone(),"Actual future still owns slot");
            original.complete("ready");drain.get(3,TimeUnit.SECONDS);
            require(!activity.hasActiveWork(),"Actual completion retires slot");
            activity.resume();require(activity.invoke(()->CompletableFuture.completedFuture(7)).get(2,TimeUnit.SECONDS)==7,"Resume only after idle");
        }finally{original.complete("cleanup");activity.closeAsync().get(3,TimeUnit.SECONDS);}
    }

    private static void heldSupplierAndQueuedSkipHaveActualFinally()throws Exception{
        var activity=new LegacyWorldActivity("authored-held-supplier",false);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var queued=new AtomicInteger();
        try{
            activity.invoke(()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}return CompletableFuture.completedFuture(1);});
            require(entered.await(2,TimeUnit.SECONDS),"Held supplier entered");
            var pending=new ArrayList<CompletableFuture<Integer>>();
            for(int i=0;i<3;i++){pending.add(activity.invoke(()->{queued.incrementAndGet();return CompletableFuture.completedFuture(2);}));}
            fails(activity.invoke(()->CompletableFuture.completedFuture(3)));
            activity.discardQueued();var drain=activity.pauseAndDrain();
            require(!drain.isDone(),"Held supplier finally is not logical cancellation");
            release.countDown();drain.get(3,TimeUnit.SECONDS);
            require(queued.get()==0,"Retired queued suppliers do no provider work");
            for(var result:pending){fails(result);}
            require(!activity.hasActiveWork(),"No stranded cleared-queue ticket");
        }finally{release.countDown();activity.closeAsync().get(3,TimeUnit.SECONDS);}
    }

    private static void cancellationIsOffCallerAndItsFinallyIsPartOfDrain()throws Exception{
        var activity=new LegacyWorldActivity("authored-cancel-finally",true);
        var actual=new CompletableFuture<Integer>();var supplier=new CountDownLatch(1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var count=new AtomicInteger();var thread=new AtomicReference<Thread>();Object key=new Object();
        Runnable cancel=()->{count.incrementAndGet();thread.set(Thread.currentThread());entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}};
        try{
            activity.invoke(key,cancel,()->{supplier.countDown();return actual;});
            require(supplier.await(2,TimeUnit.SECONDS),"Supplier invocation");
            long start=System.nanoTime();var drain=activity.pauseAndDrain(key,cancel);
            require(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(250),"Pause cannot execute cancellation on caller");
            require(entered.await(2,TimeUnit.SECONDS),"Lower cancellation entered off caller");
            for(int i=0;i<50;i++){activity.stop(key,cancel);}
            require(thread.get()!=Thread.currentThread()&&count.get()==1,"One coalesced cancellation off caller");
            actual.complete(1);
            require(!drain.isDone()&&activity.hasActiveWork(),"Provider result does not fake callback-finally");
            release.countDown();drain.get(3,TimeUnit.SECONDS);require(!activity.hasActiveWork(),"Both owners retired");
        }finally{actual.complete(0);release.countDown();activity.closeAsync().get(3,TimeUnit.SECONDS);}
    }

    private static void fourTokenCapacityAndStickyCancellationFailure()throws Exception{
        var activity=new LegacyWorldActivity("authored-four-tokens",true);
        var release=new CountDownLatch(1);var entered=new CountDownLatch(1);var count=new AtomicInteger();
        try{
            for(int i=0;i<4;i++){activity.stop(new Object(),()->{count.incrementAndGet();entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}});}
            require(entered.await(2,TimeUnit.SECONDS),"Bounded cancellation lane active");
            try{activity.stop(new Object(),()->{});throw new AssertionError("Fifth token admitted");}catch(IllegalStateException expected){ }
            var drain=activity.pauseAndDrain();require(!drain.isDone(),"Four queued/running callbacks retained");release.countDown();drain.get(3,TimeUnit.SECONDS);require(count.get()==4,"Each admitted token callback runs once");
        }finally{release.countDown();activity.closeAsync().get(3,TimeUnit.SECONDS);}
        var failed=new LegacyWorldActivity("authored-cancel-failure",true);var touched=new AtomicInteger();
        failed.stop(new Object(),()->{throw new RuntimeException(){@Override public String getMessage(){touched.incrementAndGet();throw new AssertionError("message trap");}};});
        fails(failed.pauseAndDrain());require(touched.get()==0,"Unknown Throwable accessors never run");
        try{failed.resume();throw new AssertionError("Unknown cancellation cleanup admitted resume");}catch(IllegalStateException expected){ }
        fails(failed.closeAsync());
    }

    private LegacyWorldActivityTest(){}
}
