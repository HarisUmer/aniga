package com.aniga.backend.mangadex;

import java.util.List;
import java.util.Map;

public final class Json {

    private Json() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public static String str(Object o) {
        return o == null ? null : o.toString();
    }

    public static Integer integer(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    /** MangaDex localized strings: prefer English, then any available language. */
    public static String localized(Object o) {
        Map<String, Object> m = map(o);
        if (m.containsKey("en")) {
            return str(m.get("en"));
        }
        return m.values().stream().findFirst().map(Json::str).orElse(null);
    }
}
