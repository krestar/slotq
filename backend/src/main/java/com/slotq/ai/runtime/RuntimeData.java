package com.slotq.ai.runtime;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

final class RuntimeData {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @SuppressWarnings("unchecked") static Map<String,Object> freeze(Map<String,Object> map) {
        Map<String,Object> result=(Map<String,Object>)copy(map,0);
        if(JSON.writeValueAsBytes(result).length>65536)throw new IllegalArgumentException("Runtime data bound");
        return result;
    }
    private static Object copy(Object value,int depth) {
        if(depth>16)throw new IllegalArgumentException("Runtime data depth");
        if(value instanceof Map<?,?> map) {
            if(map.size()>64)throw new IllegalArgumentException("Runtime field bound");
            Map<String,Object> result=new LinkedHashMap<>();
            map.forEach((key,child)->{if(!(key instanceof String s) || s.length()>100)throw new IllegalArgumentException("Runtime key bound");result.put(s,copy(child,depth+1));});
            return Collections.unmodifiableMap(result);
        }
        if(value instanceof List<?> list) {
            if(list.size()>4096)throw new IllegalArgumentException("Runtime list bound");
            return Collections.unmodifiableList(list.stream().map(child->copy(child,depth+1)).toList());
        }
        if(value==null || value instanceof Boolean || value instanceof Number || value instanceof String)return value;
        throw new IllegalArgumentException("Unsupported Runtime data");
    }
    private RuntimeData() { }
}
