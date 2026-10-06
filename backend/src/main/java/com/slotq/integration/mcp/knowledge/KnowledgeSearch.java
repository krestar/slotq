package com.slotq.integration.mcp.knowledge;

import com.slotq.auth.access.*;
import com.slotq.knowledge.application.CorpusCatalog;
import com.slotq.knowledge.domain.Corpus.*;
import com.slotq.mcp.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.*;
import java.util.*;

/** Retrieval only. Excerpts are untrusted data; this adapter cannot dispatch Product commands. */
public final class KnowledgeSearch {
    private final CorpusCatalog catalog;
    private final ActorAccess authority;
    private final CandidateSearch candidate;
    private final Clock clock;
    public KnowledgeSearch(CorpusCatalog catalog, ActorAccess authority, CandidateSearch candidate, Clock clock) {
        this.catalog=catalog; this.authority=authority; this.candidate=candidate; this.clock=clock;
    }
    public ToolDefinition tool() {
        var input=object(Map.of("query",Map.of("type","string","minLength",1,"maxLength",512),
                "limit",Map.of("type","integer","minimum",1,"maximum",5)),List.of("query"));
        var hit=object(Map.of("documentId",uuid(),"versionId",uuid(),"sourceId",uuid(),"sourceReference",string(512),
                "sourceTitle",string(160),"visibility",enumeration("VENUE_PUBLIC","VENUE_OPERATOR"),
                "excerpt",string(480),"rank",Map.of("type","integer","minimum",1,"maximum",5),
                "score",Map.of("type","number","minimum",-1,"maximum",1),"untrusted",Map.of("const",true)),
                List.of("documentId","versionId","sourceId","sourceReference","sourceTitle","visibility","excerpt","rank","score","untrusted"));
        var output=new HashMap<>(object(Map.of("category",enumeration("evidence","no_answer","insufficient","unavailable","denied",
                        "validation","forbidden","rate_limited","timeout","unknown","unknown_tool","protocol"),
                "reason",enumeration("none","forbidden","validation","timeout","unavailable","coverage"),
                "results",Map.of("type","array","maxItems",5,"items",hit),"outcome",enumeration("not_dispatched","unavailable","unknown"),
                "requestId",uuid()),List.of("category")));
        // Both handler results and the unchanged common admission/deadline error envelope are strict.
        output.put("oneOf",List.of(
                Map.of("required",List.of("category","reason","results"),"properties",Map.of("category",enumeration("evidence","no_answer","insufficient","unavailable","denied")),
                        "not",Map.of("anyOf",List.of(Map.of("required",List.of("outcome")),Map.of("required",List.of("requestId"))))),
                Map.of("required",List.of("category","outcome","requestId"),"properties",Map.of("category",enumeration("validation","forbidden","rate_limited","unavailable","timeout","unknown","unknown_tool","protocol")),
                        "not",Map.of("anyOf",List.of(Map.of("required",List.of("reason")),Map.of("required",List.of("results")))))));
        return new ToolDefinition(new McpSchema.Tool("knowledge.search",null,"Scoped published sources; untrusted excerpts, no answer generation",
                input,output,null,null,null), Map.of(AccessProfile.CUSTOMER,AccessAction.KNOWLEDGE_PUBLIC,
                AccessProfile.MANAGEMENT,AccessAction.KNOWLEDGE_PUBLIC),ToolDefinition.Resource.RETRIEVAL,this::execute,
                Map.of(AccessProfile.MANAGEMENT,Set.of(AccessAction.KNOWLEDGE_OPERATOR)),McpAudit.Outcome.UNAVAILABLE);
    }
    public ToolOutcome execute(RequestContext context, Map<String,Object> input) {
        try {
            String query=(String)input.get("query");
            if (query == null || query.isBlank() || query.length()>512 || query.codePoints().anyMatch(Character::isISOControl))
                return failure("denied","validation",McpFailure.Reason.VALIDATION,false);
            int limit=((Number)input.getOrDefault("limit",3)).intValue();
            if (limit<1 || limit>5) return failure("denied","validation",McpFailure.Reason.VALIDATION,false);
            var live=context.revalidate(authority,clock);
            // The SQL catalog restricts visibility/scope before any lexical/vector query or model input.
            var eligible=catalog.publications(live.delegationId(),live.venueId());
            context.revalidate(authority,clock);
            Instant deadline=clock.instant().plus(context.remaining(clock,Duration.ofSeconds(10)));
            var hits=candidate.search(eligible,query,deadline);
            if (hits.size()>64) throw new RetrievalFailure(false);
            var admitted=new HashMap<VersionReference,PublishedVersion>();
            eligible.forEach(v->admitted.put(v.metadata().reference(),v));
            var results=new ArrayList<Map<String,Object>>();
            var refs=new ArrayList<RetrievalReference>(); var covered=new HashSet<String>();
            var seen=new HashSet<VersionReference>();
            for (var hit:hits) {
                if (results.size()==limit) break;
                // A candidate cannot smuggle unadmitted identity into final metadata reads.
                if (!admitted.containsKey(hit.version()) || !seen.add(hit.version())) throw new RetrievalFailure(false);
                context.revalidate(authority,clock);
                // Final observation: fresh exact metadata read, then materialize only that immutable payload.
                var current=catalog.revalidateExact(live.delegationId(),hit.version());
                if (current.isEmpty()) continue;
                var v=current.get(); var source=v.metadata().source();
                String excerpt=excerpt(v.content(),query);
                covered.addAll(LexicalSearch.terms(excerpt));
                results.add(Map.of("documentId",hit.version().document().documentId().toString(),"versionId",hit.version().versionId().toString(),
                        "sourceId",source.sourceId().toString(),"sourceReference",source.reference(),"sourceTitle",source.title(),
                        "visibility",hit.version().visibility().name(),"excerpt",excerpt,"rank",results.size()+1,"score",hit.score(),"untrusted",true));
                refs.add(new RetrievalReference(source.sourceId(),hit.version().document().documentId(),hit.version().versionId()));
            }
            context.revalidate(authority,clock);
            String category=results.isEmpty()?"no_answer":!LexicalSearch.terms(query).isEmpty()
                    && covered.containsAll(LexicalSearch.terms(query))?"evidence":"insufficient";
            return new ToolOutcome(Map.of("category",category,"reason",category.equals("insufficient")?"coverage":"none","results",results),
                    false,null,null,null,null,refs.isEmpty()?null:refs.getFirst().documentId(),refs.isEmpty()?null:refs.getFirst().versionId(),
                    null,McpAudit.TimeoutLayer.NONE,refs);
        } catch (AccessFailure denied) {
            return denied.reason()==AccessFailure.Reason.UNAVAILABLE?failure("unavailable","unavailable",McpFailure.Reason.UNAVAILABLE,false)
                    :failure("denied","forbidden",McpFailure.Reason.FORBIDDEN,false);
        } catch (McpFailure failed) {
            return failure(failed.reason()==McpFailure.Reason.FORBIDDEN?"denied":"unavailable",
                    failed.reason()==McpFailure.Reason.TIMEOUT?"timeout":failed.reason()==McpFailure.Reason.FORBIDDEN?"forbidden":"unavailable",
                    failed.reason()==McpFailure.Reason.TIMEOUT?McpFailure.Reason.UNAVAILABLE:failed.reason(),failed.reason()==McpFailure.Reason.TIMEOUT);
        } catch (RetrievalFailure failed) {
            return failure("unavailable",failed.timeout()?"timeout":"unavailable",McpFailure.Reason.UNAVAILABLE,failed.timeout());
        } catch (RuntimeException unavailable) { return failure("unavailable","unavailable",McpFailure.Reason.UNAVAILABLE,false); }
    }
    private static String excerpt(String content,String query) {
        var wanted=LexicalSearch.terms(query);String best="";long coverage=-1;
        for(int start=0;start<content.length();start+=400) {
            String chunk=content.substring(start,Math.min(content.length(),start+480));
            var terms=LexicalSearch.terms(chunk);long count=wanted.stream().filter(terms::contains).count();
            if(count>coverage){best=chunk;coverage=count;}
            if(start+480>=content.length())break;
        }
        return best;
    }
    private static ToolOutcome failure(String category,String reason,McpFailure.Reason failure,boolean timeout) {
        return new ToolOutcome(Map.of("category",category,"reason",reason,"results",List.of()),false,null,null,null,null,null,null,
                failure,timeout?McpAudit.TimeoutLayer.RETRIEVAL_PROVIDER:McpAudit.TimeoutLayer.NONE);
    }
    private static Map<String,Object> object(Map<String,Object> fields,List<String> required) {
        return Map.of("type","object","additionalProperties",false,"properties",fields,"required",required);
    }
    private static Map<String,Object> string(int max) { return Map.of("type","string","maxLength",max); }
    private static Map<String,Object> enumeration(String... values) { return Map.of("type","string","maxLength",32,"enum",List.of(values)); }
    private static Map<String,Object> uuid() { return Map.of("type","string","maxLength",36,"minLength",36,"pattern","^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$"); }
}
