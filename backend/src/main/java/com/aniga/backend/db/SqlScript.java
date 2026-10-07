package com.aniga.backend.db;

import java.util.ArrayList;
import java.util.List;

/** Splits a Postgres script on semicolons while leaving dollar-quoted function bodies intact. */
public final class SqlScript {

    private SqlScript() {
    }

    public static List<String> statements(String sql) {
        StringBuilder current = new StringBuilder();
        boolean dollar = false;
        List<String> out = new ArrayList<>();
        String body = sql.replace("\r", "");
        for (int i = 0; i < body.length(); i++) {
            if (body.startsWith("$$", i)) {
                dollar = !dollar;
                current.append("$$");
                i++;
                continue;
            }
            char c = body.charAt(i);
            if (c == ';' && !dollar) {
                add(out, current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        add(out, current.toString());
        return out;
    }

    private static void add(List<String> out, String raw) {
        StringBuilder kept = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            if (line.trim().startsWith("--")) continue;
            kept.append(line).append('\n');
        }
        String statement = kept.toString().trim();
        if (!statement.isEmpty()) out.add(statement);
    }
}
