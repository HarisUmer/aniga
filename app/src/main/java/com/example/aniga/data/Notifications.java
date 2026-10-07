package com.example.aniga.data;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.example.aniga.R;
import com.example.aniga.data.model.LibraryUpdate;

import java.util.ArrayList;
import java.util.List;

/**
 * Posts a single "new chapters are out" notification for shelved titles whose source now lists chapters
 * past what the user last saw. Each (title, latest chapter) is remembered so the same release never pings twice.
 */
public final class Notifications {

    private static final String CHANNEL = "updates";
    private static final int NOTIF_ID = 4201;
    private static final String PREFS = "chapter_updates";

    private Notifications() {
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    context.getString(R.string.update_notif_title), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(context.getString(R.string.library_updates));
            NotificationManager mgr = context.getSystemService(NotificationManager.class);
            if (mgr != null) {
                mgr.createNotificationChannel(channel);
            }
        }
    }

    /** Notifies about updates the user hasn't been told about yet; returns the count that was fresh. */
    public static int notifyUpdates(Context context, List<LibraryUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        List<LibraryUpdate> fresh = new ArrayList<>();
        for (LibraryUpdate u : updates) {
            if (u.media == null) {
                continue;
            }
            String key = "m" + u.media.id;
            float lastNotified = prefs.getFloat(key, 0f);
            if (u.latestChapter > lastNotified) {
                fresh.add(u);
                editor.putFloat(key, (float) u.latestChapter);
            }
        }
        editor.apply();
        if (fresh.isEmpty()) {
            return 0;
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= 33) {
            return fresh.size();
        }
        ensureChannel(context);
        String text = fresh.size() == 1
                ? context.getString(R.string.update_notif_one, fresh.get(0).media.title, fresh.get(0).newCount)
                : context.getString(R.string.update_notif_many, fresh.size());
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.update_notif_title))
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, builder.build());
        } catch (SecurityException ignored) {
            // Notifications denied: the in-app updates rail still shows the same titles.
        }
        return fresh.size();
    }
}
