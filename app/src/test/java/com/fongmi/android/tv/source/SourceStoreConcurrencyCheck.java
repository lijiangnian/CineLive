package com.fongmi.android.tv.source;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class SourceStoreConcurrencyCheck {
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("source-concurrency-");
        ExecutorService pool=Executors.newFixedThreadPool(4);
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicBoolean canceledFetcher=new AtomicBoolean();
        try {
            SourceStore store=new SourceStore(dir.toFile());
            store.refresh("a",()->"old-a",s->1,1,false);
            store.refresh("b",()->"old-b",s->1,1,false);
            Future<?> slow=pool.submit(()->store.refresh("a",()->{
                entered.countDown();release.await();return "new-a";
            },s->1,10,true));
            if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("fetch never started");
            if(!pool.submit(()->store.cached("a").text).get(1,TimeUnit.SECONDS).equals("old-a"))throw new AssertionError("same-key stale read");
            if(!pool.submit(()->store.cached("b").text).get(1,TimeUnit.SECONDS).equals("old-b"))throw new AssertionError("other-key cached read");
            if(!pool.submit(()->store.refresh("b",()->"new-b",s->1,10,true).text).get(1,TimeUnit.SECONDS).equals("new-b"))throw new AssertionError("other-key refresh blocked");
            CountDownLatch waiting=new CountDownLatch(1), interrupted=new CountDownLatch(1);
            Thread waiter=new Thread(()->{
                waiting.countDown();
                try {store.refresh("a",()->{canceledFetcher.set(true);return "canceled";},s->1,20,true);}
                catch(InterruptedException expected){interrupted.countDown();}
                catch(Exception e){throw new AssertionError(e);}
            });
            waiter.start();waiting.await();waiter.interrupt();
            if(!interrupted.await(1,TimeUnit.SECONDS))throw new AssertionError("lock wait not interruptible");
            release.countDown();slow.get(2,TimeUnit.SECONDS);waiter.join(1000);
            if(canceledFetcher.get())throw new AssertionError("canceled waiter performed network IO");
            if(!store.cached("a").text.equals("new-a"))throw new AssertionError("canceled write replaced valid cache");
            System.out.println("SourceStoreConcurrency: 6 checks passed");
        } finally {
            release.countDown();pool.shutdownNow();pool.awaitTermination(2,TimeUnit.SECONDS);
            try(var files=Files.list(dir)){for(Path p:files.toList())Files.deleteIfExists(p);}
            Files.deleteIfExists(dir);
        }
    }
}
