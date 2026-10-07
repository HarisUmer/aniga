package com.aniga.backend.chat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** @mentions are a username after whitespace. An email address is not a mention. */
public final class Mentions {

    private static final Pattern AT = Pattern.compile("(^|\\s)@([A-Za-z0-9_]{3,32})");

    private Mentions() {
    }

    public static List<String> usernames(String body) {
        if (body == null || body.isEmpty()) return List.of();
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = AT.matcher(body);
        while (matcher.find()) names.add(matcher.group(2).toLowerCase(Locale.ROOT));
        return List.copyOf(names);
    }
}
