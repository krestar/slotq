package com.slotq.experiments.waitlist;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import tools.jackson.core.type.TypeReference;

import static com.slotq.experiments.waitlist.M5TransportComparisonRunner.*;
import static com.slotq.experiments.waitlist.WaitlistRecoveryDatabase.*;

/** Recomputes both consumer oracles and comparison denominators from persisted rows. */
final class M5TransportEvidence {
    static Map<String,Object> summarize(Map<String,Object> raw) {
        var result=new LinkedHashMap<String,Object>();
        result.put("schemaVersion","slotq-m5-comparison-summary/v1");
        result.put("waitlist",WaitlistBaselineEvidence.summarize(raw));
        var distribution=map(raw.get("distribution"));
        var observations=maps(distribution.get("observations"));
        require(!observations.isEmpty(),"distribution observations missing");
        for(var o:observations) reconcile(o,false);
        var last=observations.getLast(); var counts=reconcile(last,true);
        result.put("correctness",counts);
        var timings=new LinkedHashMap<String,Object>();
        var eventTimes=new ArrayList<Double>(); var intakeTimes=new ArrayList<Double>(); var productProgress=new ArrayList<Double>();
        var events=new LinkedHashMap<String,Map<String,Object>>();
        for(var t:maps(last.get("tenants"))) for(var e:list(t,"event_records")) events.put(e.get("event_id").toString(),e);
        for(var t:maps(last.get("tenants"))) for(var d:list(t,"event_deliveries"))
            eventTimes.add(millis(events.get(d.get("event_id")).get("recorded_at"),d.get("updated_at")));
        for(var i:maps(last.get("event_kafka_target_intakes"))) intakeTimes.add(millis(events.get(i.get("event_id")).get("recorded_at"),i.get("intaken_at")));
        var firstOffers=new HashMap<String,Long>();
        for(var o:maps(raw.get("observations"))) for(var t:maps(o.get("tenants"))) for(var offer:list(t,"waitlist_offers"))
            firstOffers.putIfAbsent(offer.get("entry_id").toString(),number(o,"endNanos"));
        for(var c:maps(raw.get("commands"))) if("registration".equals(c.get("type"))||"contended-registration".equals(c.get("type"))) {
            String answer=c.get("result").toString();
            for(var e:firstOffers.entrySet()) if(answer.contains(uuid(e.getKey()).toString()))
                productProgress.add((e.getValue()-number(c,"startNanos"))/1_000_000.0);
        }
        timings.put("eventInsertToDoneUpdateMs",quantiles(eventTimes));
        timings.put("eventInsertToIntakeInsertMs",quantiles(intakeTimes));
        timings.put("demandInvocationToFirstOfferObservationUpperBoundMs",quantiles(productProgress));
        timings.put("semantics","recorded_at/intaken_at/updated_at are in-transaction DB timestamps, not physical commit timestamps; demand progress is a monotonic invocation-to-fresh-observation upper bound; business clock is excluded");
        result.put("timing",timings);
        var phases=new LinkedHashMap<String,Object>();
        for(String phase:observations.stream().map(o->o.get("phase").toString()).distinct().toList()) {
            var rows=observations.stream().filter(o->phase.equals(o.get("phase"))).toList();
            var first=rows.getFirst(); var end=rows.getLast();
            long nanos=number(end,"endNanos")-number(first,"startNanos");
            var a=reconcile(first,false);var b=reconcile(end,false);
            long committed=number(b,"originalEvents")-number(a,"originalEvents"),done=number(b,"doneTargets")-number(a,"doneTargets");
            var p=new LinkedHashMap<String,Object>();p.put("observationWindowMs",nanos/1_000_000.0);
            p.put("committedInputsAdded",committed);p.put("committedInputRate",rate(committed,nanos));
            p.put("doneTargetsAdded",done);p.put("businessCompletionRate",rate(done,nanos));
            long intakes=number(b,"durableTargetIntakes")-number(a,"durableTargetIntakes");
            p.put("durableIntakesAdded",intakes);p.put("durableIntakeRate",rate(intakes,nanos));
            p.put("first",a);p.put("last",b);
            phases.put(phase,p);
        }
        result.put("phases",phases);
        var samples=maps(distribution.get("resources"));
        var containers=new TreeMap<String,List<Map<String,Object>>>();
        for(var sample:samples) if(sample.containsKey("containerStatsJsonLines")) for(String line:sample.get("containerStatsJsonLines").toString().lines().filter(s->!s.isBlank()).toList()) {
            try {var row=JSON.readValue(line,new TypeReference<Map<String,Object>>(){});String role=map(sample.get("containerRoles")).get(row.get("ID").toString()).toString();containers.computeIfAbsent(role,k->new ArrayList<>()).add(row);}catch(Exception malformed){throw new IllegalStateException("invalid resource sample",malformed);}
        }
        var usage=new TreeMap<String,Object>();for(var entry:containers.entrySet())usage.put(entry.getKey(),Map.of("samples",entry.getValue().size(),"cpuPercent",quantiles(entry.getValue().stream().map(r->Double.parseDouble(r.get("CPUPerc").toString().replace("%",""))).toList()),"maxMemoryBytes",entry.getValue().stream().mapToDouble(r->bytes(r.get("MemUsage").toString().split(" / ")[0])).max().orElse(0),"network",entry.getValue().getLast().get("NetIO"),"blockIO",entry.getValue().getLast().get("BlockIO")));
        result.put("resources",Map.of("samples",samples.size(),"unavailable",samples.stream().filter(s->s.containsKey("unavailable")).count(),"containers",usage,"heapUsedBytes",quantiles(samples.stream().filter(s->s.containsKey("heapUsedBytes")).map(s->((Number)s.get("heapUsedBytes")).doubleValue()).toList())));
        result.put("dbStatusFirst",observations.getFirst().get("dbStatus"));result.put("dbStatusLast",last.get("dbStatus"));
        result.put("pool",Map.of("maxObservedActive",observations.stream().map(o->map(o.get("pool"))).mapToLong(p->number(p,"active")).max().orElse(0),"maxObservedWaiting",observations.stream().map(o->map(o.get("pool"))).mapToLong(p->number(p,"waiting")).max().orElse(0),"semantics","fresh observation-boundary samples, not a wait-duration histogram"));
        result.put("poolAcquireFirst",observations.getFirst().get("poolAcquire"));result.put("poolAcquireLast",last.get("poolAcquire"));
        var waits=new TreeMap<String,Long>();for(var o:observations)for(var row:maps(o.getOrDefault("lockWaits",List.of())))waits.merge(row.get("table_name").toString(),number(row,"waiting"),Math::max);
        result.put("lockWaits",Map.of("maxObservedByTable",waits,"semantics","fresh performance_schema blocking-table samples, including event_boundary fence; zero samples do not prove no transient wait; cumulative DB row-lock time/waits recorded separately"));
        result.put("brokerState",last.getOrDefault("broker",Map.of("status","not applicable")));
        result.put("limitations",List.of("finite closed-loop workload; coordinated omission and observation cost; not steady-state saturation or production SLO",
            "co-located harness JVM; process restart/rebalance/failure isolation belongs to independent drill and #109 evidence",
            "same host envelope does not enforce one aggregate cgroup across JVM and containers; per-role caps and actual usage disclosed",
            "MySQL query/row-lock counters include oracle/telemetry queries; fence wait has sampled inventory rather than a duration histogram",
            "single-broker comparison is not HA evidence; additional allocation scale-out profile reported separately"));
        return result;
    }

