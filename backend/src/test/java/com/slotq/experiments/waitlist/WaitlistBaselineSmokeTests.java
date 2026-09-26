package com.slotq.experiments.waitlist;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class WaitlistBaselineSmokeTests {
    @TempDir Path temporary;

    @Test void realProductWorkloadAndRawRecalculationRunOnMysql() throws Exception {
        Path output=temporary.resolve("fresh-run");
        System.setProperty("slotq.waitlist.baseline.output",output.toString());
        try {
            WaitlistBaselineRunner.main(new String[0]);
            var raw=WaitlistBaselineRunner.JSON.readTree(Files.readString(output.resolve("raw.json")));
            assertThat(raw.path("schemaVersion").asText()).isEqualTo("slotq-waitlist-baseline/v1");
            assertThat(raw.path("manifest").path("mysql").path("version").asText()).startsWith("8.4.");
            assertThat(raw.path("manifest").path("workload").path("workerCount").asInt()).isEqualTo(1);
            assertThat(raw.path("observations").size()).isGreaterThan(5);
            System.setProperty("slotq.waitlist.baseline.recalculate",output.toString());
            WaitlistBaselineRunner.main(new String[0]);
        } finally {
            System.clearProperty("slotq.waitlist.baseline.output");
            System.clearProperty("slotq.waitlist.baseline.recalculate");
        }
    }
}
