package com.example.aniga.data;

import android.content.Context;

import androidx.annotation.NonNull;

import com.example.aniga.BuildConfig;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import java.util.function.Consumer;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class Api {

    /** Backend origin from the build (aniga.local.properties or ANIGA_API_BASE_URL). */
    public static final String BASE_URL = BuildConfig.API_BASE_URL;

    private static AnigaService service;
    private static Context appContext;

    private Api() {
    }

    /** Call once from the Application/launch so the auth interceptor can read the stored session token. */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static synchronized AnigaService get() {
        if (service == null) {
            OkHttpClient client = new OkHttpClient.Builder()
                    .addInterceptor(chain -> {
                        Request request = chain.request();
                        String token = appContext == null ? null : Session.token(appContext);
                        if (token != null && !token.isEmpty()) {
                            request = request.newBuilder().header("Authorization", "Bearer " + token).build();
                        }
                        return chain.proceed(request);
                    })
                    .build();
            service = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(AnigaService.class);
        }
        return service;
    }

    /**
     * Enqueue and deliver on the main thread, dropping the result if the screen was destroyed
     * meanwhile (avoids touching detached views).
     */
    public static <T> void load(LifecycleOwner owner, Call<T> call,
                                Consumer<T> onSuccess, Consumer<Throwable> onError) {
        call.enqueue(new Callback<T>() {
            @Override
            public void onResponse(@NonNull Call<T> c, @NonNull Response<T> response) {
                if (!alive(owner)) return;
                if (response.isSuccessful() && (response.body() != null || response.code() == 204)) {
                    onSuccess.accept(response.body());
                } else {
                    onError.accept(new IllegalStateException("HTTP " + response.code()));
                }
            }

            @Override
            public void onFailure(@NonNull Call<T> c, @NonNull Throwable t) {
                if (alive(owner)) onError.accept(t);
            }
        });
    }

    private static boolean alive(LifecycleOwner owner) {
        return owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.CREATED);
    }
}
