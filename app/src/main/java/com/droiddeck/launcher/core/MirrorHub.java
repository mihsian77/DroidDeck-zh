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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MirrorHub GitHub acceleration node client.
 *
 * Fetches the active node list from MirrorHub (cached 6h), filters universal_proxy nodes that
 * support release asset downloads, and probes the fastest source between direct GitHub and the
 * top nodes before a large download. Falls back to direct connection when probing fails.
 *
 * Attribution: MirrorHub requires displaying "节点由 MirrorHub 提供" in user-visible UI.
 * See {@link #getAttributionText()} and {@link #getAttributionUrl()}.
 */
public final class MirrorHub {
    private static final String TAG = "MirrorHub";
    private static final String ACTIVE_NODES_URL =
            "https://raw.githubusercontent.com/mihsian77/MirrorHub/main/active-nodes.json";
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

    /** Result of a speed probe. */
    private static final class ProbeResult {
        final String url;
        final long latencyMs;
        ProbeResult(String url, long latencyMs) {
            this.url = url;
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

        if (System.currentTimeMillis() - lastFetch < CACHE_TTL_MS && cached != null) {
            return parseNodeCount(cached) > 0;
        }

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(ACTIVE_NODES_URL).openConnection();
            conn.setConnectTimeout(PROBE_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "DroidDeck-Android");
            if (conn.getResponseCode() / 100 != 2) {
                Log.w(TAG, "fetch nodes HTTP " + conn.getResponseCode());
                return cached != null && parseNodeCount(cached) > 0;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
            }
            String json = out.toString("UTF-8");
            prefs.edit().putString(KEY_NODES_JSON, json).putLong(KEY_LAST_FETCH, System.currentTimeMillis()).apply();
            return parseNodeCount(json) > 0;
        } catch (Exception e) {
            Log.w(TAG, "refresh failed", e);
            return cached != null && parseNodeCount(cached) > 0;
        }
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

    /**
     * Builds the ordered candidate source list for a GitHub URL: the fastest probed source
     * first, then direct GitHub, then remaining nodes. All serve the same file so a download
     * can fall through or resume across switches. Mirrors only GitHub-flavoured URLs; any other
     * URL (an IPFS gateway, a generic http mirror) is returned as a single direct entry.
     */
    public static List<String> buildSourceList(Context context, String url) {
        List<String> sources = new ArrayList<>();
        if (!isGitHubUrl(url)) {
            sources.add(url);
            return sources;
        }
        String fastest = selectFastestSource(context, url);
        sources.add(fastest);
        if (!fastest.equals(url)) sources.add(url);
        for (Node node : getNodes(context)) {
            String mirrored = "https://" + node.domain + "/" + url;
            if (!sources.contains(mirrored)) sources.add(mirrored);
            if (sources.size() >= 5) break;
        }
        return sources;
    }

    /**
     * Lightweight candidate list for small fetches (catalog JSON, API calls): direct GitHub
     * first, then the cached nodes in latency order. No probing - the caller applies a short
     * timeout on the direct source and falls through quickly when it is blocked.
     */
    public static List<String> buildFallbackSources(Context context, String url) {
        List<String> sources = new ArrayList<>();
        if (!isGitHubUrl(url)) {
            sources.add(url);
            return sources;
        }
        sources.add(url);
        for (Node node : getNodes(context)) {
            String mirrored = "https://" + node.domain + "/" + url;
            if (!sources.contains(mirrored)) sources.add(mirrored);
            if (sources.size() >= 4) break;
        }
        return sources;
    }

    /** True for GitHub-flavoured URLs (github.com, raw.githubusercontent.com, api.github.com). */
    public static boolean isGitHubUrl(String url) {
        return url != null && (url.startsWith("https://github.com/")
                || url.startsWith("https://raw.githubusercontent.com/")
                || url.startsWith("https://api.github.com/")
                || url.startsWith("http://github.com/")
                || url.startsWith("http://raw.githubusercontent.com/")
                || url.startsWith("http://api.github.com/"));
    }

    /**
     * Measures real download speed (KiB/s) over a short window for the speed-test page.
     * Downloads the first ~256 KiB of {@code url} and returns bytes/sec, or -1 on failure.
     * A one-byte Range probe measures latency only; this actually reads the body.
     */
    public static long measureSpeed(Context context, String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(PROBE_TIMEOUT_MS);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", "DroidDeck-Android");
            conn.setRequestProperty("Range", "bytes=0-262143"); // 256 KiB window
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() / 100 != 2) return -1;
            long start = System.currentTimeMillis();
            long read = 0;
            byte[] buf = new byte[8192];
            try (java.io.InputStream in = conn.getInputStream()) {
                int n;
                while (read < 262144 && (n = in.read(buf)) > 0) read += n;
            }
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed <= 0 || read <= 0) return -1;
            return read * 1000L / elapsed; // bytes per second
        } catch (Exception e) {
            Log.w(TAG, "speed probe " + url, e);
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Select the fastest download source for a GitHub URL.
     * Probes direct connection and the top N universal_proxy nodes concurrently with a
     * 1-byte Range request, returns the fastest. Falls back to the original URL if all fail.
     */
    public static String selectFastestSource(Context context, String githubUrl) {
        List<Node> nodes = getNodes(context);
        List<String> candidates = new ArrayList<>();
        candidates.add(githubUrl); // direct first
        int limit = Math.min(MAX_NODES_TO_PROBE, nodes.size());
        for (int i = 0; i < limit; i++) {
            candidates.add("https://" + nodes.get(i).domain + "/" + githubUrl);
        }

        ExecutorService executor = Executors.newFixedThreadPool(candidates.size());
        List<Future<ProbeResult>> futures = new ArrayList<>();
        for (String url : candidates) {
            futures.add(executor.submit(new ProbeTask(url)));
        }

        List<ProbeResult> results = new ArrayList<>();
        for (Future<ProbeResult> f : futures) {
            try {
                ProbeResult r = f.get(PROBE_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
                if (r != null && r.latencyMs > 0) results.add(r);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                // skip this source
            }
        }
        executor.shutdownNow();

        if (results.isEmpty()) {
            Log.w(TAG, "all probes failed, using direct");
            return githubUrl;
        }
        results.sort(Comparator.comparingLong(r -> r.latencyMs));
        ProbeResult best = results.get(0);
        Log.i(TAG, "fastest source: " + best.url.substring(0, Math.min(60, best.url.length()))
                + " (" + best.latencyMs + "ms) out of " + results.size() + " candidates");
        return best.url;
    }

    private static int parseNodeCount(String json) {
        try {
            return new JSONObject(json).getJSONArray("nodes").length();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Probes a single URL with a 1-byte Range GET, returns latency in ms or -1 on failure. */
    private static final class ProbeTask implements Callable<ProbeResult> {
        private final String url;
        ProbeTask(String url) { this.url = url; }

        @Override
        public ProbeResult call() {
            long start = System.currentTimeMillis();
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(PROBE_TIMEOUT_MS);
                conn.setReadTimeout(PROBE_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "DroidDeck-Android");
                conn.setRequestProperty("Range", "bytes=0-0");
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                // 200 = full file (no range support), 206 = partial content (range supported)
                if (code / 100 == 2) {
                    long latency = System.currentTimeMillis() - start;
                    return new ProbeResult(url, latency);
                }
                return new ProbeResult(url, -1);
            } catch (Exception e) {
                return new ProbeResult(url, -1);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }
}
