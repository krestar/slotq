package com.slotq.architecture;

import com.slotq.ai.router.ModelRouter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelRouterArchitectureTests {
    @Test void routerAndAdapterCannotExecuteMcpOrAccessProductAuthOrPersistence() throws Exception {
        Path root=Path.of(ModelRouter.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("com/slotq/ai/router");
        try(var files=Files.walk(root)) {
            for(Path file:files.filter(p->p.toString().endsWith(".class")).toList())
                assertThat(new String(Files.readAllBytes(file),StandardCharsets.ISO_8859_1)).as(file.toString())
                    .doesNotContain("com/slotq/booking/","com/slotq/management/","com/slotq/auth/","com/slotq/mcp/",
                        "com/slotq/integration/","jakarta/persistence/","org/springframework/web/");
        }
    }
    @Test void productAndMcpCoreDoNotDependOnModelRouterOrProvider() throws Exception {
        Path root=Path.of(ModelRouter.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("com/slotq");
        for(String owner:List.of("booking","management","auth","mcp","knowledge","events","venue","tenancy","waitlist"))
            try(var files=Files.walk(root.resolve(owner))) {
                for(Path file:files.filter(p->p.toString().endsWith(".class")).toList())
                    assertThat(new String(Files.readAllBytes(file),StandardCharsets.ISO_8859_1)).as(file.toString()).doesNotContain("com/slotq/ai/");
            }
    }
}
