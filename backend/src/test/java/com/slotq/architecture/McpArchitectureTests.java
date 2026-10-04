package com.slotq.architecture;

import com.slotq.mcp.McpEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class McpArchitectureTests {
    @Test void productAuthAndSharedFoundationDoNotDependOnConcreteAiProductOrRetrievalImplementations() throws Exception {
        Path root=Path.of(McpEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("com/slotq");
        List<String> violations=new ArrayList<>();
        for(String owner:List.of("auth","booking","management","tenancy","venue","waitlist","events","mcp")) {
            try(var files=Files.walk(root.resolve(owner))) {
                for(Path file:files.filter(p->p.toString().endsWith(".class")).toList()) {
                    String content=new String(Files.readAllBytes(file),StandardCharsets.ISO_8859_1);
                    List<String> forbidden=owner.equals("mcp")?List.of("com/slotq/booking/","com/slotq/management/",
                        "com/slotq/knowledge/","com/slotq/integration/mcp/"):
                        List.of("com/slotq/mcp/","com/slotq/knowledge/");
                    for(String dependency:forbidden)if(content.contains(dependency))violations.add(root.relativize(file)+" -> "+dependency);
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
