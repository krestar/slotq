package com.slotq.mcp;

import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.util.*;

/** Frozen before endpoint activation; finite server schemas only, no remote references/cache growth. */
public final class ToolRegistry {
    private final Map<String, Registered> tools;
    public ToolRegistry(List<ToolDefinition> registrations) {
        if (registrations.size() > 32) throw new IllegalArgumentException("Too many tools");
        Map<String, Registered> map = new TreeMap<>();
        for (ToolDefinition tool : registrations) {
            Objects.requireNonNull(tool.resource()); Objects.requireNonNull(tool.handler());
            if (!tool.wire().name().matches("[a-z][a-z0-9_.]{0,79}")) throw new IllegalArgumentException("Invalid tool name");
            requireBounded(tool.wire().inputSchema()); requireBounded(tool.wire().outputSchema());
            // Separate validator instances avoid the SDK's hash-key collision across distinct schemas.
            var input = new DefaultJsonSchemaValidator(); var output = new DefaultJsonSchemaValidator();
            input.assertConforms("input", tool.wire().inputSchema()); output.assertConforms("output", tool.wire().outputSchema());
            if (map.putIfAbsent(tool.wire().name(), new Registered(tool, input, output)) != null)
                throw new IllegalArgumentException("Duplicate tool");
        }
        tools = Collections.unmodifiableMap(map);
    }
    public List<ToolDefinition> all() { return tools.values().stream().map(Registered::definition).toList(); }
    public ToolDefinition require(String name) {
        Registered tool = tools.get(name);
        if (tool == null) throw new McpFailure(McpFailure.Reason.UNKNOWN_TOOL);
        return tool.definition();
    }
    public void validateInput(ToolDefinition tool, Map<String, Object> value) {
        if (!tools.get(tool.wire().name()).input().validate(tool.wire().inputSchema(), value).valid())
            throw new McpFailure(McpFailure.Reason.VALIDATION);
    }
    public void validateOutput(ToolDefinition tool, Map<String, Object> value) {
        if (!tools.get(tool.wire().name()).output().validate(tool.wire().outputSchema(), value).valid())
            throw new McpFailure(McpFailure.Reason.UNKNOWN);
    }
    private static void requireBounded(Map<String, Object> schema) {
        if (schema == null || !"object".equals(schema.get("type"))) throw new IllegalArgumentException("Object schema required");
        inspect(schema, 0);
    }
    private static void inspect(Object value, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Schema nesting limit");
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("$ref") || map.containsKey("$dynamicRef") || map.containsKey("$id"))
                throw new IllegalArgumentException("Schema references unsupported");
            if (map.containsKey("$schema") && !io.modelcontextprotocol.spec.McpSchema.JSON_SCHEMA_DIALECT_2020_12.equals(map.get("$schema")))
                throw new IllegalArgumentException("Only JSON Schema 2020-12 supported");
            if ("object".equals(map.get("type")) && !Boolean.FALSE.equals(map.get("additionalProperties")))
                throw new IllegalArgumentException("Closed object schema required");
            if ("string".equals(map.get("type")) && !positiveBound(map.get("maxLength")))
                throw new IllegalArgumentException("String bound required");
            if ("array".equals(map.get("type")) && !positiveBound(map.get("maxItems")))
                throw new IllegalArgumentException("Array bound required");
            for (Object child : map.values()) inspect(child, depth + 1);
        } else if (value instanceof List<?> list) for (Object child : list) inspect(child, depth + 1);
    }
    private static boolean positiveBound(Object value) { return value instanceof Number n && n.longValue() > 0 && n.longValue() <= 65536; }
    private record Registered(ToolDefinition definition, DefaultJsonSchemaValidator input, DefaultJsonSchemaValidator output) { }
}
