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
import android.os.CancellationSignal;
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
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewFeature;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.regex.Pattern;

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

    // Google sign-in hand-off: the browser returns a token through a custom address.
    private static final String AUTH_SCHEME = "io.github.sudoapexo.studycolors";
    private static final long AUTH_WINDOW_MS = 10 * 60 * 1000L;
    private static final Pattern STATE_FORMAT = Pattern.compile("^[a-f0-9]{16,64}$");
    private static final Pattern TOKEN_FORMAT =
            Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
    private static final Pattern WEB_CLIENT_FORMAT =
            Pattern.compile("^[0-9]+-[a-z0-9]+\\.apps\\.googleusercontent\\.com$");
    private CancellationSignal googleSignal;
    private String pendingGoogleToken;
    private long pendingGoogleTokenAt;

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
        handleAuthIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (handleAuthIntent(intent)) {
            webView.evaluateJavascript(
                    "window.__studyGoogleNotify&&window.__studyGoogleNotify()", null);
        }
    }

    /** Accepts the sign-in token only if it carries the secret created when sign-in began. */
    private boolean handleAuthIntent(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return false;
        }
        Uri uri = intent.getData();
        // Use each link once, even if the activity is re-created with the same intent.
        intent.setData(null);
        if (!AUTH_SCHEME.equals(uri.getScheme()) || !"auth".equals(uri.getHost())) {
            return false;
        }
        String state = uri.getQueryParameter("state");
        String token = uri.getQueryParameter("token");
        String expected = prefs.getString("google_state", "");
        long started = prefs.getLong("google_state_at", 0L);
        prefs.edit().remove("google_state").remove("google_state_at").apply();
        if (state == null || token == null || expected.isEmpty()
                || !STATE_FORMAT.matcher(state).matches()
                || token.length() > 8192 || !TOKEN_FORMAT.matcher(token).matches()
                || System.currentTimeMillis() - started > AUTH_WINDOW_MS
                || !MessageDigest.isEqual(
                        state.getBytes(StandardCharsets.UTF_8),
                        expected.getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        pendingGoogleToken = token;
        pendingGoogleTokenAt = System.currentTimeMillis();
        return true;
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
        if (googleSignal != null) {
            googleSignal.cancel();
            googleSignal = null;
        }
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

    private void notifyGoogleToken() {
        webView.evaluateJavascript("window.__studyGoogleNotify&&window.__studyGoogleNotify()", null);
    }

    private void sendGoogleError(String code) {
        webView.evaluateJavascript(
                "window.__studyGoogleError&&window.__studyGoogleError('" + code + "')", null);
    }

    /** Shows the phone's own Google account picker and hands the ID token to the page. */
    private void nativeGoogleSignIn(String webClientId) {
        if (googleSignal != null) {
            googleSignal.cancel();
        }
        googleSignal = new CancellationSignal();
        CredentialManager manager = CredentialManager.create(this);
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(webClientId).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();
        manager.getCredentialAsync(this, request, googleSignal, ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        Credential credential = result.getCredential();
                        if (credential instanceof CustomCredential
                                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                        .equals(credential.getType())) {
                            try {
                                GoogleIdTokenCredential google = GoogleIdTokenCredential
                                        .createFrom(((CustomCredential) credential).getData());
                                pendingGoogleToken = google.getIdToken();
                                pendingGoogleTokenAt = System.currentTimeMillis();
                                notifyGoogleToken();
                                return;
                            } catch (RuntimeException ignored) {
                                // Falls through to the error below.
                            }
                        }
                        sendGoogleError("native-failed");
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        if (e instanceof GetCredentialCancellationException) {
                            sendGoogleError("cancelled");
                        } else if (e instanceof NoCredentialException) {
                            sendGoogleError("no-account");
                        } else {
                            sendGoogleError("native-failed");
                        }
                    }
                });
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
        public void googleSignIn(String helperUrl, String webClientId) {
            // Preferred: the phone's own account picker, when the Web client ID is configured.
            if (webClientId != null && WEB_CLIENT_FORMAT.matcher(webClientId).matches()) {
                final String clientId = webClientId;
                runOnUiThread(() -> nativeGoogleSignIn(clientId));
                return;
            }
            final Uri base;
            try {
                base = Uri.parse(helperUrl);
            } catch (RuntimeException e) {
                return;
            }
            if (base == null || !"https".equals(base.getScheme()) || base.getHost() == null) {
                return;
            }
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            final String state = hex.toString();
            prefs.edit().putString("google_state", state)
                    .putLong("google_state_at", System.currentTimeMillis()).apply();
            final Uri target = base.buildUpon()
                    .appendQueryParameter("platform", "android")
                    .appendQueryParameter("state", state)
                    .build();
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, target));
                } catch (RuntimeException e) {
                    Toast.makeText(MainActivity.this, R.string.no_browser, Toast.LENGTH_LONG).show();
                }
            });
        }

        /** Returns the sign-in token once, then forgets it. */
        @JavascriptInterface
        public String takeGoogleToken() {
            String token = pendingGoogleToken;
            long at = pendingGoogleTokenAt;
            pendingGoogleToken = null;
            if (token == null || System.currentTimeMillis() - at > AUTH_WINDOW_MS) {
                return "";
            }
            return token;
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
