package com.example.aniga.ui.people;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.aniga.R;
import com.example.aniga.data.Api;
import com.example.aniga.data.Session;
import com.example.aniga.data.model.ChatMessage;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * One chat room: history on open, then light polling for new lines (NOTIFY wakes the backend; the
 * app just re-reads). Posting a message goes through the locked chat API with the session token.
 */
public class ChatActivity extends AppCompatActivity {

    public static final String EXTRA_ROOM_ID = "roomId";
    public static final String EXTRA_TITLE = "title";

    private static final long POLL_MS = 3000;

    private long roomId;
    private RecyclerView list;
    private ChatAdapter adapter;
    private EditText input;
    private TextView empty;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean active;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!active) {
                return;
            }
            fetch();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_chat);
        roomId = getIntent().getLongExtra(EXTRA_ROOM_ID, 0L);
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        ((TextView) findViewById(R.id.chat_title)).setText(title == null ? "Chat" : title);
        findViewById(R.id.chat_back).setOnClickListener(v -> finish());
        empty = findViewById(R.id.chat_empty);
        input = findViewById(R.id.chat_input);
        list = findViewById(R.id.chat_list);
        adapter = new ChatAdapter(Session.userId(this));
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        list.setLayoutManager(lm);
        list.setAdapter(adapter);

        findViewById(R.id.chat_send).setOnClickListener(v -> send());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chat_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            findViewById(R.id.chat_bar).setPadding(0, bars.top, 0, 0);
            View row = findViewById(R.id.chat_input_row);
            row.setPadding(row.getPaddingLeft(), row.getPaddingTop(), row.getPaddingRight(),
                    (int) (bars.bottom + getResources().getDisplayMetrics().density * 10));
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        active = false;
        handler.removeCallbacks(poll);
    }

    private void fetch() {
        Api.get().chatMessages(roomId).enqueue(new Callback<List<ChatMessage>>() {
            @Override
            public void onResponse(Call<List<ChatMessage>> c, Response<List<ChatMessage>> r) {
                if (!active || !r.isSuccessful() || r.body() == null) {
                    return;
                }
                boolean grew = r.body().size() != adapter.size();
                adapter.submit(r.body());
                empty.setVisibility(r.body().isEmpty() ? View.VISIBLE : View.GONE);
                if (grew && !r.body().isEmpty()) {
                    list.scrollToPosition(r.body().size() - 1);
                }
            }

            @Override
            public void onFailure(@NonNull Call<List<ChatMessage>> c, @NonNull Throwable t) {
            }
        });
    }

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        input.setText("");
        Map<String, String> body = Collections.singletonMap("body", text);
        Api.get().sendMessage(roomId, body).enqueue(new Callback<ChatMessage>() {
            @Override
            public void onResponse(Call<ChatMessage> c, Response<ChatMessage> r) {
                fetch();
            }

            @Override
            public void onFailure(@NonNull Call<ChatMessage> c, @NonNull Throwable t) {
                input.setText(text);
            }
        });
    }
}
