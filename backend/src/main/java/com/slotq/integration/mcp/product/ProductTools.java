package com.slotq.integration.mcp.product;

import com.slotq.auth.access.*;
import com.slotq.mcp.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.*;
import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Explicit Product mapping; HTTP DTOs and errors are returned without new domain interpretation. */
public final class ProductTools implements McpRegistrations {
    private final ProductHttpBinding binding;
    private final ProductHttpClient client;
    private final HoldApprovals approvals;
    public ProductTools(ProductHttpBinding binding,ProductHttpClient client,HoldApprovals approvals){this.binding=binding;this.client=client;this.approvals=approvals;}
    @Override public List<ToolDefinition> tools(){return List.of(
        tool("reservation.get",AccessProfile.CUSTOMER,AccessAction.RESERVATION_READ,
            object(Map.of("reservationId",uuid()),List.of("reservationId")),output(reservation()),this::get),
        tool("reservation.hold",AccessProfile.CUSTOMER,AccessAction.RESERVATION_WRITE,
            object(Map.of("intentId",uuid(),"confirmationId",uuid(),"slotInventoryId",uuid(),"partySize",integer(1,Integer.MAX_VALUE),
                "idempotencyKey",Map.of("type","string","minLength",1,"maxLength",255,"pattern","^[!-~]+$")),
                List.of("intentId","confirmationId","slotInventoryId","partySize","idempotencyKey")),output(reservation()),this::hold),
        tool("management.reservations.list",AccessProfile.MANAGEMENT,AccessAction.MANAGEMENT_READ,
            object(Map.of("date",Map.of("type","string","minLength",10,"maxLength",10,"format","date"),"status",state()),List.of("date")),
            output(Map.of("type","array","maxItems",4096,"items",managementReservation())),this::list),
        tool("management.event-observations.list", AccessProfile.MANAGEMENT, AccessAction.EVENT_OBSERVATION_READ,
            object(Map.of("from", timestamp(), "to", timestamp(), "limit", integer(1, 100)), List.of("from", "to")),
            output(observations()), this::observations));}
    private ToolOutcome get(RequestContext context,Map<String,Object> input) {
        UUID id=id(input,"reservationId");
        return result(client.exchange(binding.prepare(context,ProductOperation.RESERVATION_GET,id),null,null),null,null,id);
    }
    private ToolOutcome list(RequestContext context,Map<String,Object> input) {
        String date;
        try {date=LocalDate.parse((String)input.get("date")).toString();}
        catch(DateTimeException failure){return rejected(null,null,McpFailure.Reason.VALIDATION);}
        String query="date="+date+(input.containsKey("status")?"&status="+input.get("status"):"");
        return result(client.exchange(binding.prepare(context,ProductOperation.MANAGEMENT_LIST,null),query,null),null,null,null);
    }
    private ToolOutcome observations(RequestContext context, Map<String, Object> input) {
        Instant from;
        Instant to;
        try {
            from = Instant.parse((String) input.get("from"));
            to = Instant.parse((String) input.get("to"));
        } catch (DateTimeException failure) {
            return rejected(null, null, McpFailure.Reason.VALIDATION);
        }
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            return rejected(null, null, McpFailure.Reason.VALIDATION);
        }
        String query = "from=" + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8)
            + "&to=" + URLEncoder.encode(to.toString(), StandardCharsets.UTF_8)
            + (input.containsKey("limit") ? "&limit=" + ((Number) input.get("limit")).intValue() : "");
        return result(client.exchange(binding.prepare(context, ProductOperation.EVENT_OBSERVATIONS_LIST, null),
            query, null), null, null, null);
    }
    private ToolOutcome hold(RequestContext context,Map<String,Object> input) {
        UUID intent=id(input,"intentId"),confirmation=id(input,"confirmationId"),slot=id(input,"slotInventoryId");
        String key=(String)input.get("idempotencyKey");int party=((Number)input.get("partySize")).intValue();
        HoldApprovals.Attempt attempt;
        try {attempt=approvals.dispatch(context,intent,confirmation,slot,party,key);}
        catch(McpFailure failure){return rejected(intent,confirmation,failure.reason());}
        catch(AccessFailure failure){return rejected(intent,confirmation,failure.reason()==AccessFailure.Reason.UNAVAILABLE?McpFailure.Reason.UNAVAILABLE:McpFailure.Reason.FORBIDDEN);}
        ProductHttpBinding.Prepared prepared;
        try {prepared=binding.prepareHold(context,slot,party,key);}
        catch(RuntimeException failure){return rejected(intent,confirmation,McpFailure.Reason.UNAVAILABLE);}
        ProductHttpClient.Result response=client.exchange(prepared,null,
            Map.of("slotInventoryId",slot,"partySize",party,"idempotencyKey",key));
        UUID known=response.knownTarget()==null?attempt.knownTarget():response.knownTarget();
        try {approvals.rememberTarget(intent,known);}
        catch(RuntimeException failure){response=new ProductHttpClient.Result(null,null,known,response.requestId(),true);}
        return result(response,intent,confirmation,known);
    }
    private static ToolOutcome rejected(UUID intent,UUID confirmation,McpFailure.Reason reason){
        return new ToolOutcome(Map.of("outcome","not_dispatched","category",reason.name().toLowerCase(Locale.ROOT)),false,null,null,
            confirmation,intent,null,null,reason,McpAudit.TimeoutLayer.NONE);
    }
    private static ToolOutcome result(ProductHttpClient.Result result,UUID intent,UUID confirmation,UUID known) {
        boolean unknown=result.unknown() || result.status()>=500 || (result.status()>=300 && result.status()<400);
        boolean success=!unknown && result.status()>=200 && result.status()<300;
        Map<String,Object> content=new LinkedHashMap<>();content.put("outcome",unknown?"outcome_unknown":success?"succeeded":"rejected");
        if(result.status()!=null)content.put("productStatus",result.status());
        if(result.data()!=null)content.put(success?"data":"problem",result.data());
        return new ToolOutcome(content,true,known==null?result.knownTarget():known,result.requestId(),confirmation,intent,null,null,
            unknown?McpFailure.Reason.UNKNOWN:success?null:McpFailure.Reason.VALIDATION,
            unknown?McpAudit.TimeoutLayer.PRODUCT_RESPONSE:McpAudit.TimeoutLayer.NONE);
    }
    private static ToolDefinition tool(String name,AccessProfile profile,AccessAction action,Map<String,Object> input,Map<String,Object> output,ToolDefinition.Handler handler){
        String description = name.equals("management.event-observations.list")
            ? "매장의 운영 관측을 projected_at 기준 [from, to)로 조회합니다. 최대 31일, 기본 50개, 최대 100개입니다. "
                + "occurredAt은 발생 시각입니다. null, 빈 결과, 누락된 receipt, pending, 지연된 projection, truncated는 정상 또는 전체 처리 완료의 근거가 아닙니다."
            : "Authenticated Product " + name;
        int resultBytes = name.equals("management.event-observations.list")
            ? ToolDefinition.MAX_RESULT_BYTES : ToolDefinition.DEFAULT_RESULT_BYTES;
        return new ToolDefinition(new McpSchema.Tool(name,null,description,input,output,null,null,null),profile,action,
            ToolDefinition.Resource.PRODUCT,handler,resultBytes);
    }
    private static UUID id(Map<String,Object> input,String field){
        try{return UUID.fromString((String)input.get(field));}catch(IllegalArgumentException failure){throw new McpFailure(McpFailure.Reason.VALIDATION);}
    }
    static Map<String,Object> uuid(){return Map.of("type","string","minLength",36,"maxLength",36,"format","uuid",
        "pattern","^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$");}
    static Map<String,Object> string(int max){return Map.of("type","string","maxLength",max);}
    static Map<String,Object> integer(long min,long max){return Map.of("type","integer","minimum",min,"maximum",max);}
    static Map<String,Object> state(){return Map.of("type","string","maxLength",16,"enum",List.of("HELD","CONFIRMED","CANCELLED","CHECKED_IN","NO_SHOW","COMPLETED","EXPIRED"));}
    static Map<String,Object> object(Map<String,Object> fields,List<String> required){return Map.of("type","object","additionalProperties",false,"properties",fields,"required",required);}
    private static Map<String, Object> timestamp() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 40);
    }
    private static Map<String, Object> nullable(Map<String, Object> schema) {
        return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
    }
    private static Map<String, Object> observations() {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String name : List.of("eventId", "observerRegistrationId", "aggregateId", "slotInventoryId")) {
            fields.put(name, uuid());
        }
        fields.put("eventType", string(100));
        fields.put("schemaVersion", integer(1, Integer.MAX_VALUE));
        fields.put("aggregateType", string(100));
        for (String name : List.of("occurredAt", "projectedAt")) {
            fields.put(name, timestamp());
        }
        for (String name : List.of("fromState", "toState", "waitlistDeliveryState", "waitlistReceiptOutcome")) {
            fields.put(name, nullable(string(64)));
        }
        fields.put("durableIntakeObservedAt", nullable(timestamp()));
        for (String name : List.of("waitlistRegistrationId", "waitlistOfferId")) {
            fields.put(name, nullable(uuid()));
        }
        return object(Map.of("items", Map.of("type", "array", "maxItems", 100,
            "items", object(fields, List.copyOf(fields.keySet()))), "truncated", Map.of("type", "boolean")),
            List.of("items", "truncated"));
    }
    private static Map<String,Object> reservation(){
        Map<String,Object> fields=new LinkedHashMap<>();
        for(String name:List.of("id","venueId","resourceId","slotInventoryId"))fields.put(name,uuid());
        fields.put("state",state());for(String name:List.of("partySize","allocationQuantity"))fields.put(name,integer(1,Integer.MAX_VALUE));
        for(String name:List.of("startsAt","endsAt","expiresAt","cancelAllowedUntil","noShowEligibleAt"))fields.put(name,string(40));
        fields.put("appliedPolicyVersion",integer(1,Long.MAX_VALUE));return object(fields,List.copyOf(fields.keySet()));
    }
    private static Map<String,Object> managementReservation(){
        Map<String,Object> fields=new LinkedHashMap<>();for(String name:List.of("id","resourceId","slotInventoryId","customerReference"))fields.put(name,uuid());
        fields.put("state",state());fields.put("partySize",integer(1,Integer.MAX_VALUE));
        for(String name:List.of("startsAt","endsAt","expiresAt"))fields.put(name,string(40));
        fields.put("allowedActions",Map.of("type","array","maxItems",4,"items",string(16)));return object(fields,List.copyOf(fields.keySet()));
    }
    private static Map<String,Object> output(Map<String,Object> data){
        Map<String,Object> problem=new LinkedHashMap<>();for(String name:List.of("type","title","detail","instance","code"))problem.put(name,string(512));
        problem.put("status",integer(100,599));problem.put("fieldErrors",object(Map.of("slotInventoryId",string(512),"partySize",string(512),"Idempotency-Key",string(512),"date",string(512),
            "from", string(512), "to", string(512), "limit", string(512)),List.of()));
        return object(Map.of("outcome",Map.of("type","string","maxLength",32,"enum",List.of("succeeded","rejected","outcome_unknown","not_dispatched","unknown")),
            "requestId",uuid(),"category",string(32),"productStatus",integer(100,599),"data",data,
            "problem",object(problem,List.of("type","title","status","detail","instance","code"))),List.of("outcome"));
    }
}
