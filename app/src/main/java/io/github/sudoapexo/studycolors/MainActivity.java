package io.github.sudoapexo.studycolors;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewFeature;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shows the Study Colors page (assets/index.html) full screen and connects it to Android:
 * backup files, the file picker, exam notifications, the Back button and the system bar colors.
 */
public class MainActivity extends ComponentActivity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final String CHANNEL_ID = "exam_reminders";

    private FrameLayout root;
    private WebView webView;
    private SharedPreferences prefs;

    private ValueCallback<Uri[]> fileCallback;
    private String pendingSaveText;

    private ActivityResultLauncher<Intent> chooserLauncher;
    private ActivityResultLauncher<String> saveLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("study_colors", MODE_PRIVATE);

        // These must be registered before the activity starts.
        chooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (fileCallback == null) {
                        return;
                    }
                    Uri[] uris = WebChromeClient.FileChooserParams.parseResult(
                            result.getResultCode(), result.getData());
                    fileCallback.onReceiveValue(uris);
                    fileCallback = null;
                });

        saveLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                    String text = pendingSaveText;
                    pendingSaveText = null;
                    if (uri == null || text == null) {
                        return;
                    }
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) {
                            throw new IOException("No output stream");
                        }
                        out.write(text.getBytes(StandardCharsets.UTF_8));
                        Toast.makeText(this, R.string.backup_saved, Toast.LENGTH_SHORT).show();
                    } catch (IOException e) {
                        Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show();
                    }
                });

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> sendNotificationResult(granted ? "granted" : "denied"));

        // Draw behind the system bars, then pad the content by their size.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.surface));
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });

        setUpWebView();
        setUpBackButton();

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(START_URL);
        }
    }

    private void setUpWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false);
        }

        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setHapticFeedbackEnabled(false);
        webView.setOnLongClickListener(v -> true);
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) {
                    return false;
                }
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (RuntimeException ignored) {
                        // No browser installed.
                    }
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    chooserLauncher.launch(params.createIntent());
                } catch (RuntimeException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
    }

    /** Back closes an open window in the page first; otherwise it leaves the app. */
    private void setUpBackButton() {
        final String script = "(function(){"
                + "var o=document.querySelector('.sheet.open,.dialog.open');"
                + "if(o){document.dispatchEvent(new KeyboardEvent('keydown',"
                + "{key:'Escape',bubbles:true,cancelable:true}));return 1;}"
                + "return 0;})()";
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                webView.evaluateJavascript(script, value -> {
                    if (!"1".equals(value)) {
                        finish();
                    }
                });
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }

    // ---- helpers -------------------------------------------------------------

    private void applyBars(int color) {
        root.setBackgroundColor(color);
        double luma = (0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0;
        boolean lightBackground = luma > 0.5;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(lightBackground);
        controller.setAppearanceLightNavigationBars(lightBackground);
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        return manager == null || manager.areNotificationsEnabled();
    }

    private void sendNotificationResult(String state) {
        webView.evaluateJavascript(
                "window.__studyNotifResult&&window.__studyNotifResult('" + state + "')", null);
    }

    private static String safeFileName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "study-colors-backup.json" : cleaned;
    }

    /** Functions the page can call. Added to the page as window.AndroidBridge. */
    private class Bridge {

        @JavascriptInterface
        public void setBars(int red, int green, int blue) {
            final int color = Color.rgb(red, green, blue);
            runOnUiThread(() -> applyBars(color));
        }

        @JavascriptInterface
        public String notifPermission() {
            if (hasNotificationPermission()) {
                return "granted";
            }
            return prefs.getBoolean("notification_asked", false) ? "denied" : "default";
        }

        @JavascriptInterface
        public void requestNotifPermission() {
            runOnUiThread(() -> {
                if (hasNotificationPermission()) {
                    sendNotificationResult("granted");
                    return;
                }
                prefs.edit().putBoolean("notification_asked", true).apply();
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                } else {
                    sendNotificationResult("denied");
                }
            });
        }

        @JavascriptInterface
        public void showNotification(String title, String body) {
            if (!hasNotificationPermission()) {
                return;
            }
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) {
                return;
            }
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(MainActivity.this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pending = PendingIntent.getActivity(MainActivity.this, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(MainActivity.this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build();
            manager.notify((int) (System.currentTimeMillis() & 0x7fffffff), notification);
        }

        @JavascriptInterface
        public void saveFile(String name, String text) {
            final String fileName = safeFileName(name);
            final String content = text;
            runOnUiThread(() -> {
                pendingSaveText = content;
                saveLauncher.launch(fileName);
            });
        }
    }
}
