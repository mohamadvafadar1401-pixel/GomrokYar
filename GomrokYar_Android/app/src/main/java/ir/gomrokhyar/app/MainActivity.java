package ir.gomrokhyar.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import android.util.Base64;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.List;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraOutputUri;
    private File cameraOutputFile;
    private static final int FILE_CHOOSER_REQUEST = 501;
    private static final int CAMERA_REQUEST = 502;
    private static final int REPORT_SAVE_REQUEST = 503;
    private static final int FILE_SAVE_REQUEST = 504;
    private byte[] pendingReportBytes;
    private String pendingReportName;
    private String pendingMimeType = "application/octet-stream";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(18, 59, 53));
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new ReportBridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                clearPendingCallback();
                fileCallback = callback;
                if (params.isCaptureEnabled()) {
                    launchRearCamera();
                    return true;
                }
                Intent intent;
                try {
                    intent = params.createIntent();
                } catch (Exception ignored) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    clearPendingCallback();
                    Toast.makeText(MainActivity.this, "امکان باز کردن گالری وجود ندارد.", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void launchRearCamera() {
        try {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (intent.resolveActivity(getPackageManager()) == null) {
                clearPendingCallback();
                Toast.makeText(this, "برنامه دوربین روی گوشی پیدا نشد.", Toast.LENGTH_LONG).show();
                return;
            }
            cameraOutputFile = File.createTempFile("gomrokyar_scan_", ".jpg", getCacheDir());
            cameraOutputUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cameraOutputFile);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            intent.putExtra("android.intent.extras.CAMERA_FACING", 0);
            intent.putExtra("android.intent.extras.LENS_FACING_FRONT", false);
            intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", false);
            intent.setClipData(ClipData.newRawUri("GomrokYar scan", cameraOutputUri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            List<ResolveInfo> handlers = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo handler : handlers) {
                grantUriPermission(handler.activityInfo.packageName, cameraOutputUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            startActivityForResult(intent, CAMERA_REQUEST);
        } catch (Exception e) {
            clearPendingCallback();
            Toast.makeText(this, "باز کردن دوربین ناموفق بود؛ دوباره تلاش کن.", Toast.LENGTH_LONG).show();
        }
    }

    private class ReportBridge {
        @JavascriptInterface public void saveGzipFile(String fileName, String mimeType, String content) {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                    gzip.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
                }
                saveFile(fileName, mimeType, Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "فشرده‌سازی فایل پشتیبان ناموفق بود.", Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface public String decompressGzip(String base64) {
            try {
                byte[] compressed = Base64.decode(base64, Base64.DEFAULT);
                try (GZIPInputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed));
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192];
                    int count;
                    while ((count = gzip.read(chunk)) != -1) {
                        if (output.size() + count > 150 * 1024 * 1024) throw new java.io.IOException("Backup too large");
                        output.write(chunk, 0, count);
                    }
                    return output.toString("UTF-8");
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("GZIP backup could not be decompressed", e);
            }
        }

        @JavascriptInterface public void saveReport(String fileName, String base64) {
            saveFile(fileName, "text/html", base64);
        }
        @JavascriptInterface public void saveFile(String fileName, String mimeType, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String safeName = (fileName == null || fileName.trim().isEmpty())
                        ? "gomrokyar-export.dat"
                        : fileName.replaceAll("[\\\\/:*?\\\"<>|]", "_");
                String safeMime = (mimeType == null || mimeType.trim().isEmpty())
                        ? "application/octet-stream" : mimeType;
                runOnUiThread(() -> {
                    pendingReportBytes = bytes;
                    pendingReportName = safeName;
                    pendingMimeType = safeMime;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(pendingMimeType);
                    intent.putExtra(Intent.EXTRA_TITLE, pendingReportName);
                    try { startActivityForResult(intent, FILE_SAVE_REQUEST); }
                    catch (Exception e) {
                        pendingReportBytes = null;
                        pendingReportName = null;
                        pendingMimeType = "application/octet-stream";
                        Toast.makeText(MainActivity.this, "امکان باز کردن محل ذخیره فایل وجود ندارد.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "آماده‌سازی فایل ناموفق بود.", Toast.LENGTH_LONG).show());
            }
        }
    }

    private void clearPendingCallback() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REPORT_SAVE_REQUEST || requestCode == FILE_SAVE_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && pendingReportBytes != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out == null) throw new java.io.IOException("Cannot open destination");
                    out.write(pendingReportBytes);
                    Toast.makeText(this, "فایل گزارش با موفقیت ذخیره شد.", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "ذخیره فایل گزارش ناموفق بود.", Toast.LENGTH_LONG).show();
                }
            }
            pendingReportBytes = null;
            pendingReportName = null;
            pendingMimeType = "application/octet-stream";
            return;
        }
        if (requestCode == CAMERA_REQUEST) {
            Uri result = null;
            if (resultCode == Activity.RESULT_OK && cameraOutputFile != null
                    && cameraOutputFile.exists() && cameraOutputFile.length() > 0) {
                result = cameraOutputUri;
            } else if (cameraOutputFile != null) {
                cameraOutputFile.delete();
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(result == null ? null : new Uri[]{result});
                fileCallback = null;
            }
            if (cameraOutputUri != null) {
                revokeUriPermission(cameraOutputUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            cameraOutputUri = null;
            cameraOutputFile = null;
            return;
        }
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            Uri[] result = (resultCode == Activity.RESULT_OK && data != null && data.getData() != null)
                    ? new Uri[]{data.getData()} : null;
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        clearPendingCallback();
        if (cameraOutputFile != null && cameraOutputFile.exists()) cameraOutputFile.delete();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
