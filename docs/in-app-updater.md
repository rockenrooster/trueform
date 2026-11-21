# In-app updater: download the release APK and open Android's installer

How Trueform updates itself from its GitHub releases without a browser, written so Raveform, Glowtide or any other
app with the same release pipeline can do the same. Reference implementation (Kotlin, Compose):
`app/src/main/java/dev/trueform/player/update/Updater.kt` (download, checks, installer), `UpdateUi.kt` (the pill and
the sheet), `app/src/test/.../UpdaterTest.kt`, `.github/workflows/release.yml`. Below is the same thing as one Java
class for the SDL games, whose activities are Java.

## What the user sees

1. The app reads the latest GitHub release; when its tag `build-<n>` is newer, it shows "Update · build n".
2. Tap: the APK downloads inside the app (with progress), is checked, and Android's own "Do you want to update this
   app?" dialog appears. Update, done; Android restarts nothing, the user opens the app again.
3. The first time only, Android opens "Install unknown apps" for the app instead: allow it, go back, tap again.

## What has to be true

On the release side (Raveform and Glowtide already do all of this except the version code):

- A public GitHub repository with one release per build: tag `build-<commit count>`, assets `<app>.apk` and
  `<app>.apk.sha256` (`sha256sum` format: `<hex>  <app>.apk`).
- Every release signed with the same release key. Android refuses an update signed with another key, and the app
  checks it before asking.
- **`versionCode` = the build number** (the commit count). Android only knows "newer" by the version code; the
  checks below refuse an APK that is not newer. Trueform: `versionCode = if (buildNumber > 0) buildNumber else 1`.
  Raveform and Glowtide still have `versionCode 1` in `android/app/build.gradle`: change it to the number they
  already pass to CMake, e.g. `versionCode((gtBuildNumber as int) > 0 ? (gtBuildNumber as int) : 1)`. Local builds
  stay at 1 and never ask GitHub (build number 0). The copy installed today has version code 1, so the first
  release with the updater is installed the old way, from the browser, once; from then on it updates itself.

In the app:

- Manifest: `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />` (INTERNET is already
  there) and, inside `<application>`, `<receiver android:name=".ApkInstallReceiver" android:exported="false" />`.
- No storage permission and no FileProvider: the APK goes to the app's cache and into a `PackageInstaller` session.

## The flow, step by step

1. If `PackageManager.canRequestPackageInstalls()` is false, open `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`
   with `package:<our package>` and stop; the user allows it once and taps again.
2. Read `https://api.github.com/repos/<owner>/<repo>/releases/latest`; find the `<app>.apk` and `<app>.apk.sha256`
   assets (their `browser_download_url` and `size`).
3. Download the APK into `cacheDir/updates/` on a background thread, reporting progress; the URL redirects to
   GitHub's file host, which `HttpURLConnection` follows.
4. Check, refusing on any failure: the size matches; the SHA-256 matches the `.sha256` asset; the file is an app
   package (`getPackageArchiveInfo`) with our package name, a higher version code than the installed one, and the
   same signing certificates.
5. Open a `PackageInstaller` session (`MODE_FULL_INSTALL`, our package name, on Android 12+
   `setRequireUserAction(USER_ACTION_REQUIRED)`), write the APK, `fsync`, and commit with a `PendingIntent` to our
   receiver: an explicit intent, `FLAG_MUTABLE` on Android 12+ (the installer adds its result as extras; an
   immutable intent would lose them).
6. The receiver gets `STATUS_PENDING_USER_ACTION` with the installer's confirmation `Intent` in `EXTRA_INTENT`:
   start it with `FLAG_ACTIVITY_NEW_TASK`. That is the system dialog. `STATUS_SUCCESS` needs nothing (Android
   replaces the process); anything else carries a reason in `EXTRA_STATUS_MESSAGE` for the user.

## Pitfalls met in Trueform

