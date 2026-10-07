package com.example.aniga.ui.shelf;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.aniga.R;
import com.example.aniga.data.model.LibraryState;
import com.example.aniga.data.model.Media;
import com.example.aniga.ui.Ui;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.function.Consumer;

/** The shelf editor in a bottom sheet, so For You / Library rows can be rated without leaving the list. */
public final class RateSheet {

    private RateSheet() {
    }

    public static void show(Context context, Media m, Consumer<LibraryState> onChange) {
        AppCompatActivity activity = activityOf(context);
        if (activity == null || activity.isFinishing() || m == null) {
            return;
        }
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) Ui.dp(activity, 20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Ui.color(activity, R.color.ink_800));

        TextView type = new TextView(activity);
        type.setTextAppearance(R.style.TextAppearance_Aniga_Kicker);
        String kind = m.mediaType == null ? "" : m.mediaType;
        type.setText(kind + (m.total() != null ? "  ·  " + m.total() + (m.isAnime() ? " episodes" : " chapters") : ""));
        type.setTextColor(Ui.typeColor(activity, m.mediaType));
        root.addView(type);
        TextView title = new TextView(activity);
        title.setTextAppearance(R.style.TextAppearance_Aniga_Headline);
        title.setText(m.title == null ? "" : m.title);
        root.addView(title);

        ShelfPanel panel = new ShelfPanel(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) Ui.dp(activity, 12);
        root.addView(panel, lp);
        panel.setOnChange(onChange);
        panel.bind(activity, m);

        dialog.setContentView(root);
        View sheet = (View) root.getParent();
        if (sheet != null) sheet.setBackgroundColor(Ui.color(activity, R.color.ink_800));
        dialog.show();
    }

    private static AppCompatActivity activityOf(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof AppCompatActivity) {
                return (AppCompatActivity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }
}
