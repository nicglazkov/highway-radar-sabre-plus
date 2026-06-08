package app.sabre.wzsabre.waze;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Thin OkHttp wrapper for the Waze "RT" protocol: binary/octet-stream POSTs with a
 * per-session cookie jar (login sets cookies that subsequent commands must carry).
 * Ported from wzsabre 2.2 wazemo.WazeHttpClient.
 */
final class WazeHttpClient {
    private static final MediaType OCTET = MediaType.parse("binary/octet-stream");

    private final SessionCookieJar cookieJar = new SessionCookieJar();
    private final OkHttpClient client;

    WazeHttpClient() {
        client = new OkHttpClient.Builder()
                // The RT /command endpoint long-polls (x-waze-wait-timeout: 10500ms),
                // so the read timeout must exceed that or the server's held response
                // is cut off mid-wait.
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .cookieJar(cookieJar)
                .build();
    }

    /** Drop all cookies (login clears them before re-authenticating). */
    void clearCookies() { cookieJar.clear(); }

    static final class HttpResult {
        final int code;
        final byte[] body;
        HttpResult(int code, byte[] body) { this.code = code; this.body = body; }
    }

    HttpResult post(String url, byte[] body, Map<String, String> headers) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, OCTET));
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) rb.header(e.getKey(), e.getValue());
            }
        }
        Request request = rb.build();

        // Retry transient network failures (DNS hiccups, timeouts) a couple of times.
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response resp = client.newCall(request).execute()) {
                byte[] b = resp.body() != null ? resp.body().bytes() : new byte[0];
                return new HttpResult(resp.code(), b);
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(400L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    /** Simple in-memory cookie jar scoped to one Waze session. */
    private static final class SessionCookieJar implements CookieJar {
        private final List<Cookie> cookies = new ArrayList<>();

        @Override
        public synchronized void saveFromResponse(HttpUrl url, List<Cookie> newCookies) {
            for (Cookie c : newCookies) {
                Iterator<Cookie> it = cookies.iterator();
                while (it.hasNext()) {
                    if (it.next().name().equals(c.name())) it.remove();
                }
                cookies.add(c);
            }
        }

        @Override
        public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            return new ArrayList<>(cookies);
        }

        synchronized void clear() { cookies.clear(); }
    }
}
