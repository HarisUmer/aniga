package com.example.aniga.ui.people;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.aniga.R;
import com.example.aniga.data.model.ChatMessage;
import com.example.aniga.data.model.Media;
import com.example.aniga.data.model.Series;
import com.example.aniga.ui.Covers;
import com.example.aniga.ui.Ui;
import com.example.aniga.ui.detail.DetailActivity;

import java.util.ArrayList;
import java.util.List;

/** Messages in a room. Your own messages lean right in the hanko tint; everyone else leans left. */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Holder> {

    private final long me;
    private final java.util.function.Consumer<String> onSender;
    private final List<ChatMessage> items = new ArrayList<>();

    public ChatAdapter(long me, java.util.function.Consumer<String> onSender) {
        this.me = me;
        this.onSender = onSender;
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
        final LinearLayout shareCard;
        final ImageView shareCover;
        final TextView shareTitle;
        final TextView shareSub;

        Holder(LinearLayout root, LinearLayout bubble, TextView sender, TextView body,
               LinearLayout shareCard, ImageView shareCover, TextView shareTitle, TextView shareSub) {
            super(root);
            this.bubble = bubble;
            this.sender = sender;
            this.body = body;
            this.shareCard = shareCard;
            this.shareCover = shareCover;
            this.shareTitle = shareTitle;
            this.shareSub = shareSub;
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

        // Share card: cover thumbnail + title + "is reading" / "rated" line.
        LinearLayout shareCard = new LinearLayout(c);
        shareCard.setOrientation(LinearLayout.HORIZONTAL);
        shareCard.setGravity(Gravity.CENTER_VERTICAL);
        int cp = (int) Ui.dp(c, 6);
        shareCard.setPadding(cp, cp, cp, cp);
        shareCard.setBackgroundColor(Ui.withAlpha(Ui.color(c, R.color.ink_900), 0.5f));
        shareCard.setVisibility(View.GONE);

        ImageView shareCover = new ImageView(c);
        shareCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(
                (int) Ui.dp(c, 40), (int) Ui.dp(c, 56));
        coverLp.setMarginEnd((int) Ui.dp(c, 8));
        shareCard.addView(shareCover, coverLp);

        LinearLayout shareText = new LinearLayout(c);
        shareText.setOrientation(LinearLayout.VERTICAL);
        TextView shareTitle = new TextView(c);
        shareTitle.setTextAppearance(R.style.TextAppearance_Aniga_Body);
        shareTitle.setTextColor(Ui.color(c, R.color.paper_100));
        shareTitle.setMaxLines(2);
        TextView shareSub = new TextView(c);
        shareSub.setTextAppearance(R.style.TextAppearance_Aniga_Meta);
        shareText.addView(shareTitle);
        shareText.addView(shareSub);
        shareCard.addView(shareText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bubble.addView(sender);
        bubble.addView(body);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = (int) Ui.dp(c, 4);
        bubble.addView(shareCard, cardLp);
        int width = (int) (parent.getResources().getDisplayMetrics().widthPixels * 0.78f);
        root.addView(bubble, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Holder(root, bubble, sender, body, shareCard, shareCover, shareTitle, shareSub);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ChatMessage m = items.get(position);
        boolean mine = m.senderId != null && m.senderId == me;
        Context c = h.itemView.getContext();
        h.sender.setText(m.sender == null ? "—" : m.sender);
        h.body.setText(m.body);
        h.body.setVisibility(m.body == null || m.body.isEmpty() ? View.GONE : View.VISIBLE);
        if (m.body != null && m.body.contains("http")) {
            android.text.util.Linkify.addLinks(h.body, android.text.util.Linkify.WEB_URLS);
        }
        bindShare(h, m, c);
        boolean canAdd = !mine && m.sender != null && onSender != null;
        h.sender.setOnClickListener(canAdd ? v -> onSender.accept(m.sender) : null);
        if (m.share == null) {
            h.itemView.setOnClickListener(canAdd ? v -> onSender.accept(m.sender) : null);
        }
        h.bubble.setBackgroundColor(Ui.withAlpha(
                Ui.color(c, mine ? R.color.hanko : R.color.ink_700), mine ? 0.28f : 1f));
        LinearLayout root = (LinearLayout) h.itemView;
        root.setGravity(mine ? Gravity.END : Gravity.START);
        h.sender.setTextColor(Ui.color(c, mine ? R.color.hanko : R.color.paper_300));
    }

    private void bindShare(Holder h, ChatMessage m, Context c) {
        ChatMessage.Share s = m.share;
        if (s == null) {
            h.shareCard.setVisibility(View.GONE);
            h.shareCard.setOnClickListener(null);
            return;
        }
        h.shareCard.setVisibility(View.VISIBLE);
        h.shareTitle.setText(s.title);
        String who = m.sender == null ? "" : m.sender + " ";
        String sub = "rating".equals(s.kind) && s.score != null
                ? who + c.getString(R.string.chat_share_rated, s.score)
                : who + c.getString(R.string.chat_share_reading);
        h.shareSub.setText(sub);
        h.shareSub.setTextColor(Ui.color(c, R.color.hanko));
        Covers.load(h.shareCover, s.coverUrl, s.title, s.mediaType);
        h.shareCard.setOnClickListener(v -> openShare(v, s));
    }

    /** A catalog title opens by id. A source-only title opens by source and series id. */
    private static void openShare(View v, ChatMessage.Share s) {
        Activity activity = (Activity) v.getContext();
        if (s.mediaId != null && s.mediaId > 0) {
            Media media = new Media();
            media.id = s.mediaId;
            media.title = s.title;
            media.coverUrl = s.coverUrl;
            media.mediaType = s.mediaType == null ? "MANGA" : s.mediaType;
            DetailActivity.open(activity, media, null);
            return;
        }
        if (s.source == null || s.seriesId == null) return;
        Series series = new Series();
        series.source = s.source;
        series.id = s.seriesId;
        series.title = s.title;
        series.coverUrl = s.coverUrl;
        series.contentType = s.mediaType;
        DetailActivity.open(activity, series, null);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