    static Map<String,Object> reconcile(Map<String,Object> o,boolean complete) {
        long events=0,targets=0,done=0,dead=0,outstanding=0,retries=0;
        var projections=maps(o.get("event_observation_projections"));
        var intake=maps(o.get("event_kafka_target_intakes"));
        var assignments=maps(o.get("assignments"));
        var authority=new HashMap<String,Map<String,Object>>();for(var a:assignments)require(authority.put(a.get("registration_id").toString().toLowerCase(Locale.ROOT),a)==null,"duplicate assignment");
        var projectionKeys=new HashSet<String>();for(var p:projections) require(projectionKeys.add(key(p)),"duplicate observer logical receipt");
        double oldestPendingMs=0;
        for(var tenant:maps(o.get("tenants"))) {
            var originals=list(tenant,"event_records"); events+=originals.size();
            var deliveries=list(tenant,"event_deliveries");var registrations=list(tenant,"registrations");
            for(var d:deliveries) require(originals.stream().anyMatch(e->Objects.equals(e.get("event_id"),d.get("event_id"))&&registrations.stream().anyMatch(r->Objects.equals(r.get("registration_id"),d.get("registration_id"))&&CONSUMERS.contains(r.get("consumer_id"))&&member(e,r))),"unexplained extra delivery target");
            for(var e:originals) for(String consumer:CONSUMERS) {
                var members=registrations.stream().filter(r->consumer.equals(r.get("consumer_id"))&&member(e,r)).toList();
                require(members.size()==1,"missing/ambiguous original membership for "+consumer);targets++;
                var r=members.getFirst();var a=authority.get(r.get("registration_id").toString());require(a!=null,"transport authority missing");
                var matching=deliveries.stream().filter(d->Objects.equals(d.get("event_id"),e.get("event_id"))&&Objects.equals(d.get("registration_id"),r.get("registration_id"))).toList();
                require(matching.size()<=1,"duplicate original target");
                if(matching.isEmpty()) {outstanding++;require(!complete,"unexplained original target loss");continue;}
                var d=matching.getFirst();String state=d.get("state").toString();
                if(Set.of("PENDING","PROCESSING").contains(state)&&tenant.containsKey("databaseNow"))oldestPendingMs=Math.max(oldestPendingMs,millis(java.time.LocalDateTime.parse(tenant.get("databaseNow").toString().replace(' ','T')).toInstant(java.time.ZoneOffset.UTC).toString(),d.get("created_at"))*(-1));
                if(state.equals("DONE"))done++;else if(state.equals("DEAD"))dead++;else outstanding++;
                retries+=Math.max(0,number(d,"lifetime_attempts")-1);
                if(consumer.equals(OBSERVER)) {
                    var receipts=projections.stream().filter(p->Objects.equals(p.get("event_id"),e.get("event_id"))&&Objects.equals(p.get("registration_id"),r.get("registration_id"))).toList();
                    require(receipts.size()==(state.equals("DONE")?1:0),"partial observer receipt/DONE");
                    if(!receipts.isEmpty()) {
                        var p=receipts.getFirst();require(Objects.equals(p.get("tenant_id"),e.get("tenant_id"))&&Objects.equals(p.get("event_type"),e.get("event_type")),"observer original meaning mismatch");
                        if("DB_DIRECT".equals(a.get("transport")))require(p.get("intaken_at")==null,"DB observer fabricated Kafka intake time");
                    }
                }
                if("KAFKA".equals(a.get("transport"))) {
                    var provenance=intake.stream().filter(i->Objects.equals(i.get("event_id"),e.get("event_id"))&&Objects.equals(i.get("registration_id"),r.get("registration_id"))).toList();
                    require(provenance.size()==1,"Kafka target without unique durable intake");
                    require(number(provenance.getFirst(),"authority_epoch")==number(a,"authority_epoch"),"intake authority epoch mismatch");
                }
            }
        }
        long quarantine=maps(o.getOrDefault("quarantine",List.of())).size();
        require(dead==0&&quarantine==0,"unexpected DEAD/quarantine in healthy comparison");
        if(complete)require(outstanding==0&&done==targets,"incomplete comparison");
        var result=new LinkedHashMap<String,Object>();result.put("originalEvents",events);result.put("originalTargets",targets);result.put("doneTargets",done);result.put("outstandingTargets",outstanding);
        result.put("dead",dead);result.put("quarantine",quarantine);result.put("extraLifetimeClaims",retries);
        result.put("durableTargetIntakes",intake.size());result.put("observerReceipts",projections.size());
        result.put("physicalTargetIntakes",maps(o.get("event_kafka_intake_records")).stream().filter(i->"TARGET".equals(i.get("disposition"))).count());
        if(maps(o.get("tenants")).stream().allMatch(t->t.containsKey("databaseNow")))result.put("oldestMaterializedPendingAgeMs",oldestPendingMs);
        result.put("unexplainedLoss",0);result.put("partialObserverReceiptDone",0);result.put("duplicateObserverEffect",0);
        return result;
    }

