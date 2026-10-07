package com.example.aniga.ui.foryou;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.RecyclerView;

import com.example.aniga.R;
import com.example.aniga.data.Api;
import com.example.aniga.data.Session;
import com.example.aniga.data.model.Media;
import com.example.aniga.data.model.Recommendation;
import com.example.aniga.data.model.TasteV2;
import com.example.aniga.ui.Covers;
import com.example.aniga.ui.Ui;
import com.example.aniga.ui.detail.DetailActivity;
import com.example.aniga.ui.shelf.RateSheet;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The ranked list with the reward buttons: ♥ is reward 1, ✕ is reward 0 (and hides the title), opening a pick
 * is a weak positive. Every reaction is a step of the online learner on the server; {@code onLearned} lets the
 * screen refresh the taste bars so the user sees what moved.
 */
public class RecommendationAdapter extends RecyclerView.Adapter<RecommendationAdapter.Holder> {

    private final List<Recommendation> items = new ArrayList<>();
    private final Map<Long, Integer> rated = new HashMap<>();
    private final Set<Long> liked = new HashSet<>();
    private final LifecycleOwner owner;
    private final Runnable onLearned;
    private RecyclerView recycler;
    private double best = 1;

    public RecommendationAdapter(LifecycleOwner owner, Runnable onLearned) {
        this.owner = owner;
        this.onLearned = onLearned;
    }

    public void submit(List<Recommendation> next) {
        items.clear();
        items.addAll(next);
        liked.clear();
        best = 1e-9;
        for (Recommendation r : next) best = Math.max(best, r.score);
        notifyDataSetChanged();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
        recycler = rv;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
        recycler = null;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recommendation, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Recommendation r = items.get(position);
        Media m = r.media;
        int typeColor = Ui.typeColor(h.itemView.getContext(), m.mediaType);
        h.rank.setText(String.format(Locale.ROOT, "%02d", position + 1));
        h.rank.setTextColor(position < 3 ? typeColor : Ui.color(h.itemView.getContext(), R.color.ink_600));
        h.type.setText(m.mediaType + (m.startYear != null ? "  ·  " + m.startYear : ""));
        h.type.setTextColor(typeColor);
        h.explore.setVisibility(r.isExplore() ? View.VISIBLE : View.GONE);
        h.title.setText(m.title);
        h.reasons.setText(r.reasons == null ? "" : String.join("  ·  ", r.reasons));
        h.match.setIndicatorColor(typeColor);
        h.match.setProgressCompat((int) (1000 * Math.max(0, r.score) / best), true);
        h.matchLabel.setText(String.format(Locale.ROOT, "%.2f", r.score));
        Covers.load(h.cover, m.coverUrl, m.title, m.mediaType);
        h.itemView.setOnClickListener(v -> {
            send(v, m.id, "CLICK", false);
            DetailActivity.open((Activity) v.getContext(), m, h.cover);
        });
        Integer given = rated.get(m.id);
        h.rate.setText(given != null && given > 0 ? "★ " + given : h.itemView.getContext().getString(R.string.rate));
        h.rate.setOnClickListener(v -> RateSheet.show((AppCompatActivity) v.getContext(), m, s -> {
            rated.put(m.id, s.score);
            int pos = h.getBindingAdapterPosition();
            if (pos >= 0) notifyItemChanged(pos);
            // A rating is a reward too; the server already took the step.
            onLearned.run();
        }));
        boolean isLiked = liked.contains(m.id);
        h.like.setText(isLiked ? R.string.rec_liked : R.string.rec_like);
        h.like.setTextColor(Ui.color(h.itemView.getContext(), isLiked ? R.color.hanko : R.color.paper_200));
        h.like.setOnClickListener(v -> {
            boolean now = !liked.contains(m.id);
            if (now) liked.add(m.id);
            else liked.remove(m.id);
            send(v, m.id, now ? "LIKE" : "CLEAR", true);
            int pos = h.getBindingAdapterPosition();
            if (pos >= 0) notifyItemChanged(pos, "like");
            if (now) pop(v);
        });
        h.nope.setOnClickListener(v -> dismiss(h, r));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
        if (payloads.contains("like")) {
            boolean isLiked = liked.contains(items.get(position).media.id);
            h.like.setText(isLiked ? R.string.rec_liked : R.string.rec_like);
            h.like.setTextColor(Ui.color(h.itemView.getContext(), isLiked ? R.color.hanko : R.color.paper_200));
            return;
        }
        super.onBindViewHolder(h, position, payloads);
    }

    /** The row slides out; Undo clears the reaction on the server and puts the row back where it was. */
    private void dismiss(Holder h, Recommendation r) {
        int pos = h.getBindingAdapterPosition();
        if (pos < 0) return;
        send(h.itemView, r.media.id, "NOT_INTERESTED", true);
        Runnable remove = () -> {
            int at = items.indexOf(r);
            if (at < 0) return;
            items.remove(at);
            notifyItemRemoved(at);
            notifyItemRangeChanged(at, items.size() - at);
        };
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            h.itemView.animate().translationX(-h.itemView.getWidth() * 0.6f).alpha(0f).setDuration(220)
                    .withEndAction(() -> {
                        h.itemView.setTranslationX(0f);
                        h.itemView.setAlpha(1f);
                        remove.run();
                    }).start();
        } else {
            remove.run();
        }
        if (recycler == null) return;
        Snackbar.make(recycler, R.string.rec_noped, Snackbar.LENGTH_LONG)
                .setAction(R.string.rec_undo, v -> {
                    send(v, r.media.id, "CLEAR", true);
                    int at = Math.min(pos, items.size());
                    if (!items.contains(r)) {
                        items.add(at, r);
                        notifyItemInserted(at);
                        notifyItemRangeChanged(at, items.size() - at);
                    }
                }).show();
    }

    private void send(View v, long mediaId, String kind, boolean refresh) {
        long user = Session.userId(v.getContext());
        Api.load(owner, Api.get().feedback(user, new TasteV2.Feedback(mediaId, kind)), res -> {
            if (refresh) onLearned.run();
        }, e -> {
        });
    }

    private static void pop(View v) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        v.setScaleX(0.8f);
        v.setScaleY(0.8f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new OvershootInterpolator(3f)).start();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView rank;
        final ImageView cover;
        final TextView type;
        final TextView explore;
        final TextView title;
        final TextView reasons;
        final LinearProgressIndicator match;
        final TextView matchLabel;
        final MaterialButton rate;
        final MaterialButton like;
        final MaterialButton nope;

        Holder(View v) {
            super(v);
            rank = v.findViewById(R.id.rank);
            cover = v.findViewById(R.id.cover);
            type = v.findViewById(R.id.type_tag);
            explore = v.findViewById(R.id.explore);
            title = v.findViewById(R.id.title);
            reasons = v.findViewById(R.id.reasons);
            match = v.findViewById(R.id.match);
            matchLabel = v.findViewById(R.id.match_label);
            rate = v.findViewById(R.id.rate);
            like = v.findViewById(R.id.like);
            nope = v.findViewById(R.id.nope);
        }
    }
}
