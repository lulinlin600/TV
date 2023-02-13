package com.fongmi.android.tv.player.parse;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.ApiConfig;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.net.OkHttp;
import com.fongmi.android.tv.ui.custom.CustomWebView;
import com.fongmi.android.tv.utils.Json;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.Response;

public class ParseJob {

    private final List<CustomWebView> webViews;
    private ExecutorService executor;
    private ExecutorService infinite;
    private Callback callback;
    private Parse parse;

    public static ParseJob create(Callback callback) {
        return new ParseJob(callback);
    }

    public ParseJob(Callback callback) {
        this.executor = Executors.newFixedThreadPool(2);
        this.infinite = Executors.newCachedThreadPool();
        this.webViews = new ArrayList<>();
        this.callback = callback;
    }

    public ParseJob start(Result result, boolean useParse) {
        setParse(result, useParse);
        execute(result);
        return this;
    }

    private void setParse(Result result, boolean useParse) {
        if (useParse) parse = ApiConfig.get().getParse();
        else if (result.getPlayUrl().startsWith("json:")) parse = Parse.get(1, result.getPlayUrl().substring(5));
        else if (result.getPlayUrl().startsWith("parse:")) parse = ApiConfig.get().getParse(result.getPlayUrl().substring(6));
        if (parse == null) parse = Parse.get(0, result.getPlayUrl(), result.getHeader());
    }

    private void execute(Result result) {
        executor.execute(() -> {
            try {
                executor.submit(getTask(result)).get(Constant.TIMEOUT_PARSE_DEF, TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                onParseError();
            }
        });
    }

    private Runnable getTask(Result result) {
        return () -> {
            try {
                doInBackground(result.getKey(), result.getUrl(), result.getFlag());
            } catch (Exception e) {
                onParseError();
            }
        };
    }

    private void doInBackground(String key, String webUrl, String flag) throws Exception {
        switch (parse.getType()) {
            case 0: //嗅探
                App.post(() -> startWeb(key, parse, webUrl, callback));
                break;
            case 1: //Json
                jsonParse(parse, webUrl, false);
                break;
            case 2: //Json聚合
                mixJson(webUrl, flag);
                break;
            case 3: //Web聚合
                mixWeb(webUrl, flag);
                break;
        }
    }

    private void jsonParse(Parse item, String webUrl, boolean strict) throws Exception {
        Response response = OkHttp.newCall(item.getUrl() + webUrl, Headers.of(item.getHeaders())).execute();
        JsonObject object = JsonParser.parseString(response.body().string()).getAsJsonObject();
        object = object.has("data") ? object.getAsJsonObject("data") : object;
        if (strict) checkResult(item, getHeader(object), Json.safeString(object, "url"));
        else checkResult(getHeader(object), Json.safeString(object, "url"));
    }

    private void mixJson(String webUrl, String flag) throws Exception {
        List<Parse> items = ApiConfig.get().getParses(1, flag);
        CountDownLatch latch = new CountDownLatch(items.size());
        for (Parse item : items) infinite.execute(() -> jsonParse(latch, item, webUrl));
        latch.await();
        onParseError();
    }

    private void mixWeb(String webUrl, String flag) {
        List<Parse> items = ApiConfig.get().getParses(0, flag);
        for (Parse item : items) App.post(() -> startWeb(item, webUrl, callback));
        if (items.isEmpty()) onParseError();
    }

    private void jsonParse(CountDownLatch latch, Parse item, String webUrl) {
        try {
            jsonParse(item, webUrl, true);
        } catch (Exception ignored) {
        } finally {
            latch.countDown();
        }
    }

    private void checkResult(HashMap<String, String> headers, String url) {
        if (TextUtils.isEmpty(url)) onParseError();
        else onParseSuccess(headers, url, "");
    }

    private void checkResult(Parse item, HashMap<String, String> headers, String url) throws Exception {
        int code = OkHttp.newCall(url, Headers.of(headers)).execute().code();
        if (code == 200) onParseSuccess(headers, url, item.getName());
    }

    private void startWeb(Parse item, String webUrl, Callback callback) {
        startWeb("", item, webUrl, callback);
    }

    private void startWeb(String key, Parse item, String webUrl, Callback callback) {
        webViews.add(CustomWebView.create(App.get()).start(key, item.getName(), item.getUrl() + webUrl, item.getHeaders(), callback));
    }

    private HashMap<String, String> getHeader(JsonObject object) {
        HashMap<String, String> headers = new HashMap<>();
        for (String key : object.keySet()) if (key.equalsIgnoreCase("user-agent") || key.equalsIgnoreCase("referer")) headers.put(key, object.get(key).getAsString());
        return headers;
    }

    private void onParseSuccess(Map<String, String> headers, String url, String from) {
        App.post(() -> {
            if (callback != null) callback.onParseSuccess(headers, url, from);
            stop();
        });
    }

    private void onParseError() {
        App.post(() -> {
            if (callback != null) callback.onParseError();
            stop();
        });
    }

    private void stopWeb() {
        for (CustomWebView webView : webViews) webView.stop(false);
    }

    public void stop() {
        if (executor != null) executor.shutdownNow();
        if (infinite != null) infinite.shutdownNow();
        infinite = null;
        executor = null;
        callback = null;
        stopWeb();
    }

    public interface Callback {

        void onParseSuccess(Map<String, String> headers, String url, String from);

        void onParseError();
    }
}