- The installer and the settings page need a real `Activity` to start from. In Compose, a bottom sheet's
  `LocalContext` is a `ContextWrapper`, not the activity: unwrap it (`UpdateUi.activity()`). Builds 2 and 3 could not
  install because of this, and could not update themselves to the fix.
- Ask GitHub at most about once a minute: unauthenticated API calls are limited to 60 an hour per address.
- Clean `cacheDir/updates/` on start; leftovers from a failed attempt are 10–30 MB.
- A debug-signed or differently signed copy cannot be updated by a release: the check says "signed with another
  key". Test with release builds: install the previous release from GitHub, then update it in the app.
- Android 12+ shows its own dialog every time (`USER_ACTION_REQUIRED`); silent updates are for app stores.

## Drop-in for the SDL games (Java)

Two files next to the activity, package changed to the game's. `ApkUpdater.downloadAndInstall` does steps 1–5;
`ApkInstallReceiver` does step 6. The listener is called on the main thread.

```java
package dev.example.game;   // the game's package

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Downloads the latest GitHub release's APK, checks it (size, SHA-256, same app, same signing key, newer version
 * code) and hands it to Android's installer, which asks the user to confirm. No browser, no storage permission.
 */
public final class ApkUpdater {
    public interface Listener {
        void onProgress(float fraction);   // 0..1 while downloading
        void onFailed(String reason);
    }

    private interface Progress { void bytes(long n); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;
    private static volatile Listener listener;
    private static volatile Context appContext;

    /** repo "owner/name"; asset "game.apk" (its checksum is "game.apk.sha256" in the same release). */
    public static void downloadAndInstall(Activity activity, String repo, String asset, Listener l) {
        listener = l;
        appContext = activity.getApplicationContext();
        if (!activity.getPackageManager().canRequestPackageInstalls()) {   // once: allow, come back, tap again
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        if (busy) return;
        busy = true;
        final Context app = appContext;
        new Thread(() -> {
            File dir = new File(app.getCacheDir(), "updates");
            File apk = new File(dir, asset);
            try {
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                dir.mkdirs();
                JSONObject release = new JSONObject(new String(
                        get("https://api.github.com/repos/" + repo + "/releases/latest", 1 << 20), StandardCharsets.UTF_8));
                String apkUrl = null, shaUrl = null;
                long size = 0;
                JSONArray assets = release.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (asset.equals(a.optString("name"))) { apkUrl = a.optString("browser_download_url"); size = a.optLong("size"); }
                    if ((asset + ".sha256").equals(a.optString("name"))) shaUrl = a.optString("browser_download_url");
                }
                if (apkUrl == null || shaUrl == null) throw new IOException("the release has no " + asset + " or no checksum");
                String want = new String(get(shaUrl, 4096), StandardCharsets.UTF_8).trim().split("\\s+")[0].toLowerCase();
                final long total = size;
                download(apkUrl, apk, n -> { if (total > 0) MAIN.post(() -> l.onProgress(Math.min(1f, (float) n / total))); });
                if (total > 0 && apk.length() != total) throw new IOException("the download is incomplete");
                if (!sha256(apk).equals(want)) throw new IOException("the checksum does not match");
                String why = verify(app, apk);
                if (why != null) throw new IOException(why);
                install(app, apk);   // the installer's dialog follows through ApkInstallReceiver
            } catch (Exception e) {
                apk.delete();
                failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } finally {
                busy = false;
            }
        }, "apk-updater").start();
    }

    /** Also called by ApkInstallReceiver when the installer gives up. */
    static void failed(String reason) {
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onFailed(reason);
            else if (appContext != null) Toast.makeText(appContext, "Update not installed: " + reason, Toast.LENGTH_LONG).show();
        });
    }

    /** null when the file is this app, signed with this app's key and newer; otherwise the reason. */
    @SuppressWarnings("deprecation")
    static String verify(Context c, File apk) throws PackageManager.NameNotFoundException {
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo theirs = pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (theirs == null) return "not an app package";
        if (!c.getPackageName().equals(theirs.packageName)) return "it is another app (" + theirs.packageName + ")";
        PackageInfo mine = pm.getPackageInfo(c.getPackageName(), flags);
        if (version(theirs) <= version(mine)) return "it is not newer than this build";
        Set<String> a = signers(theirs), b = signers(mine);
        if (a.isEmpty() || !a.equals(b)) return "it is signed with another key";
        return null;
    }

    @SuppressWarnings("deprecation")
    private static long version(PackageInfo p) { return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode; }

    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo p) {
        Signature[] s = Build.VERSION.SDK_INT >= 28 ? (p.signingInfo != null ? p.signingInfo.getApkContentsSigners() : null) : p.signatures;
        Set<String> out = new HashSet<>();
        if (s != null) for (Signature x : s) out.add(x.toCharsString());
        return out;
    }

    private static void install(Context c, File apk) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("update.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent done = PendingIntent.getBroadcast(c, id, new Intent(c, ApkInstallReceiver.class), flags);
            session.commit(done.getIntentSender());
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "apk-updater");
        return c;
    }

    private static byte[] get(String url, int limit) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > limit) throw new IOException("response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File to, Progress progress) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Accept", "application/octet-stream");
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) { out.write(buf, 0, n); total += n; progress.bytes(total); }
            }
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte x : md.digest()) hex.append(String.format("%02x", x));
        return hex.toString();
    }
}
```

