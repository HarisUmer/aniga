package com.example.aniga.data;

import android.util.Log;

/** Logcat tag for the shelf. Messages name the step. They never include a token. */
public final class Trace {

    public static final String TAG = "Aniga";

    private Trace() {
    }

    public static void event(String where, String detail) {
        Log.i(TAG, where + " · " + detail);
    }

    public static void fail(String where, Throwable error) {
        String message = error == null ? null : error.getMessage();
        Log.w(TAG, where + " · " + (message == null || message.isBlank()
                ? (error == null ? "failed" : error.getClass().getSimpleName())
                : message));
    }
}
