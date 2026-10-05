package com.slotq.experiments.waitlist;

import java.time.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class WaitlistProcessRecoveryEvidenceTests {
    @Test void bothSpringFixedDelayOverloadsRespectTheTestOnlyPause() throws Exception {
        WaitlistProcessRecoveryRunner.mode="RECOVER_BACKLOG";WaitlistProcessRecoveryRunner.ticks=false;
        var scheduler=new WaitlistProcessRecoveryRunner.ChildConfiguration().taskScheduler();scheduler.initialize();
        var calls=new AtomicInteger();var called=new CountDownLatch(2);
        Runnable work=()->{calls.incrementAndGet();called.countDown();};
        try {
            scheduler.scheduleWithFixedDelay(work,Duration.ofMillis(10));
            scheduler.scheduleWithFixedDelay(work,Instant.now(),Duration.ofMillis(10));
            Thread.sleep(60);assertThat(calls.get()).isZero();
            WaitlistProcessRecoveryRunner.ticks=true;assertThat(called.await(2,TimeUnit.SECONDS)).isTrue();
        } finally {WaitlistProcessRecoveryRunner.ticks=false;scheduler.shutdown();}
    }

}
