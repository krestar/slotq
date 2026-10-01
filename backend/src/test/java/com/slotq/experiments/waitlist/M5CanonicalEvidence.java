package com.slotq.experiments.waitlist;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.core.type.TypeReference;
import static com.slotq.experiments.waitlist.M5TransportComparisonRunner.*;

/** Lossless row dictionary: repeated snapshots share canonical raw row versions, without deleting observations. */
public final class M5CanonicalEvidence {
    public static void main(String[]args) throws Exception {
        if(System.getProperty("slotq.m5.recalculate")!=null){verify(Path.of(System.getProperty("slotq.m5.recalculate")));return;}
        Path source=Path.of(System.getProperty("slotq.m5.input"));
        Path target=Path.of(System.getProperty("slotq.m5.export"));
        require(!Files.exists(target),"canonical export requires fresh directory");Files.createDirectories(target);
        var summaries=new TreeMap<String,Object>();var inventory=new TreeMap<String,Object>();
        try(var folders=Files.list(source)) {
            for(Path folder:folders.filter(Files::isDirectory).sorted().toList()) if(Files.exists(folder.resolve("raw.json"))) {
                Map<String,Object> raw=JSON.readValue(Files.readString(folder.resolve("raw.json")),new TypeReference<>(){});
                var summary=M5TransportEvidence.summarize(raw);
                require(JSON.readTree(Files.readString(folder.resolve("summary.json"))).equals(JSON.readTree(JSON.writeValueAsString(summary))),"source summary differs from raw");
                Path output=target.resolve(folder.getFileName());Files.createDirectories(output);
                write(output.resolve("manifest.json"),raw.get("manifest"));
                var dictionary=new TreeMap<String,Object>();
                var payload=new LinkedHashMap<>(raw);payload.remove("manifest");
                Object encoded=encode(payload,dictionary);
                String csvHash=sha(Files.readAllBytes(folder.resolve("correspondence.csv")));
                writeRaw(output,Map.of("schemaVersion","slotq-m5-canonical/v1","manifestSha256",sha(Files.readAllBytes(output.resolve("manifest.json"))),"correspondenceCsvSha256",csvHash,"rows",dictionary,"data",encoded));
                Map<String,Object> restored=read(output);
                require(JSON.readTree(JSON.writeValueAsString(raw)).equals(JSON.readTree(JSON.writeValueAsString(restored))),"canonical round trip changed raw");
                summaries.put(folder.getFileName().toString(),summary);
                inventory.put(folder.getFileName().toString(),Map.of("rawFile",rawPath(output).getFileName().toString(),"rawSha256",sha(Files.readAllBytes(rawPath(output))),"manifestSha256",sha(Files.readAllBytes(output.resolve("manifest.json"))),"rowVersions",dictionary.size(),"rawBytes",Files.size(rawPath(output))));
            }
        }
        require(!summaries.isEmpty(),"no source datasets");
        write(target.resolve("summary.json"),summaries);
        write(target.resolve("integrity.json"),Map.of("result","PASS","runs",inventory,"summarySha256",sha(Files.readAllBytes(target.resolve("summary.json")))));
        verify(target);
    }
    static Object encode(Object value,Map<String,Object> dictionary) throws Exception {
        if(value instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(v->v instanceof Map<?,?> m && m.containsKey("tenant_id"))) {
            var refs=new ArrayList<String>();for(Object row:list) {String hash=sha(JSON.writeValueAsString(row).getBytes(StandardCharsets.UTF_8));dictionary.putIfAbsent(hash,row);refs.add(hash);}return Map.of("rowRefs",refs);
        }
        if(value instanceof Map<?,?> map) {var result=new LinkedHashMap<String,Object>();for(var e:map.entrySet())result.put(e.getKey().toString(),encode(e.getValue(),dictionary));return result;}
        if(value instanceof List<?> list) {var result=new ArrayList<Object>();for(Object item:list)result.add(encode(item,dictionary));return result;}
        return value;
    }
    static Object decode(Object value,Map<String,Object> dictionary) {
        if(value instanceof Map<?,?> map) {
            if(map.size()==1&&map.containsKey("rowRefs"))return ((List<?>)map.get("rowRefs")).stream().map(ref->{Object row=dictionary.get(ref.toString());require(row!=null,"missing canonical row version");return row;}).toList();
            var result=new LinkedHashMap<String,Object>();for(var e:map.entrySet())result.put(e.getKey().toString(),decode(e.getValue(),dictionary));return result;
        }
        if(value instanceof List<?> list)return list.stream().map(v->decode(v,dictionary)).toList();return value;
    }
    static Map<String,Object> read(Path folder) throws Exception {
        Map<String,Object> stored=stored(folder);
        require("slotq-m5-canonical/v1".equals(stored.get("schemaVersion")),"unsupported canonical schema");
        require(sha(Files.readAllBytes(folder.resolve("manifest.json"))).equals(stored.get("manifestSha256")),"manifest integrity mismatch");
        Map<String,Object> dictionary=M5TransportEvidence.map(stored.get("rows"));
        for(var row:dictionary.entrySet())require(row.getKey().equals(sha(JSON.writeValueAsString(row.getValue()).getBytes(StandardCharsets.UTF_8))),"canonical row hash mismatch");
        var raw=M5TransportEvidence.map(decode(stored.get("data"),dictionary));
        raw.put("manifest",JSON.readValue(Files.readString(folder.resolve("manifest.json")),new TypeReference<Map<String,Object>>(){}));return raw;
    }
    static void verify(Path target) throws Exception {
        Map<String,Object> integrity=JSON.readValue(Files.readString(target.resolve("integrity.json")),new TypeReference<>(){});
        require(sha(Files.readAllBytes(target.resolve("summary.json"))).equals(integrity.get("summarySha256")),"summary integrity mismatch");
        var inventory=M5TransportEvidence.map(integrity.get("runs"));
        var recalculated=new TreeMap<String,Object>();
        var repetitions=new TreeMap<String,Set<Long>>();
        try(var folders=Files.list(target)) {
            for(Path folder:folders.filter(Files::isDirectory).sorted().toList()) if(hasRaw(folder)) {
                var expected=M5TransportEvidence.map(inventory.get(folder.getFileName().toString()));require(expected!=null,"unlisted canonical dataset");
                require(sha(Files.readAllBytes(rawPath(folder))).equals(expected.get("rawSha256")),"raw inventory integrity mismatch");
                var raw=read(folder);recalculated.put(folder.getFileName().toString(),M5TransportEvidence.summarize(raw));
                var manifest=M5TransportEvidence.map(raw.get("manifest"));var comparison=M5TransportEvidence.map(manifest.get("comparison"));
                var runs=repetitions.computeIfAbsent(comparison.get("profile").toString(),k->new TreeSet<>());require(runs.add(M5TransportEvidence.number(comparison,"repetition")),"duplicate repetition");
                require(comparison.get("logicalConsumers").equals(CONSUMERS),"logical consumer comparison mismatch");
                require(M5TransportEvidence.number(M5TransportEvidence.map(manifest.get("workload")),"seed")==10501,"workload seed mismatch");
                Path csv=Files.createTempFile("m5-canonical-",".csv");
                try {WaitlistBaselineEvidence.writeCsv(csv,raw);require(sha(Files.readAllBytes(csv)).equals(stored(folder).get("correspondenceCsvSha256")),"correspondence recalculation mismatch");}finally{Files.deleteIfExists(csv);}
            }
        }
        require(JSON.readTree(Files.readString(target.resolve("summary.json"))).equals(JSON.readTree(JSON.writeValueAsString(recalculated))),"canonical raw -> summary mismatch");
        require(recalculated.keySet().equals(inventory.keySet()),"missing inventory dataset");
        require(repetitions.keySet().equals(Set.of("DB_DIRECT-1","DB_DIRECT-3","KAFKA-1","KAFKA-3","KAFKA-3-extra"))&&repetitions.values().stream().allMatch(r->r.size()>=3),"comparison cohort lacks three repetitions per profile");
        System.out.println("#111 canonical raw -> summary/CSV PASS: "+recalculated.size()+" datasets");
    }
    static boolean hasRaw(Path folder){return Files.exists(folder.resolve("raw.json"))||Files.exists(folder.resolve("raw.json.gz"));}
    static Path rawPath(Path folder){boolean compressed=Files.exists(folder.resolve("raw.json.gz"));require(!(compressed&&Files.exists(folder.resolve("raw.json"))),"duplicate canonical raw formats");return folder.resolve(compressed?"raw.json.gz":"raw.json");}
    static Map<String,Object> stored(Path folder)throws Exception {
        Path path=rawPath(folder);try(var file=Files.newInputStream(path);var input=path.toString().endsWith(".gz")?new java.util.zip.GZIPInputStream(file):file){return JSON.readValue(input,new TypeReference<>(){});}
    }
    static void writeRaw(Path folder,Object value)throws Exception {try(var output=new java.util.zip.GZIPOutputStream(Files.newOutputStream(folder.resolve("raw.json.gz")))){output.write(JSON.writeValueAsBytes(value));}}
    static String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
