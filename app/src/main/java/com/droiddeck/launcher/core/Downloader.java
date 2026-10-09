package com.droiddeck.launcher.core;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The two fetches this app makes: the runtime catalog (a few hundred bytes of JSON) and the
 * runtime tarball itself (~790 MB). Plain HttpURLConnection - nothing here needs a client library,
 * and one fewer dependency is one fewer thing that can fail a release build.
 */
public final class Downloader {
    private static final String TAG = "Downloader";
    private static final int TIMEOUT_MS = 30_000;
    /** Set once from the Application class; lets every fetch fall through to MirrorHub nodes. */
    private static volatile android.content.Context appContext;

    private Downloader() {}

    /** Set once from the Application class; enables MirrorHub multi-source for every download. */
    public static void init(android.content.Context context) {
        appContext = context.getApplicationContext();
    }

    /** True when MirrorHub multi-source is available for this fetch. */
    private static boolean mirrorHubReady() {
        return appContext != null && MirrorHub.refresh(appContext);
    }

    /**
     * Fetches a URL with MirrorHub fallback: tries the original URL first with a short timeout,
     * then the cached nodes in latency order. Small JSON fetches skip the latency probe - a
     * blocked raw.githubusercontent.com fails in seconds instead of stalling the UI.
     */
    public static String downloadString(String url) {
        if (mirrorHubReady() && MirrorHub.isGitHubUrl(url)) {
            java.util.List<String> sources = MirrorHub.buildFallbackSources(appContext, url);
            for (int i = 0; i < sources.size(); i++) {
                String candidate = sources.get(i);
                String body = downloadStringSingle(candidate, i == 0 ? 6_000 : TIMEOUT_MS);
                if (body != null) {
                    if (i > 0) Log.i(TAG, "GET via mirror (" + (i + 1) + "/" + sources.size() + "): " + candidate);
                    return body;
                }
                Log.w(TAG, "string source " + (i + 1) + "/" + sources.size() + " failed, trying next");
            }
            return null;
        }
        return downloadStringSingle(url, TIMEOUT_MS);
    }

    private static String downloadStringSingle(String url, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            // Both connect and read get the same budget: a blocked GitHub is dead weight for the UI.
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            if (connection.getResponseCode() / 100 != 2) {
                Log.w(TAG, url + " -> HTTP " + connection.getResponseCode());
                return null;
            }
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                FileUtils.copy(in, out);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.w(TAG, "GET " + url, e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Downloads to {@code destination}, reporting a 0..1 fraction (or -1 when the server sends no
     * length). {@code resume} continues a partial file with a Range request, which is what makes a
     * 790 MB download survive a dropped connection instead of starting over. GitHub URLs are
     * automatically expanded to the MirrorHub multi-source list.
     */
    public static boolean downloadFile(String url, File destination, boolean resume,
                                       java.util.function.Consumer<Float> progress) {
        return downloadFile(url, destination, resume, -1, progress);
    }

    /**
     * Variant with a known total size: mirror nodes that stream release assets rarely send a
     * Content-Length, which would leave the progress callback at -1 ("downloading" forever)
     * even though bytes are moving. The catalog's size lets the caller compute real fractions.
     */
    public static boolean downloadFile(String url, File destination, boolean resume, long knownSize,
                                       java.util.function.Consumer<Float> progress) {
        java.util.List<String> sources;
        if (mirrorHubReady() && MirrorHub.isGitHubUrl(url)) {
            sources = MirrorHub.buildSourceList(appContext, url);
        } else {
            sources = java.util.Collections.singletonList(url);
        }
        return downloadFileWithSources(sources, destination, resume, knownSize, progress);
    }

    /**
     * Downloads from multiple candidate URLs, trying the next one when the current fails.
     * Resume works across source switches because all sources serve the same file and the partial
     * archive is kept between attempts. Returns true when any source completes the download.
     */
    public static boolean downloadFileWithSources(java.util.List<String> urls, File destination,
                                                  boolean resume,
                                                  java.util.function.Consumer<Float> progress) {
        return downloadFileWithSources(urls, destination, resume, -1, progress);
    }

    public static boolean downloadFileWithSources(java.util.List<String> urls, File destination,
                                                  boolean resume, long knownSize,
                                                  java.util.function.Consumer<Float> progress) {
        if (urls == null || urls.isEmpty()) return false;
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            boolean ok = downloadFileSingle(url, destination, resume, knownSize, progress);
            if (ok) return true;
            Log.w(TAG, "source " + (i + 1) + "/" + urls.size() + " failed, trying next");
        }
        return false;
    }

    private static boolean downloadFileSingle(String url, File destination, boolean resume,
                                              long knownSize,
                                              java.util.function.Consumer<Float> progress) {
        HttpURLConnection connection = null;
        long have = resume && destination.isFile() ? destination.length() : 0;
        try {
            connection = open(url);
            if (have > 0) connection.setRequestProperty("Range", "bytes=" + have + "-");
            int code = connection.getResponseCode();
            boolean appending = code == HttpURLConnection.HTTP_PARTIAL;
            if (code / 100 != 2) {
                // A source that refuses the range is not worth restarting the file over: keep
                // the partial archive and let the caller try the next source. The file is only
                // rewritten from scratch when a later attempt finds a range-capable source.
                Log.w(TAG, url + " -> HTTP " + code + (have > 0 ? " (range refused, partial kept)" : ""));
                return false;
            }
            if (!appending && have > 0) {
                // The source ignored our Range header (200 instead of 206): accepting it would
                // truncate the partial and rewrite the whole file from byte 0, losing everything
                // downloaded so far. Skip this source; a range-capable one (direct GitHub, whose
                // release URL redirects to objects.githubusercontent.com, or a proxying mirror)
                // continues where we left off.
                Log.w(TAG, url + " ignored Range (HTTP 200), partial kept for the next source");
                return false;
            }
            if (!appending) have = 0;
            long length = connection.getContentLengthLong();
            // Mirror nodes that stream the asset usually send no Content-Length; the caller's
            // knownSize (the catalog row) keeps the progress fraction real in that case.
            long total = knownSize > 0 ? knownSize : (length < 0 ? -1 : length + have);
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            long written = have;
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), 1 << 16);
                 OutputStream out = new FileOutputStream(destination, appending)) {
                byte[] buffer = new byte[1 << 16];
                long lastReport = 0;
                for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                    written += read;
                    // A callback per 64 KB would be tens of thousands of UI hops; once per MB is
                    // enough to move a progress bar smoothly.
                    if (progress != null && written - lastReport > (1 << 20)) {
                        lastReport = written;
                        progress.accept(total > 0 ? written / (float) total : -1f);
                    }
                }
            }
            if (progress != null) progress.accept(1f);
            return total <= 0 || written >= total;
        } catch (Exception e) {
            Log.w(TAG, "download " + url, e);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws java.io.IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "DroidDeck-Android");
        return connection;
    }
}
