package com.example.aniga.data;

import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.aniga.data.model.LibraryState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The signed-in user's shelf as mediaId → (status, progress), so every cover tile can show how far you are
 * without a request per tile. Loaded once per user and patched locally whenever the shelf is saved;
 * {@link #version()} ticks on every change so adapters can rebind.
 */
public final class Progress {

    public static final class State {
        public final String status;
        public final int progress;

        State(String status, int progress) {
            this.status = status;
            this.progress = progress;
        }
    }

    private static final Map<Long, State> byMedia = new HashMap<>();
    private static final MutableLiveData<Integer> version = new MutableLiveData<>(0);
    private static long loadedFor = -1;

    private Progress() {
    }

    public static LiveData<Integer> version() {
        return version;
    }

    @Nullable
    public static State get(long mediaId) {
        return byMedia.get(mediaId);
    }

    public static void load(LifecycleOwner owner, long userId) {
        if (loadedFor == userId) return;
        Api.load(owner, Api.get().library(userId, null, null), entries -> {
            loadedFor = userId;
            fill(entries);
        }, e -> {
        });
    }

    public static void reload(LifecycleOwner owner, long userId) {
        loadedFor = -1;
        load(owner, userId);
    }

    private static void fill(List<LibraryState.Entry> entries) {
        byMedia.clear();
        for (LibraryState.Entry e : entries) {
            if (e.media != null) byMedia.put(e.media.id, new State(e.status, e.progress));
        }
        bump();
    }

    public static void put(LibraryState s) {
        if (s == null) return;
        if (s.status == null || "NONE".equals(s.status)) byMedia.remove(s.mediaId);
        else byMedia.put(s.mediaId, new State(s.status, s.progress));
        bump();
    }

    public static void put(long mediaId, String status, int progress) {
        if (status == null || "NONE".equals(status)) byMedia.remove(mediaId);
        else byMedia.put(mediaId, new State(status, progress));
        bump();
    }

    private static void bump() {
        Integer v = version.getValue();
        version.setValue(v == null ? 1 : v + 1);
    }
}
