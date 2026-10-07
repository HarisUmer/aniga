package com.example.aniga.data;

import android.content.Context;

/**
 * The signed-in user and the backend session token. The token is minted by our backend after it
 * checks the password with Firebase; the app never holds a Firebase credential.
 */
public final class Session {

    private static final String PREFS = "aniga.session";
    private static final String KEY_USER = "user_id";
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_TOKEN = "session_token";
    private static final String KEY_READER_THEME = "reader_theme";
    private static final String KEY_LANG = "reading_lang";

    private Session() {
    }

    public static long userId(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_USER, 0L);
    }

    public static String userName(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER_NAME, "You");
    }

    public static String token(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null);
    }

    public static boolean isSignedIn(Context c) {
        String token = token(c);
        return token != null && !token.isEmpty() && userId(c) > 0;
    }

    public static void setUser(Context c, long id, String name) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_USER, id).putString(KEY_USER_NAME, name).apply();
    }

    public static void signIn(Context c, long id, String name, String token) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_USER, id).putString(KEY_USER_NAME, name).putString(KEY_TOKEN, token).apply();
    }

    public static void signOut(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_USER).remove(KEY_USER_NAME).remove(KEY_TOKEN).apply();
    }

    /** Chapter language for every reading source; codes as the catalog uses them (en, es, pt-BR, ja…). */
    public static String readingLang(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, "en");
    }

    public static void setReadingLang(Context c, String lang) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANG, lang).apply();
    }

    public static String readerTheme(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_READER_THEME, "NIGHT");
    }

    public static void setReaderTheme(Context c, String theme) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_READER_THEME, theme).apply();
    }
}