    static void recalculate(Path root) throws Exception {
        var summaries=new LinkedHashMap<String,Object>();
        try(var directories=Files.list(root)) {
            for(Path folder:directories.filter(Files::isDirectory).sorted().toList()) if(Files.exists(folder.resolve("raw.json"))) {
                Map<String,Object> raw=JSON.readValue(Files.readString(folder.resolve("raw.json")),new TypeReference<>(){});
                var summary=summarize(raw);
                require(JSON.readTree(Files.readString(folder.resolve("summary.json"))).equals(JSON.readTree(JSON.writeValueAsString(summary))),"saved summary diverges from raw: "+folder);
                Path csv=Files.createTempFile("slotq-m5-recalculated-",".csv");
                try {WaitlistBaselineEvidence.writeCsv(csv,raw);require(Files.mismatch(csv,folder.resolve("correspondence.csv"))==-1,"CSV diverges from raw");}finally{Files.deleteIfExists(csv);}
                summaries.put(folder.getFileName().toString(),summary);
            }
        }
        require(!summaries.isEmpty(),"no comparison raw datasets");
        var hashes=new TreeMap<String,String>();
        try(var files=Files.walk(root)) {for(Path f:files.filter(Files::isRegularFile).filter(f->Set.of("raw.json","summary.json","manifest.json","correspondence.csv").contains(f.getFileName().toString())).toList())hashes.put(root.relativize(f).toString().replace('\\','/'),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f))));}
        write(root.resolve("recalculation.json"),Map.of("result","PASS","datasets",summaries.keySet(),"sha256",hashes));
        write(root.resolve("comparison.json"),summaries);
        System.out.println("#111 raw -> summaries/CSV PASS: "+summaries.size()+" datasets");
    }
    static boolean member(Map<String,Object>e,Map<String,Object>r) {long n=number(e,"boundary_sequence");return Objects.equals(e.get("event_type"),r.get("event_type"))&&number(e,"schema_version")==number(r,"schema_version")&&n>number(r,"activation_boundary")&&(r.get("deactivation_boundary")==null||n<number(r,"deactivation_boundary"));}
    static String key(Map<String,Object> row) {return row.get("tenant_id")+"/"+row.get("event_id")+"/"+row.get("registration_id");}
    static UUID uuid(String hex) {var b=java.nio.ByteBuffer.wrap(HexFormat.of().parseHex(hex));return new UUID(b.getLong(),b.getLong());}
    static long number(Map<String,Object>row,String key) {return ((Number)row.get(key)).longValue();}
    static double rate(long count,long nanos) {return nanos==0?0:count*1_000_000_000.0/nanos;}
    static double millis(Object a,Object b) {return Duration.between(Instant.parse(a.toString()),Instant.parse(b.toString())).toNanos()/1_000_000.0;}
    static double bytes(String value){var matcher=java.util.regex.Pattern.compile("([0-9.]+)\\s*(B|kB|MB|GB|KiB|MiB|GiB)").matcher(value.strip());require(matcher.matches(),"unknown Docker resource unit");double multiplier=switch(matcher.group(2)){case "kB"->1000;case "MB"->1_000_000;case "GB"->1_000_000_000;case "KiB"->1024;case "MiB"->1024*1024;case "GiB"->1024*1024*1024;default->1;};return Double.parseDouble(matcher.group(1))*multiplier;}
    static Map<String,Object> quantiles(List<Double>values) {var sorted=values.stream().sorted().toList();var result=new LinkedHashMap<String,Object>();result.put("samples",sorted.size());if(!sorted.isEmpty()){result.put("min",sorted.getFirst());result.put("max",sorted.getLast());result.put("p50",sorted.get((int)Math.ceil(sorted.size()*.5)-1));result.put("p95",sorted.get((int)Math.ceil(sorted.size()*.95)-1));}return result;}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value) {return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> maps(Object value) {return (List<Map<String,Object>>)value;}
}
