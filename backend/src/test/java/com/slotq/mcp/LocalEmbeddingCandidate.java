package com.slotq.mcp;

import com.slotq.integration.mcp.knowledge.*;
import com.slotq.knowledge.domain.Corpus.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual local model candidate, comparison-only. Runtime lexical default needs no Python/model. */
final class LocalEmbeddingCandidate implements CandidateSearch {
    final Path python, script, model;
    final Clock clock;
    final JsonMapper json=JsonMapper.builder().build();
    final Map<ChunkKey,double[]> index=new LinkedHashMap<>();
    final List<Map<String,Object>> observations=new ArrayList<>();
    record ChunkKey(VersionReference reference,int start,int end) { }
    LocalEmbeddingCandidate(Clock clock) {
        this(clock,Path.of(System.getProperty("slotq.knowledge.python")),Path.of("../infra/retrieval/local_embedding.py"),
                Path.of(System.getProperty("slotq.knowledge.model")));
    }
    LocalEmbeddingCandidate(Clock clock,Path python,Path script,Path model) { this.clock=clock;this.python=python;this.script=script;this.model=model; }
    @Override public List<Hit> search(List<PublishedVersion> eligible,String query,Instant deadline) {
        if(eligible.isEmpty())return List.of();
        var keys=new ArrayList<ChunkKey>(); var missing=new ArrayList<ChunkKey>();var texts=new ArrayList<String>();
        for(var v:eligible) for(int start=0;start<v.content().length();start+=400) {
            int end=Math.min(v.content().length(),start+480);
            var key=new ChunkKey(v.metadata().reference(),start,end); keys.add(key);
            if(!index.containsKey(key)){missing.add(key);texts.add(v.content().substring(start,end));}
            if(end==v.content().length())break;
        }
        // Scope applied before cache lookup/query/model invocation. Old cache residue is never scored.
        if(keys.size()>1024)throw new RetrievalFailure(false);
        texts.add(query);
        long buildStart=System.nanoTime();JsonNode output=encode(texts,deadline);
        var vectors=output.path("vectors");
        if(vectors.size()!=texts.size())throw new RetrievalFailure(false);
        long cacheStarted=System.nanoTime();
        for(int i=0;i<missing.size();i++) {
            if(index.size()>=1024)index.remove(index.keySet().iterator().next());
            index.put(missing.get(i),vector(vectors.get(i)));
        }
        double cacheWriteMillis=(System.nanoTime()-cacheStarted)/1e6;
        double[] wanted=vector(vectors.get(vectors.size()-1));
        var scores=new HashMap<VersionReference,Double>();
        for(var key:keys) {
            double[] document=index.get(key); if(document==null)throw new RetrievalFailure(false);
            double score=0;for(int i=0;i<384;i++)score+=document[i]*wanted[i];
            scores.merge(key.reference(),Math.max(-1,Math.min(1,score)),Math::max);
        }
        var observation=new LinkedHashMap<String,Object>();
        for(String name:List.of("model","revision","loadMillis","inferenceMillis","cpuSeconds","rssBytes","peakWorkingSetBytes","tokens","invocations","externalCalls","cost","versions"))
            observation.put(name,json.convertValue(output.path(name),Object.class));
        observation.put("indexAndQueryMillis",(System.nanoTime()-buildStart)/1e6);
        observation.put("newDocumentEmbeddings",missing.size());observation.put("queryEmbeddings",1);
        observation.put("cacheWriteMillis",cacheWriteMillis);
        double documentMillis=0;for(int i=0;i<missing.size();i++)documentMillis+=output.path("perTextMillis").get(i).asDouble();
        observation.put("documentEmbeddingMillis",documentMillis);
        observation.put("queryEmbeddingMillis",output.path("perTextMillis").get(missing.size()).asDouble());
        observation.put("scoredChunkCount",keys.size());observation.put("indexBytes",index.size()*384L*8);
        observation.put("documentDisclosure",eligible.stream().map(v->v.metadata().reference()).toList());
        observations.add(observation);
        return scores.entrySet().stream().filter(e->e.getValue()>=0.35)
                .sorted(Map.Entry.<VersionReference,Double>comparingByValue().reversed().thenComparing(e->e.getKey().document().documentId()))
                .map(e->new Hit(e.getKey(),e.getValue())).toList();
    }
    private JsonNode encode(List<String> texts,Instant deadline) {
        Process process=null;
        var reader=Executors.newVirtualThreadPerTaskExecutor();
        try {
            process=new ProcessBuilder(python.toAbsolutePath().toString(),script.toAbsolutePath().toString(),"encode",model.toAbsolutePath().toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process child=process;
            var reading=reader.submit(()->child.getInputStream().readNBytes(4_194_305));
            try(var stdin=process.getOutputStream()){stdin.write(json.writeValueAsBytes(Map.of("texts",texts)));}
            long remaining=Duration.between(clock.instant(),deadline).toMillis();
            if(remaining<=0 || !process.waitFor(Math.min(10_000,remaining),TimeUnit.MILLISECONDS))throw new RetrievalFailure(true);
            byte[] bytes=reading.get(1,TimeUnit.SECONDS);
            if(process.exitValue()!=0 || bytes.length>4_194_304)throw new RetrievalFailure(false);
            return json.readTree(bytes);
        } catch(RetrievalFailure safe){throw safe;}
        catch(Exception ignored){if(ignored instanceof InterruptedException)Thread.currentThread().interrupt();throw new RetrievalFailure(false);}
        finally {
            if(process!=null && process.isAlive()) {
                process.destroyForcibly();
                // The handler owns permits until the actual provider process has exited.
                boolean interrupted=false;
                for(;;)try{process.waitFor();break;}catch(InterruptedException e){interrupted=true;}
                if(interrupted)Thread.currentThread().interrupt();
            }
            reader.close();
        }
    }
    private static double[] vector(JsonNode value) {
        if(value.size()!=384)throw new RetrievalFailure(false);
        double[] result=new double[384];double norm=0;
        for(int i=0;i<384;i++){result[i]=value.get(i).asDouble(Double.NaN);if(!Double.isFinite(result[i]))throw new RetrievalFailure(false);norm+=result[i]*result[i];}
        if(Math.abs(norm-1)>0.001)throw new RetrievalFailure(false);return result;
    }
}
