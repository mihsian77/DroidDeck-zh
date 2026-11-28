package com.droiddeck.launcher.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * MirrorHub GitHub acceleration node client.
 *
 * Fetches the active node list from MirrorHub (cached 6h), filters universal_proxy nodes that
 * support release asset downloads, and probes the fastest source between direct GitHub and the
 * top nodes before a large download. Falls back to direct connection when probing fails.
 *
 * Attribution: the node list carries a required "节点由 MirrorHub 提供" credit. The UI has
 * not been wired to it yet; {@link #getAttributionText()} and {@link #getAttributionUrl()}
 * return it for that. Tracking: BACKLOG.md.
 */
public final class MirrorHub {
    private static final String TAG = "MirrorHub";
    private static final String ACTIVE_NODES_URL =
            "https://raw.githubusercontent.com/mihsian77/MirrorHub/main/active-nodes.json";
    // Mirror URLs for fetching the node list itself (chicken-and-egg problem)
    private static final String[] MIRROR_NODES_URLS = {
            "https://mirror.ghproxy.com/https://raw.githubusercontent.com/mihsian77/MirrorHub/main/active-nodes.json",
            "https://ghfast.top/https://raw.githubusercontent.com/mihsian77/MirrorHub/main/active-nodes.json",
            "https://gh-proxy.com/https://raw.githubusercontent.com/mihsian77/MirrorHub/main/active-nodes.json",
    };
    private static final String PREFS_NAME = "mirrorhub";
    private static final String KEY_NODES_JSON = "nodes_json";
    private static final String KEY_LAST_FETCH = "last_fetch";
    private static final long CACHE_TTL_MS = 6 * 3600_000L;
    private static final int PROBE_TIMEOUT_MS = 8_000;
    private static final int MAX_NODES_TO_PROBE = 3;

    private MirrorHub() {}

    /** A single acceleration node. */
    public static final class Node {
        public final String id;
        public final String name;
        public final String domain;
        public final String category;
        public final long latencyMs;

        Node(String id, String name, String domain, String category, long latencyMs) {
            this.id = id;
            this.name = name;
            this.domain = domain;
            this.category = category;
            this.latencyMs = latencyMs;
        }
    }

    /**
     * Refresh the node list from MirrorHub. Safe to call often - cached for 6 hours.
     * Returns true if nodes are available (from cache or fresh fetch).
     */
    public static boolean refresh(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastFetch = prefs.getLong(KEY_LAST_FETCH, 0);
        String cached = prefs.getString(KEY_NODES_JSON, null);

        // A clock that jumped backwards leaves lastFetch in the future; treat that as stale
        // instead of trusting the cache forever.
        long age = System.currentTimeMillis() - lastFetch;
        if (age >= 0 && age < CACHE_TTL_MS && cached != null) {
            return parseNodeCount(cached) > 0;
        }

        // Try direct first, then mirrors (chicken-and-egg: the node list itself needs acceleration)
        String[] allUrls = new String[1 + MIRROR_NODES_URLS.length];
        allUrls[0] = ACTIVE_NODES_URL;
        System.arraycopy(MIRROR_NODES_URLS, 0, allUrls, 1, MIRROR_NODES_URLS.length);

        for (String url : allUrls) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(PROBE_TIMEOUT_MS);
                conn.setReadTimeout(PROBE_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "DroidDeck-Android");
                int code = conn.getResponseCode();
                if (code / 100 != 2) {
                    Log.w(TAG, "fetch nodes HTTP " + code + " from " + url);
                    conn.disconnect();
                    continue;
                }
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
                }
                String json = out.toString("UTF-8");
                if (parseNodeCount(json) > 0) {
                    prefs.edit().putString(KEY_NODES_JSON, json).putLong(KEY_LAST_FETCH, System.currentTimeMillis()).apply();
                    Log.i(TAG, "fetched nodes from " + url);
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "refresh failed from " + url, e);
            }
        }

        Log.w(TAG, "all node list sources failed, using cache");
        return cached != null && parseNodeCount(cached) > 0;
    }

    /** Returns cached universal_proxy nodes, sorted by latency (fastest first). */
    public static List<Node> getNodes(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_NODES_JSON, null);
        if (json == null) return Collections.emptyList();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.getJSONArray("nodes");
            List<Node> nodes = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject n = arr.getJSONObject(i);
                String category = n.optString("category", "");
                if (!"universal_proxy".equals(category)) continue;
                nodes.add(new Node(
                        n.optString("id", ""),
                        n.optString("name", ""),
                        n.optString("domain", ""),
                        category,
                        n.optLong("latency_ms", 9999)
                ));
            }
            nodes.sort(Comparator.comparingLong(a -> a.latencyMs));
            return nodes;
        } catch (Exception e) {
            Log.w(TAG, "parse nodes", e);
            return Collections.emptyList();
        }
    }

    /** Attribution text to display in UI (required by MirrorHub license). */
    public static String getAttributionText(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_NODES_JSON, null);
        if (json != null) {
            try {
                return new JSONObject(json).optString("attribution_text", "节点由 MirrorHub 提供");
            } catch (Exception ignored) {}
        }
        return "节点由 MirrorHub 提供";
    }

    /** Attribution URL for the "learn more" link. */
    public static String getAttributionUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_NODES_JSON, null);
        if (json != null) {
            try {
                return new JSONObject(json).optString("attribution_url", "https://github.com/mihsian77/MirrorHub");
            } catch (Exception ignored) {}
        }
        return "https://github.com/mihsian77/MirrorHub";
    }

    private static int parseNodeCount(String json) {
        try {
            return new JSONObject(json).getJSONArray("nodes").length();
        } catch (Exception e) {
            return 0;
        }
    }
}
