package io.github.sudoapexo.studycolors;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.WebView;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-tap updates. Looks at the "latest" release of this app on GitHub, downloads the new
 * APK, and hands it to Android's installer. Android always shows its own Install button;
 * it only accepts the file because it is signed with the same key as the installed app.
 */
final class Updater {

    private static final String RELEASE_URL =
            "https://api.github.com/repos/sudo-apexo/study-colors-android/releases/tags/latest";
    private static final Pattern BUILD = Pattern.compile("build:\\s*(\\d+)");

    private final Activity activity;
    private final WebView web;
    private File ready;
    private String downloadUrl;
    private volatile boolean busy;
    private boolean pendingInstall;

    Updater(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    int installedBuild() {
        try {
            return (int) activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    String installedName() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** Sends {state, ...} to the page. */
    private void report(String state, String extra) {
        final String js = "window.__studyUpdate&&window.__studyUpdate({state:'" + state + "'"
                + (extra == null ? "" : "," + extra) + "})";
        activity.runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    void check() {
        if (busy) return;
        busy = true;
        if (ready != null) {
            ready.delete();
            ready = null;
        }
        report("checking", null);
        new Thread(() -> {
            try {
                JSONObject json = null;
                Exception last = null;
                for (int attempt = 0; attempt < 3 && json == null; attempt++) {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(RELEASE_URL).openConnection();
                        c.setConnectTimeout(12000);
                        c.setReadTimeout(12000);
                        c.setUseCaches(false);
                        c.setRequestProperty("Accept", "application/vnd.github+json");
                        c.setRequestProperty("User-Agent", "StudyColors-Android");
                        if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
                        json = new JSONObject(read(c.getInputStream()));
                    } catch (Exception e) {
                        last = e;
                        Thread.sleep(700);
                    }
                }
                if (json == null) throw last != null ? last : new Exception("no data");
                Matcher m = BUILD.matcher(json.optString("body", ""));
                int remote = m.find() ? Integer.parseInt(m.group(1)) : 0;
                String url = null;
                JSONArray assets = json.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if ("StudyColors.apk".equals(a.optString("name"))) {
                        url = a.optString("browser_download_url");
                    }
                }
                if (url == null || !url.startsWith("https://github.com/sudo-apexo/")) {
                    throw new Exception("no apk");
                }
                downloadUrl = url;
                report(remote > installedBuild() ? "available" : "current", "build:" + remote);
            } catch (Exception e) {
                report("error", "code:'check'");
            } finally {
                busy = false;
            }
        }).start();
    }

    void install() {
        if (ready != null && ready.exists()) {
            launchInstaller();
            return;
        }
        if (busy) return;
        if (downloadUrl == null) {
            // Nothing checked yet: check first, the page then asks again if an update exists.
            check();
            return;
        }
        busy = true;
        report("downloading", "percent:0");
        final String url = downloadUrl;
        new Thread(() -> {
            try {
                File dir = new File(activity.getCacheDir(), "updates");
                dir.mkdirs();
                File out = new File(dir, "StudyColors.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
                long total = c.getContentLengthLong();
                long done = 0;
                int last = -1;
                try (InputStream in = c.getInputStream();
                     FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int p = (int) (done * 100 / total);
                            if (p != last) {
                                last = p;
                                report("downloading", "percent:" + p);
                            }
                        }
                    }
                }
                if (done < 100000) throw new Exception("too small");
                android.content.pm.PackageInfo info = activity.getPackageManager()
                        .getPackageArchiveInfo(out.getAbsolutePath(), 0);
                if (info == null || info.getLongVersionCode() <= installedBuild()) {
                    out.delete();
                    throw new Exception("not newer");
                }
                ready = out;
                report("ready", null);
                activity.runOnUiThread(this::launchInstaller);
            } catch (Exception e) {
                report("error", "code:'download'");
            } finally {
                busy = false;
            }
        }).start();
    }

    /** Called when the app comes back to the front, e.g. after the permission screen. */
    void resumed() {
        if (ready != null && ready.exists() && !busy && activity.getPackageManager().canRequestPackageInstalls()) {
            if (pendingInstall) {
                pendingInstall = false;
                launchInstaller();
            } else {
                report("ready", null);
            }
        }
    }

    private void launchInstaller() {
        if (ready == null || !ready.exists()) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = true;
            report("permission", null);
            Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(s);
            return;
        }
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", ready);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        report("installing", null);
        activity.startActivity(i);
    }

    private static String read(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }
}