```java
package dev.example.game;   // the game's package

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** The installer's answers: show its confirmation dialog, or report why it stopped. */
public final class ApkInstallReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (status != PackageInstaller.STATUS_SUCCESS) {   // success: Android replaces this process
            String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            ApkUpdater.failed(why != null ? why : "the installer stopped");
        }
    }
}
```

## Wiring it into the SDL games

Today the update button calls `SDL_OpenURL(...)` (Glowtide `src/game/screens.cpp:120`, Raveform
`src/game/screens.cpp:634`), which opens the browser. Replace that call with a call into Java, and the rest stays:
the activity's `checkForUpdate()` and `nativeUpdateFound(build)` already find the newer build.

In the activity (Glowtide shown; Raveform the same with its names):

```java
/** The title's update button (called from native code): download, check, open Android's installer. */
static void startUpdate() {
    Activity a = (Activity) SDLActivity.getContext();
    a.runOnUiThread(() -> ApkUpdater.downloadAndInstall(a, "rockenrooster/glowtide", "glowtide.apk",
        new ApkUpdater.Listener() {
            @Override public void onProgress(float f) { nativeUpdateProgress(f); }
            @Override public void onFailed(String why) { nativeUpdateFailed(why); }
        }));
}
static native void nativeUpdateProgress(float fraction);   // e.g. the button reads "Downloading 42 %"
static native void nativeUpdateFailed(String reason);      // e.g. "Update failed: <reason> · try again"
```

In native code, on tap (SDL's JNI helpers; the SDL main thread is a Java thread, so `FindClass` sees the app's
classes):

```cpp
JNIEnv* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());   // SDL2: SDL_AndroidGetJNIEnv()
jclass cls = env->FindClass("dev/glowtide/game/GlowtideActivity");
jmethodID m = env->GetStaticMethodID(cls, "startUpdate", "()V");
env->CallStaticVoidMethod(cls, m);
env->DeleteLocalRef(cls);
```

The two callbacks run on Android's main thread: hand the value to the game thread the way `nativeUpdateFound`
already does. Without them, the smallest version leaves the listener's methods to a `Toast` ("Downloading
update…" once, then the reason if it fails); the installer's dialog appears on its own when the download is done.

## Checking it

1. Build a release with the new code and publish it (push to `main`); install it from the browser once.
2. Publish one more commit; open the game: the button offers the new build; tap it.
3. Expect: progress, then (first time) the "Install unknown apps" page; allow, back, tap again; Android's update
   dialog; Update; the game closes; open it: the title shows the new build.
4. A wrong checksum, another key or an older build must end in `onFailed` with the reason, nothing installed.
