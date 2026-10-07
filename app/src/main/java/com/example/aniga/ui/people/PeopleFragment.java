package com.example.aniga.ui.people;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.example.aniga.R;
import com.example.aniga.data.Api;
import com.example.aniga.data.Session;
import com.example.aniga.data.model.ChatRoom;
import com.example.aniga.data.model.FriendRequest;
import com.example.aniga.data.model.Match;
import com.example.aniga.data.model.Outcome;
import com.example.aniga.data.model.Person;
import com.example.aniga.data.model.Personality;
import com.example.aniga.data.model.ShelfItem;
import com.example.aniga.ui.Ui;
import com.example.aniga.ui.widget.StatusView;
import com.google.android.material.button.MaterialButton;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Your circle: a predicted reading personality, friend requests, friends you can message or whose
 * shelf you can open, the global room, and taste matches you can add. Everything is scoped to the
 * signed-in session on the backend.
 */
public class PeopleFragment extends Fragment {

    private TextView personalityType;
    private TextView personalitySummary;
    private LinearLayout traits;
    private TextView requestsHeader;
    private LinearLayout requests;
    private LinearLayout friends;
    private LinearLayout matches;
    private EditText addUsername;
    private StatusView status;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_people, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View content = view.findViewById(R.id.content);
        int basePad = content.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.setPadding(v.getPaddingLeft(), basePad + bars.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        personalityType = view.findViewById(R.id.personality_type);
        personalitySummary = view.findViewById(R.id.personality_summary);
        traits = view.findViewById(R.id.personality_traits);
        requestsHeader = view.findViewById(R.id.requests_header);
        requests = view.findViewById(R.id.requests);
        friends = view.findViewById(R.id.friends);
        matches = view.findViewById(R.id.matches);
        addUsername = view.findViewById(R.id.add_username);
        status = view.findViewById(R.id.status);

        view.findViewById(R.id.add_button).setOnClickListener(v -> addFriend());
        view.findViewById(R.id.global_button).setOnClickListener(v -> openGlobal());
        load();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh when returning from a chat or after accepting a request elsewhere.
        if (friends != null) {
            load();
        }
    }

    private void load() {
        status.loading(R.string.loading_people);
        Api.load(getViewLifecycleOwner(), Api.get().personality(), this::showPersonality, e -> {
        });
        Api.load(getViewLifecycleOwner(), Api.get().friendRequests(), this::showRequests, e -> {
        });
        Api.load(getViewLifecycleOwner(), Api.get().matches(), this::showMatches, e -> {
        });
        Api.load(getViewLifecycleOwner(), Api.get().friends(), list -> {
            status.hide();
            showFriends(list);
        }, e -> status.error(getString(R.string.error_backend), this::load));
    }

    private void showPersonality(Personality p) {
        if (p == null) {
            return;
        }
        personalityType.setText(p.type);
        personalitySummary.setText(p.summary);
        traits.removeAllViews();
        if (p.traits == null) {
            return;
        }
        for (Personality.Trait t : p.traits) {
            traits.addView(traitRow(t.name, t.score));
        }
    }

