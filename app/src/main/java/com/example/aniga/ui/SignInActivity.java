package com.example.aniga.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.example.aniga.MainActivity;
import com.example.aniga.R;
import com.example.aniga.data.Api;
import com.example.aniga.data.Session;
import com.example.aniga.data.model.AuthResponse;
import com.example.aniga.data.model.Credentials;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Email/password is sent to our backend, which checks it with Firebase and returns a session token.
 * The app never talks to Firebase directly and never holds a Firebase credential.
 */
public class SignInActivity extends AppCompatActivity {

    private EditText email;
    private EditText password;
    private TextView message;
    private ProgressBar busy;
    private View enter;
    private View create;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_sign_in);
        email = findViewById(R.id.email);
        password = findViewById(R.id.password);
        message = findViewById(R.id.message);
        busy = findViewById(R.id.busy);
        enter = findViewById(R.id.enter);
        create = findViewById(R.id.create);
        enter.setOnClickListener(v -> sign(false));
        create.setOnClickListener(v -> sign(true));
        findViewById(R.id.sign_out).setOnClickListener(v -> {
            Session.signOut(this);
            setBusy(false);
            show(getString(R.string.sign_in_out));
        });
        if (Session.isSignedIn(this)) {
            openMain();
        }
    }

    private void sign(boolean createAccount) {
        String address = email.getText().toString().trim();
        String secret = password.getText().toString();
        if (address.isEmpty() || secret.length() < 6) {
            show(getString(R.string.sign_in_short));
            return;
        }
        setBusy(true);
        Credentials body = new Credentials(address, secret);
        Call<AuthResponse> call = createAccount ? Api.get().signup(body) : Api.get().login(body);
        call.enqueue(new Callback<AuthResponse>() {
            @Override
            public void onResponse(Call<AuthResponse> c, Response<AuthResponse> response) {
                if (isFinishing()) {
                    return;
                }
                if (response.isSuccessful() && response.body() != null && response.body().token != null) {
                    AuthResponse auth = response.body();
                    String name = auth.user != null && auth.user.displayName != null
                            && !auth.user.displayName.isEmpty()
                            ? auth.user.displayName
                            : (auth.user != null ? auth.user.username : address);
                    long id = auth.user != null ? auth.user.id : 0L;
                    Session.signIn(SignInActivity.this, id, name, auth.token);
                    openMain();
                } else {
                    setBusy(false);
                    show(explain(response));
                }
            }

            @Override
            public void onFailure(Call<AuthResponse> c, Throwable t) {
                if (isFinishing()) {
                    return;
                }
                setBusy(false);
                show(getString(R.string.error_backend));
            }
        });
    }

    /** The backend puts a human-readable reason in the problem+json "detail" field. */
    private String explain(Response<AuthResponse> response) {
        try {
            if (response.errorBody() != null) {
                String raw = response.errorBody().string();
                int at = raw.indexOf("\"detail\"");
                if (at >= 0) {
                    int start = raw.indexOf('"', at + 8) + 1;
                    start = raw.indexOf('"', start) + 1;
                    int end = raw.indexOf('"', start);
                    if (start > 0 && end > start) {
                        return raw.substring(start, end);
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to the generic message.
        }
        return getString(R.string.sign_in_failed);
    }

    private void show(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private void setBusy(boolean working) {
        busy.setVisibility(working ? View.VISIBLE : View.GONE);
        enter.setEnabled(!working);
        create.setEnabled(!working);
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }
}
