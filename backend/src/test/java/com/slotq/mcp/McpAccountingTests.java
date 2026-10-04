package com.slotq.mcp;

import com.slotq.auth.access.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class McpAccountingTests {
    @TempDir Path directory;
    @Test void cardinalitySaturationCannotEvictInFlightOrIndebtedKeysAndClockFailureClosesAdmission() {
        var clock=new AtomicLong(10);var limiter=new LocalAdmission(new LocalAdmission.Limit(1,2,1),8,clock::get);
        var first=McpFoundationTests.actor(AccessProfile.CUSTOMER);
        try(var permit=limiter.attempt(first,"tool-a",null)) {
            assertThatThrownBy(()->limiter.attempt(first,"tool-a",null)).isInstanceOf(McpFailure.class);
            var second=McpFoundationTests.actor(AccessProfile.MANAGEMENT);
            try(var p=limiter.attempt(second,"tool-b",null)) {
                assertThat(limiter.bucketCount()).isEqualTo(8);
                assertThatThrownBy(()->limiter.attempt(McpFoundationTests.actor(AccessProfile.CUSTOMER),"tool-c",null)).isInstanceOf(McpFailure.class);
                clock.addAndGet(Duration.ofSeconds(10).toNanos());
                assertThatThrownBy(()->limiter.attempt(first,"tool-a",null)).isInstanceOf(McpFailure.class);
            }
        }
        clock.set(0);
        assertThatThrownBy(()->limiter.attempt(first,"tool-a",null)).isInstanceOf(McpFailure.class);
        clock.addAndGet(Duration.ofDays(1).toNanos());
        assertThatThrownBy(()->limiter.attempt(first,"tool-a",null)).isInstanceOf(McpFailure.class);
    }
    @Test void fileSinkEnforcesSevenDayRetentionAndFiniteBytesWithoutDeletingUnrelatedFiles() throws Exception {
        var clock=Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"),ZoneOffset.UTC);
        Files.writeString(directory.resolve("mcp-2026-09-27.jsonl"),"old");
        Files.writeString(directory.resolve("mcp-2026-09-28.jsonl"),"retained");
        Files.writeString(directory.resolve("unrelated.jsonl"),"retain");
        var sink=new MetadataFileSink(directory,clock,7,1024);
        var event=new McpAudit.Event(UUID.randomUUID(),null,null,null,null,null,null,false,McpFailure.Reason.FORBIDDEN,
            McpAudit.Dispatch.NOT_DISPATCHED,McpAudit.Outcome.DENIED,1,false,McpAudit.TimeoutLayer.NONE,null,null,null,null,null,null);
        sink.accept(event);
        assertThat(directory.resolve("mcp-2026-09-27.jsonl")).doesNotExist();
        assertThat(directory.resolve("mcp-2026-09-28.jsonl")).exists();
        assertThat(directory.resolve("unrelated.jsonl")).hasContent("retain");
        assertThatThrownBy(()->{for(int i=0;i<20;i++)sink.accept(event);}).isInstanceOf(IllegalStateException.class);
        assertThat(Files.size(directory.resolve("mcp-2026-10-04.jsonl"))).isLessThanOrEqualTo(1024);
    }
    @Test void productOriginCannotBeCallerSelectedRemoteRedirectOrContainCredentials() {
        for(String uri:List.of("https://evil.example:8443","http://localhost:8443","https://user:secret@localhost:8443",
            "https://localhost:8443/?query=secret","https://localhost:8444")) {
            assertThatThrownBy(()->new ProductHttpBinding(java.net.URI.create(uri),8443,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void clockRegressionCannotExtendDeadlineOrDispatchAContextFromTheFuture() {
        var actor=McpFoundationTests.actor(AccessProfile.CUSTOMER);
        Instant admitted=Instant.now();var clock=Clock.fixed(admitted.minusSeconds(1),ZoneOffset.UTC);
        var ctx=new RequestContext(actor,UUID.randomUUID(),admitted,admitted.plusSeconds(30));
        assertThatThrownBy(()->ctx.revalidate(McpFoundationTests.authority(actor),clock)).isInstanceOf(McpFailure.class);
        assertThatThrownBy(()->ctx.remaining(clock,Duration.ofSeconds(15))).isInstanceOf(McpFailure.class);
    }
}