    private View traitRow(String name, int score) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) Ui.dp(requireContext(), 3);
        row.setPadding(0, pad, 0, pad);

        TextView label = new TextView(requireContext());
        label.setTextAppearance(R.style.TextAppearance_Aniga_Label);
        label.setTextColor(Ui.color(requireContext(), R.color.paper_200));
        label.setText(name);
        row.addView(label, new LinearLayout.LayoutParams((int) Ui.dp(requireContext(), 92), -2));

        LinearLayout track = new LinearLayout(requireContext());
        track.setBackgroundColor(Ui.withAlpha(Ui.color(requireContext(), R.color.ink_700), 1f));
        View fill = new View(requireContext());
        fill.setBackgroundColor(Ui.color(requireContext(), R.color.hanko));
        LinearLayout.LayoutParams fp =
                new LinearLayout.LayoutParams(0, (int) Ui.dp(requireContext(), 10), Math.max(1, score));
        track.addView(fill, fp);
        View rest = new View(requireContext());
        LinearLayout.LayoutParams rp =
                new LinearLayout.LayoutParams(0, (int) Ui.dp(requireContext(), 10), Math.max(0, 100 - score));
        track.addView(rest, rp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginEnd((int) Ui.dp(requireContext(), 10));
        row.addView(track, tp);

        TextView value = new TextView(requireContext());
        value.setTextAppearance(R.style.TextAppearance_Aniga_Meta);
        value.setText(score + "%");
        row.addView(value, new LinearLayout.LayoutParams((int) Ui.dp(requireContext(), 40), -2));
        return row;
    }

    private void showRequests(List<FriendRequest> list) {
        requests.removeAllViews();
        boolean any = list != null && !list.isEmpty();
        requestsHeader.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) {
            return;
        }
        for (FriendRequest r : list) {
            LinearLayout row = card();
            TextView name = rowTitle(r.name() + "  @" + r.username);
            LinearLayout bar = new LinearLayout(requireContext());
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            MaterialButton accept = smallButton(getString(R.string.people_accept), false);
            accept.setOnClickListener(v -> Api.load(getViewLifecycleOwner(),
                    Api.get().acceptRequest(r.id), o -> {
                        Toast.makeText(requireContext(), R.string.people_now_friends, Toast.LENGTH_SHORT).show();
                        load();
                    }, e -> toastFail()));
            MaterialButton decline = smallButton(getString(R.string.people_decline), true);
            decline.setOnClickListener(v -> Api.load(getViewLifecycleOwner(),
                    Api.get().declineRequest(r.id), o -> load(), e -> toastFail()));
            bar.addView(accept);
            bar.addView(decline);
            row.addView(bar);
            requests.addView(row);
        }
    }

    private void showFriends(List<Person> list) {
        friends.removeAllViews();
        if (list == null || list.isEmpty()) {
            TextView empty = emptyLine(getString(R.string.people_empty_friends));
            friends.addView(empty);
            return;
        }
        for (Person p : list) {
            LinearLayout row = card();
            LinearLayout bar = new LinearLayout(requireContext());
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.addView(rowTitle(p.name() + "  @" + p.username), new LinearLayout.LayoutParams(0, -2, 1f));
            MaterialButton shelf = smallButton(getString(R.string.people_shelf), true);
            shelf.setOnClickListener(v -> openShelf(p));
            MaterialButton chat = smallButton(getString(R.string.people_chat), false);
            chat.setOnClickListener(v -> openDirect(p));
            bar.addView(shelf);
            bar.addView(chat);
            row.addView(bar);
            friends.addView(row);
        }
    }

    private void showMatches(List<Match> list) {
        matches.removeAllViews();
        if (list == null || list.isEmpty()) {
            matches.addView(emptyLine(getString(R.string.people_empty_matches)));
            return;
        }
        for (Match m : list) {
            LinearLayout row = card();
            LinearLayout head = new LinearLayout(requireContext());
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(rowTitle(m.name() + "  @" + m.username), new LinearLayout.LayoutParams(0, -2, 1f));
            MaterialButton add = smallButton(getString(R.string.people_add), false);
            add.setOnClickListener(v -> sendRequest(m.username));
            head.addView(add);
            row.addView(head);

            TextView meta = metaLine(getString(R.string.people_affinity, m.shared,
                    String.format(Locale.ROOT, "%.1f", m.affinity)));
            row.addView(meta);
            if (m.blurb != null) {
                row.addView(metaLine(m.blurb));
            }
            if (m.theirPicks != null && !m.theirPicks.isEmpty()) {
                row.addView(metaLine(getString(R.string.people_their_picks,
                        android.text.TextUtils.join(", ", m.theirPicks))));
            }
            matches.addView(row);
        }
    }

    private void addFriend() {
        String username = addUsername.getText().toString().trim();
        if (username.isEmpty()) {
            return;
        }
        addUsername.setText("");
        sendRequest(username);
    }

    private void sendRequest(String username) {
        Api.load(getViewLifecycleOwner(), Api.get().addFriend(Collections.singletonMap("username", username)),
                outcome -> {
                    boolean accepted = outcome != null && "ACCEPTED".equals(outcome.status);
                    Toast.makeText(requireContext(),
                            accepted ? R.string.people_now_friends : R.string.people_added,
                            Toast.LENGTH_SHORT).show();
                    load();
                }, e -> Toast.makeText(requireContext(), R.string.people_request_failed, Toast.LENGTH_SHORT).show());
    }

    private void openGlobal() {
        Api.load(getViewLifecycleOwner(), Api.get().globalRoom(),
                room -> openChat(room, getString(R.string.people_global_title)),
                e -> toastFail());
    }

    private void openDirect(Person p) {
        Api.load(getViewLifecycleOwner(), Api.get().openDirect(Collections.singletonMap("username", p.username)),
                room -> openChat(room, p.name()), e -> toastFail());
    }

    private void openChat(ChatRoom room, String title) {
        if (room == null) {
            toastFail();
            return;
        }
        Intent intent = new Intent(requireContext(), ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_ROOM_ID, room.id);
        intent.putExtra(ChatActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }

    private void openShelf(Person p) {
        Api.load(getViewLifecycleOwner(), Api.get().shelf(p.id), items -> showShelf(p, items), e -> toastFail());
    }

    private void showShelf(Person p, List<ShelfItem> items) {
        StringBuilder sb = new StringBuilder();
        if (items == null || items.isEmpty()) {
            sb.append(getString(R.string.people_shelf_empty));
        } else {
            for (ShelfItem it : items) {
                sb.append("• ").append(it.title);
                if (it.score != null && it.score > 0) {
                    sb.append("  ").append(it.score).append("/10");
                }
                sb.append('\n');
            }
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(p.name())
                .setMessage(sb.toString().trim())
                .setPositiveButton(R.string.back, null)
                .show();
    }

    // --- small view builders in the app's ink/paper style ---

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = (int) Ui.dp(requireContext(), 12);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) Ui.dp(requireContext(), 8);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView rowTitle(String text) {
        TextView tv = new TextView(requireContext());
        tv.setTextAppearance(R.style.TextAppearance_Aniga_Label);
        tv.setTextColor(Ui.color(requireContext(), R.color.paper_100));
        tv.setText(text);
        return tv;
    }

    private TextView metaLine(String text) {
        TextView tv = new TextView(requireContext());
        tv.setTextAppearance(R.style.TextAppearance_Aniga_Meta);
        tv.setPadding(0, (int) Ui.dp(requireContext(), 3), 0, 0);
        tv.setText(text);
        return tv;
    }

    private TextView emptyLine(String text) {
        TextView tv = new TextView(requireContext());
        tv.setTextAppearance(R.style.TextAppearance_Aniga_Body);
        tv.setTextColor(Ui.color(requireContext(), R.color.paper_300));
        tv.setText(text);
        return tv;
    }

    private MaterialButton smallButton(String text, boolean outlined) {
        MaterialButton b = outlined
                ? new MaterialButton(requireContext(), null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle)
                : new MaterialButton(requireContext());
        b.setText(text);
        b.setTextSize(11f);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        int h = (int) Ui.dp(requireContext(), 11);
        b.setPadding(h, 0, h, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, (int) Ui.dp(requireContext(), 34));
        lp.setMarginStart((int) Ui.dp(requireContext(), 6));
        b.setLayoutParams(lp);
        if (outlined) {
            b.setTextColor(Ui.color(requireContext(), R.color.paper_100));
        }
        return b;
    }

    private void toastFail() {
        if (isAdded()) {
            Toast.makeText(requireContext(), R.string.people_request_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
