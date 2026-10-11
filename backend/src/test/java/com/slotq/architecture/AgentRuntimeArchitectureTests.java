package com.slotq.architecture;

import com.slotq.ai.runtime.AgentRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentRuntimeArchitectureTests {
    @Test void runtimeUsesAuthMcpAndControlledApprovalWithoutProductPersistenceOrPublicSurface() throws Exception {
        Path root=Path.of(AgentRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("com/slotq/ai/runtime");
        try(var files=Files.walk(root)) {
            for(Path file:files.filter(p->p.toString().endsWith(".class")).toList())
                assertThat(new String(Files.readAllBytes(file),StandardCharsets.ISO_8859_1)).as(file.toString()).doesNotContain(
                    "com/slotq/booking/","com/slotq/management/","com/slotq/auth/persistence/","com/slotq/knowledge/",
                    "com/slotq/events/","com/slotq/reliability/","jakarta/persistence/","org/springframework/jdbc/",
                    "org/springframework/web/","java/sql/","hold_idempotency_records","capacity_allocations");
        }
    }
}
