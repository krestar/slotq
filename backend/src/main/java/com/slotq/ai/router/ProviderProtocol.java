package com.slotq.ai.router;

import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;

/** Structured proposals remain untrusted. No method here can invoke a tool. */
public final class ProviderProtocol {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static final String REVISION = "m7-proposal-v2";
    public static final String SCHEMA_JSON = """
        {"type":"object","additionalProperties":false,"properties":{
          "disposition":{"type":"string","enum":["ANSWER","CLARIFY","INSUFFICIENT","REJECT","PROPOSE_HOLD"]},
          "tool":{"type":"string","enum":["none","reservation.hold"]},
          "target":{"type":"string","maxLength":64},"partySize":{"type":"integer","minimum":0,"maximum":20},
          "outcome":{"type":"string","enum":["NOT_APPLICABLE","HELD","REJECTED","UNKNOWN"]},
          "sourceRefs":{"type":"array","maxItems":8,"uniqueItems":true,"items":{"type":"string","maxLength":100}},
          "claimRefs":{"type":"array","maxItems":8,"uniqueItems":true,"items":{"type":"string","maxLength":100}},
          "answer":{"type":"string","minLength":1,"maxLength":700}},
         "required":["disposition","tool","target","partySize","outcome","sourceRefs","claimRefs","answer"]}
        """;
    public record Request(String prompt, int maximumOutputTokens, Set<String> knownSources, Set<String> knownClaims) {
        public Request { knownSources=references(knownSources); knownClaims=references(knownClaims); }
        public Request(String prompt,int maximumOutputTokens) { this(prompt,maximumOutputTokens,Set.of(),Set.of()); }
        @Override public String toString() { return "ProviderRequest[context withheld]"; }
    }
    public record Output(String disposition, String tool, String target, int partySize, String outcome,
                         List<String> sourceRefs, List<String> claimRefs, String answer) {
        public Output { sourceRefs = List.copyOf(sourceRefs); claimRefs = List.copyOf(claimRefs); }
        @Override public String toString() { return "ProviderOutput[content withheld]"; }
    }
    public static Map<String, Object> schema() {
        @SuppressWarnings("unchecked") Map<String,Object> schema = JSON.readValue(SCHEMA_JSON, Map.class);
        return schema;
    }
    public static Map<String,Object> schema(Request request) {
        Map<String,Object> schema=schema();
        @SuppressWarnings("unchecked") Map<String,Object> properties=(Map<String,Object>)schema.get("properties");
        for(String name:List.of("sourceRefs","claimRefs")) {
            Set<String> references=name.equals("sourceRefs")?request.knownSources():request.knownClaims();
            properties.put(name,new TreeMap<>(Map.of("type","array","maxItems",references.isEmpty()?0:8,"uniqueItems",true,
                "items",new TreeMap<>(Map.of("type","string","maxLength",100,"enum",references.isEmpty()?List.of("__none__"):references.stream().sorted().toList())))));
        }
        return schema;
    }
    public static Output parse(String text,Request request) {
        Output output=parse(text);
        if(!request.knownSources().containsAll(output.sourceRefs()) || !request.knownClaims().containsAll(output.claimRefs()))
            throw new IllegalArgumentException("Invalid structured provider output");
        return output;
    }
    public static Output parse(String text) {
        try {
            if (text == null || text.length() > 8192) throw new IllegalArgumentException();
            @SuppressWarnings("unchecked") Map<String,Object> data = JSON.readValue(text, Map.class);
            if (!new DefaultJsonSchemaValidator().validate(schema(), data).valid()) throw new IllegalArgumentException();
            String disposition = (String)data.get("disposition"); String tool = (String)data.get("tool");
            int party = ((Number)data.get("partySize")).intValue(); String target = (String)data.get("target");
            if ("PROPOSE_HOLD".equals(disposition)) {
                if (!"reservation.hold".equals(tool) || party < 1 || target.isBlank()
                        || !"NOT_APPLICABLE".equals(data.get("outcome"))) throw new IllegalArgumentException();
            } else if (!"none".equals(tool) || party != 0 || !target.isEmpty()) throw new IllegalArgumentException();
            return new Output(disposition, tool, target, party, (String)data.get("outcome"), strings(data.get("sourceRefs")),
                strings(data.get("claimRefs")), (String)data.get("answer"));
        } catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid structured provider output"); }
    }
    private static List<String> strings(Object value) { return ((List<?>)value).stream().map(String.class::cast).toList(); }
    private static Set<String> references(Set<String> refs) {
        if(refs==null || refs.size()>8 || refs.stream().anyMatch(s->s==null || !s.matches("[A-Za-z0-9:._-]{1,100}")))
            throw new IllegalArgumentException("Invalid bounded context reference");
        return Set.copyOf(refs);
    }
    private ProviderProtocol() { }
}
