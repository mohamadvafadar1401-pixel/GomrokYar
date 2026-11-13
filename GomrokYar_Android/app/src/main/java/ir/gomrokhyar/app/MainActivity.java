package ir.gomrokhyar.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.ContentUris;
import android.database.Cursor;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import android.util.Base64;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.PushbackInputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import org.json.JSONArray;
import org.json.JSONObject;

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
    private File pendingReportFile;
    private OutputStream chunkedSaveStream;
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
        webView.addJavascriptInterface(new OcrBridge(), "AndroidOcr");
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

    private class OcrBridge {
        @JavascriptInterface public String recognizeFirstWeight(String base64) {
            TessBaseAPI api = null;
            Bitmap bitmap = null;
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) return "";
                File root = new File(getFilesDir(), "tesseract");
                File data = new File(root, "tessdata");
                if (!data.exists() && !data.mkdirs()) return "";
                File trained = new File(data, "fas.traineddata");
                if (!trained.exists() || trained.length() < 100000) {
                    try (InputStream in = getAssets().open("tessdata/fas.traineddata");
                         FileOutputStream out = new FileOutputStream(trained)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
                    }
                }
                api = new TessBaseAPI();
                if (!api.init(root.getAbsolutePath(), "fas")) return "";
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE);
                api.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "0123456789۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩٬,.");
                api.setImage(bitmap);
                String result = api.getUTF8Text();
                return result == null ? "" : result;
            } catch (Exception e) {
                return "";
            } finally {
                if (bitmap != null) bitmap.recycle();
                if (api != null) { api.clear(); api.end(); }
            }
        }
    }

    private class ReportBridge {
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

        @JavascriptInterface public synchronized boolean beginFileSave(String fileName, String mimeType) {
            try {
                if (chunkedSaveStream != null) { chunkedSaveStream.close(); chunkedSaveStream = null; }
                if (pendingReportFile != null) { pendingReportFile.delete(); pendingReportFile = null; }
                String safeName = (fileName == null || fileName.trim().isEmpty())
                        ? "gomrokyar-export.dat"
                        : fileName.replaceAll("[\\\\/:*?\\\"<>|]", "_");
                pendingReportName = safeName;
                pendingMimeType = (mimeType == null || mimeType.trim().isEmpty())
                        ? "application/octet-stream" : mimeType;
                pendingReportBytes = null;
                pendingReportFile = File.createTempFile("gomrokyar-export-", ".tmp", getCacheDir());
                chunkedSaveStream = new java.io.FileOutputStream(pendingReportFile, false);
                return true;
            } catch (Exception e) {
                if (pendingReportFile != null) pendingReportFile.delete();
                pendingReportFile = null;
                chunkedSaveStream = null;
                return false;
            }
        }

        @JavascriptInterface public synchronized boolean beginGzipFileSave(String fileName, String mimeType) {
            try {
                if (chunkedSaveStream != null) { chunkedSaveStream.close(); chunkedSaveStream = null; }
                if (pendingReportFile != null) { pendingReportFile.delete(); pendingReportFile = null; }
                String safeName = (fileName == null || fileName.trim().isEmpty())
                        ? "gomrokyar-export.json.gz"
                        : fileName.replaceAll("[\\\\/:*?\\\"<>|]", "_");
                pendingReportName = safeName;
                pendingMimeType = (mimeType == null || mimeType.trim().isEmpty())
                        ? "application/gzip" : mimeType;
                pendingReportBytes = null;
                pendingReportFile = File.createTempFile("gomrokyar-export-", ".tmp", getCacheDir());
                chunkedSaveStream = new GZIPOutputStream(new FileOutputStream(pendingReportFile, false), 32768);
                return true;
            } catch (Exception e) {
                try { if (chunkedSaveStream != null) chunkedSaveStream.close(); } catch (Exception ignored) { }
                if (pendingReportFile != null) pendingReportFile.delete();
                pendingReportFile = null;
                chunkedSaveStream = null;
                return false;
            }
        }

        @JavascriptInterface public String decompressGzip(String base64) {
            try {
                byte[] compressed = Base64.decode(base64, Base64.DEFAULT);
                try (GZIPInputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed));
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[16384];
                    int count;
                    while ((count = gzip.read(chunk)) != -1) {
                        if (output.size() + count > 512 * 1024 * 1024) throw new java.io.IOException("Backup exceeds 512 MB restore limit");
                        output.write(chunk, 0, count);
                    }
                    return output.toString("UTF-8");
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid GZIP backup", e);
            }
        }

        @JavascriptInterface public synchronized boolean appendFileSaveChunk(String base64) {
            try {
                if (chunkedSaveStream == null || base64 == null) return false;
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                chunkedSaveStream.write(bytes);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface public synchronized boolean finishFileSave() {
            try {
                if (chunkedSaveStream == null || pendingReportFile == null) return false;
                chunkedSaveStream.flush();
                chunkedSaveStream.close();
                chunkedSaveStream = null;
                final String saveName = pendingReportName;
                final String saveMime = pendingMimeType;
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(saveMime);
                    intent.putExtra(Intent.EXTRA_TITLE, saveName);
                    try { startActivityForResult(intent, FILE_SAVE_REQUEST); }
                    catch (Exception e) {
                        if (pendingReportFile != null) pendingReportFile.delete();
                        pendingReportFile = null;
                        pendingReportName = null;
                        pendingMimeType = "application/octet-stream";
                        Toast.makeText(MainActivity.this, "امکان باز کردن محل ذخیره فایل وجود ندارد.", Toast.LENGTH_LONG).show();
                    }
                });
                return true;
            } catch (Exception e) {
                cancelFileSave();
                return false;
            }
        }


        @JavascriptInterface public synchronized boolean finishAutomaticBackup() {
            Uri insertedUri = null;
            try {
                if (chunkedSaveStream == null || pendingReportFile == null || !pendingReportFile.exists()) return false;
                chunkedSaveStream.flush();
                chunkedSaveStream.close();
                chunkedSaveStream = null;
                String saveName = pendingReportName == null ? "gomrokyar-auto-backup.json" : pendingReportName;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    String relativePath = Environment.DIRECTORY_DOWNLOADS + "/GomrokYar/AutoBackups";
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, saveName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, pendingMimeType == null ? "application/json" : pendingMimeType);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    insertedUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (insertedUri == null) throw new java.io.IOException("Could not create automatic backup in Downloads");
                    try (InputStream in = new FileInputStream(pendingReportFile);
                         OutputStream out = getContentResolver().openOutputStream(insertedUri, "w")) {
                        if (out == null) throw new java.io.IOException("Could not open automatic backup destination");
                        byte[] buffer = new byte[32768];
                        int count;
                        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    }
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    if (getContentResolver().update(insertedUri, ready, null, null) <= 0) throw new java.io.IOException("Could not finalize automatic backup");
                } else {
                    File documents = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                    if (documents == null) throw new java.io.IOException("App documents directory unavailable");
                    File directory = new File(documents, "GomrokYar/AutoBackups");
                    if (!directory.exists() && !directory.mkdirs()) throw new java.io.IOException("Could not create automatic backup directory");
                    File destination = new File(directory, saveName);
                    try (InputStream in = new FileInputStream(pendingReportFile);
                         OutputStream out = new FileOutputStream(destination, false)) {
                        byte[] buffer = new byte[32768];
                        int count;
                        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    }
                }
                if (pendingReportFile != null) pendingReportFile.delete();
                pendingReportFile = null;
                pendingReportName = null;
                pendingMimeType = "application/octet-stream";
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "پشتیبان خودکار گمرکیار ذخیره شد.", Toast.LENGTH_LONG).show());
                return true;
            } catch (Exception e) {
                if (insertedUri != null) {
                    try { getContentResolver().delete(insertedUri, null, null); } catch (Exception ignored) { }
                }
                return false;
            }
        }

        private int countBackupRecords(InputStream input) {
            try {
                PushbackInputStream probe = new PushbackInputStream(input, 2);
                int first = probe.read(), second = probe.read();
                if (second >= 0) probe.unread(second);
                if (first >= 0) probe.unread(first);
                InputStream decoded = first == 0x1f && second == 0x8b ? new GZIPInputStream(probe) : probe;
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                int read;
                while ((read = decoded.read(chunk)) != -1) {
                    if (buffer.size() + read > 150 * 1024 * 1024) return 0;
                    buffer.write(chunk, 0, read);
                }
                JSONObject json = new JSONObject(buffer.toString("UTF-8"));
                JSONArray records = json.optJSONArray("records");
                return records == null ? 0 : records.length();
            } catch (Exception ignored) { return 0; }
        }

        @JavascriptInterface public synchronized String getAutomaticBackups() {
            JSONArray result = new JSONArray();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    String relativePath = Environment.DIRECTORY_DOWNLOADS + "/GomrokYar/AutoBackups/";
                    String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE};
                    try (Cursor cursor = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            projection, MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                                    + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                            new String[]{relativePath, "gomrokyar-auto-%"},
                            MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                        if (cursor != null) {
                            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
                            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
                            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
                            while (cursor.moveToNext()) {
                                long id = cursor.getLong(idCol);
                                Uri uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                                int count = 0;
                                try (InputStream in = getContentResolver().openInputStream(uri)) {
                                    if (in != null) count = countBackupRecords(in);
                                } catch (Exception ignored) { }
                                JSONObject item = new JSONObject();
                                item.put("name", cursor.getString(nameCol));
                                item.put("modified", cursor.getLong(dateCol) * 1000L);
                                item.put("size", cursor.getLong(sizeCol));
                                item.put("recordCount", count);
                                result.put(item);
                            }
                        }
                    }
                } else {
                    File documents = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                    File directory = documents == null ? null : new File(documents, "GomrokYar/AutoBackups");
                    File[] files = directory == null ? null : directory.listFiles((dir, name) ->
                            name.startsWith("gomrokyar-auto-") && (name.endsWith(".json") || name.endsWith(".json.gz")));
                    if (files != null) {
                        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
                        for (File file : files) {
                            int count = 0;
                            try (InputStream in = new java.io.FileInputStream(file)) { count = countBackupRecords(in); }
                            JSONObject item = new JSONObject();
                            item.put("name", file.getName());
                            item.put("modified", file.lastModified());
                            item.put("size", file.length());
                            item.put("recordCount", count);
                            result.put(item);
                        }
                    }
                }
            } catch (Exception ignored) { }
            return result.toString();
        }

        @JavascriptInterface public boolean openAutomaticBackupFolder() {
            try {
                runOnUiThread(() -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            String documentId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                                    ? "primary:Download/GomrokYar/AutoBackups"
                                    : "primary:Android/data/" + getPackageName() + "/files/Documents/GomrokYar/AutoBackups";
                            Uri initial = DocumentsContract.buildTreeDocumentUri(
                                    "com.android.externalstorage.documents", documentId);
                            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
                        }
                        startActivityForResult(intent, 505);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "باز کردن پوشه پشتیبان‌ها ممکن نشد.", Toast.LENGTH_LONG).show();
                    }
                });
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface public synchronized boolean cancelFileSave() {
            try { if (chunkedSaveStream != null) chunkedSaveStream.close(); } catch (Exception ignored) { }
            chunkedSaveStream = null;
            if (pendingReportFile != null) pendingReportFile.delete();
            pendingReportFile = null;
            pendingReportName = null;
            pendingMimeType = "application/octet-stream";
            return true;
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
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null
                    && (pendingReportBytes != null || pendingReportFile != null)) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out == null) throw new java.io.IOException("Cannot open destination");
                    if (pendingReportFile != null && pendingReportFile.exists()) {
                        try (java.io.FileInputStream in = new java.io.FileInputStream(pendingReportFile)) {
                            byte[] buffer = new byte[32768];
                            int count;
                            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                        }
                    } else if (pendingReportBytes != null) {
                        out.write(pendingReportBytes);
                    }
                    Toast.makeText(this, "فایل گزارش با موفقیت ذخیره شد.", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "ذخیره فایل گزارش ناموفق بود.", Toast.LENGTH_LONG).show();
                }
            }
            pendingReportBytes = null;
            if (pendingReportFile != null) pendingReportFile.delete();
            pendingReportFile = null;
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
        if (chunkedSaveStream != null) { try { chunkedSaveStream.close(); } catch (Exception ignored) { } chunkedSaveStream = null; }
        if (pendingReportFile != null && pendingReportFile.exists()) pendingReportFile.delete();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
