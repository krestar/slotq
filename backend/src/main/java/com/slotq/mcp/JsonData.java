package com.slotq.mcp;

import java.util.*;

final class JsonData {
    private JsonData() { }
    @SuppressWarnings("unchecked") static Map<String,Object> freeze(Map<String,Object> map) {
        return (Map<String,Object>) immutable(map);
    }
    private static Object immutable(Object value) {
        if(value instanceof Map<?,?> map) {
            Map<String,Object> copy=new LinkedHashMap<>();
            map.forEach((key,child)->copy.put((String)key,immutable(child)));
            return Collections.unmodifiableMap(copy);
        }
        if(value instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(JsonData::immutable).toList());
        return value;
    }
}
