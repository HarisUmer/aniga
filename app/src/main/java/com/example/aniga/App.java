package com.example.aniga;

import android.app.Application;

import com.example.aniga.data.Api;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // The API client reads the stored session token on every request.
        Api.init(this);
    }
}
