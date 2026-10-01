package com.slotq.experiments.waitlist;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.slotq.experiments.waitlist.M5TransportComparisonRunner.*;

class M5CanonicalEvidenceTests {
    @TempDir Path folder;
    @Test void deduplicatesRowVersionsWithoutDeletingObservationOrderOrChangedState() throws Exception {
        var pending=Map.of("tenant_id","tenant","event_id","original","state","PENDING");
        var done=Map.of("tenant_id","tenant","event_id","original","state","DONE");
        var raw=Map.of("observations",List.of(List.of(pending),List.of(pending),List.of(done)));
        var dictionary=new TreeMap<String,Object>();var encoded=M5CanonicalEvidence.encode(raw,dictionary);
        assertThat(dictionary).hasSize(2);
        assertThat(M5CanonicalEvidence.decode(encoded,dictionary)).isEqualTo(raw);
        dictionary.clear();assertThatThrownBy(()->M5CanonicalEvidence.decode(encoded,dictionary)).hasMessageContaining("missing canonical row");
    }
    @Test void manifestAndRowTamperingCannotPassCanonicalRead() throws Exception {
        write(folder.resolve("manifest.json"),Map.of("seed",10501));
        var dictionary=new TreeMap<String,Object>();var data=M5CanonicalEvidence.encode(Map.of("rows",List.of(Map.of("tenant_id","t","event_id","e"))),dictionary);
        var stored=new LinkedHashMap<String,Object>(Map.of("schemaVersion","slotq-m5-canonical/v1","manifestSha256",M5CanonicalEvidence.sha(Files.readAllBytes(folder.resolve("manifest.json"))),"rows",dictionary,"data",data));write(folder.resolve("raw.json"),stored);
        assertThat(M5CanonicalEvidence.read(folder)).containsKey("manifest");
        dictionary.put(dictionary.firstKey(),Map.of("tenant_id","other","event_id","e"));write(folder.resolve("raw.json"),stored);
        assertThatThrownBy(()->M5CanonicalEvidence.read(folder)).hasMessageContaining("row hash");
        write(folder.resolve("manifest.json"),Map.of("seed",999));assertThatThrownBy(()->M5CanonicalEvidence.read(folder)).hasMessageContaining("manifest integrity");
    }
    @Test void compressedCanonicalRawIsLosslessAndRejectsDuplicateFormatsAndBrokenStreams() throws Exception {
        write(folder.resolve("manifest.json"),Map.of("seed",10501));
        var dictionary=new TreeMap<String,Object>();var source=Map.of("observations",List.of(List.of(Map.of("tenant_id","t","event_id","e","state","PENDING")),List.of(Map.of("tenant_id","t","event_id","e","state","DONE"))));
        var data=M5CanonicalEvidence.encode(source,dictionary);
        M5CanonicalEvidence.writeRaw(folder,Map.of("schemaVersion","slotq-m5-canonical/v1","manifestSha256",M5CanonicalEvidence.sha(Files.readAllBytes(folder.resolve("manifest.json"))),"rows",dictionary,"data",data));
        assertThat(M5CanonicalEvidence.read(folder)).containsEntry("observations",source.get("observations"));
        Files.writeString(folder.resolve("raw.json"),"{}");assertThatThrownBy(()->M5CanonicalEvidence.read(folder)).hasMessageContaining("duplicate canonical raw");Files.delete(folder.resolve("raw.json"));
        Files.write(folder.resolve("raw.json.gz"),new byte[]{1,2,3});assertThatThrownBy(()->M5CanonicalEvidence.read(folder)).isInstanceOf(java.io.IOException.class);
    }
}
