package com.example.aniga.ui.people;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.aniga.R;
import com.example.aniga.data.model.ChatMessage;
import com.example.aniga.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/** Messages in a room. Your own messages lean right in the hanko tint; everyone else leans left. */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Holder> {

    private final long me;
    private final List<ChatMessage> items = new ArrayList<>();

    public ChatAdapter(long me) {
        this.me = me;
    }

    public void submit(List<ChatMessage> messages) {
        items.clear();
        if (messages != null) {
            items.addAll(messages);
        }
        notifyDataSetChanged();
    }

    public int size() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout bubble;
        final TextView sender;
        final TextView body;

        Holder(LinearLayout root, LinearLayout bubble, TextView sender, TextView body) {
            super(root);
            this.bubble = bubble;
            this.sender = sender;
            this.body = body;
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context c = parent.getContext();
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int vpad = (int) Ui.dp(c, 4);
        root.setPadding(0, vpad, 0, vpad);

        LinearLayout bubble = new LinearLayout(c);
        bubble.setOrientation(LinearLayout.VERTICAL);
        int bp = (int) Ui.dp(c, 10);
        bubble.setPadding(bp, (int) Ui.dp(c, 7), bp, (int) Ui.dp(c, 7));

        TextView sender = new TextView(c);
        sender.setTextAppearance(R.style.TextAppearance_Aniga_Meta);

        TextView body = new TextView(c);
        body.setTextAppearance(R.style.TextAppearance_Aniga_Body);
        body.setTextColor(Ui.color(c, R.color.paper_100));

        bubble.addView(sender);
        bubble.addView(body);
        root.addView(bubble, new LinearLayout.LayoutParams(
                (int) (parent.getWidth() * 0.82f), ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Holder(root, bubble, sender, body);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ChatMessage m = items.get(position);
        boolean mine = m.senderId != null && m.senderId == me;
        Context c = h.itemView.getContext();
        h.sender.setText(m.sender == null ? "—" : m.sender);
        h.body.setText(m.body);
        h.bubble.setBackgroundColor(Ui.withAlpha(
                Ui.color(c, mine ? R.color.hanko : R.color.ink_700), mine ? 0.28f : 1f));
        LinearLayout root = (LinearLayout) h.itemView;
        root.setGravity(mine ? Gravity.END : Gravity.START);
        h.sender.setTextColor(Ui.color(c, mine ? R.color.hanko : R.color.paper_300));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
