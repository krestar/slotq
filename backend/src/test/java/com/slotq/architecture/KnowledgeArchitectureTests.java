package com.slotq.architecture;

import com.slotq.knowledge.application.CorpusService;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KnowledgeArchitectureTests {
    @Test void corpusUsesPublicAccessAndVenuePortsWithoutProductPersistenceOrRetrievalRuntime() throws Exception {
        Path root = Path.of(CorpusService.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("com/slotq/knowledge");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                String content = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                assertThat(content).as(path.toString()).doesNotContain("com/slotq/auth/persistence/", "com/slotq/venue/persistence/",
                        "com/slotq/booking/", "com/slotq/waitlist/", "com/slotq/management/", "com/slotq/events/",
                        "com/slotq/integration/", "com/slotq/mcp/", "jakarta/persistence/", "org/springframework/kafka/",
                        "redis", "embedding", "vector");
                if (!path.toString().contains("persistence")) {
                    assertThat(content).doesNotContain("org/springframework/jdbc/", "knowledge_documents", "knowledge_versions");
                }
            }
        }
    }
    @Test void corpusPersistenceReferencesOnlyOwnedTablesAndTheVenueScopeForeignKey() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V23__create_knowledge_corpus.sql"));
        assertThat(migration).doesNotContain("reservations", "booking_policies", "waitlist_", "auth_access_", "operations_", "event_");
        assertThat(migration).contains("REFERENCES venues(tenant_id, id)", "fk_knowledge_current_version");
        Path root = Path.of(CorpusService.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("com/slotq/knowledge/persistence");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                String content = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                assertThat(content).doesNotContain("FROM venues", "FROM tenants", "auth_", "operations_", "booking_", "event_");
            }
        }
    }
}
