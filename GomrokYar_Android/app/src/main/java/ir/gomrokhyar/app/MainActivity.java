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
import android.util.Log;

import android.util.Base64;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import android.util.JsonReader;

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
    private static final int BACKUP_RESTORE_REQUEST = 505;
    private Uri backupRestoreUri;
    private Reader backupRestoreReader;
    private final char[] backupReadBuffer = new char[65536];
    private final ReportBridge reportBridge = new ReportBridge();
    private volatile boolean saveDocumentPending;
    private byte[] pendingReportBytes;
    private File pendingReportFile;
    private OutputStream chunkedSaveStream;
    private String pendingReportName;
    private String pendingMimeType = "application/octet-stream";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(27, 21, 34));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(17, 15, 24));
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                Log.i("GomrokYarWeb", "PAGE_STARTED " + url);
            }
            @Override public void onPageFinished(WebView view, String url) {
                Log.i("GomrokYarWeb", "PAGE_FINISHED " + url);
                view.evaluateJavascript("(function(){return JSON.stringify({ready:document.readyState,title:document.title,bodyText:(document.body&&document.body.innerText||'').slice(0,1200),bodyHtmlLength:document.body?document.body.innerHTML.length:0,gate:!!document.getElementById('authGate'),gateDisplay:document.getElementById('authGate')?getComputedStyle(document.getElementById('authGate')).display:'missing',scene:!!document.getElementById('authScene')});})()", value ->
                        Log.i("GomrokYarWeb", "DOM_STATE " + value));
            }
            @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) Log.e("GomrokYarWeb", "MAIN_FRAME_ERROR " + error.getErrorCode() + " " + error.getDescription());
            }
        });
        webView.addJavascriptInterface(reportBridge, "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                Log.e("GomrokYarWeb", "JS_CONSOLE " + message.message() + " -- "
                        + message.sourceId() + ":" + message.lineNumber());
                return true;
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                clearPendingCallback();
                fileCallback = callback;
                if (params.isCaptureEnabled()) {
                    launchRearCamera();
                    return true;
                }
                boolean backupRequest = isBackupFileRequest(params);
                Intent intent;
                if (backupRequest) {
                    // Older Samsung file pickers may hide JSON files when filtered by MIME type.
                    // Show all openable files and validate the selected file in the app.
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    try {
                        intent = params.createIntent();
                    } catch (Exception ignored) {
                        intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType(isImageFileRequest(params) ? "image/*" : "*/*");
                    }
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    if (backupRequest) {
                        try {
                            Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            fallback.addCategory(Intent.CATEGORY_OPENABLE);
                            fallback.setType("*/*");
                            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivityForResult(fallback, FILE_CHOOSER_REQUEST);
                            return true;
                        } catch (Exception ignored) { }
                    }
                    clearPendingCallback();
                    Toast.makeText(MainActivity.this, "امکان باز کردن فایل وجود ندارد.", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }

    private boolean isBackupFileRequest(WebChromeClient.FileChooserParams params) {
        if (params == null || params.getAcceptTypes() == null) return false;
        for (String type : params.getAcceptTypes()) {
            String value = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
            if (value.contains("json") || value.contains("gzip") || value.contains(".gz") || value.contains(".json")) {
                return true;
            }
        }
        return false;
    }

    private boolean isImageFileRequest(WebChromeClient.FileChooserParams params) {
        if (params == null || params.getAcceptTypes() == null) return false;
        for (String type : params.getAcceptTypes()) {
            String value = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
            if (value.startsWith("image/") || value.contains("image/*")) return true;
        }
        return false;
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
        @JavascriptInterface public String convertPersianDate(int year, int month, int day, boolean toGregorian) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return "";
            try {
                return PersianCalendarConversion.convert(year, month, day, toGregorian);
            } catch (IllegalArgumentException invalidDate) {
                return "";
            }
        }

        @JavascriptInterface public void pickBackupFile() {
            runOnUiThread(() -> {
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.setType("*/*");
                picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivityForResult(picker, BACKUP_RESTORE_REQUEST);
                } catch (Exception ignored) {
                    picker.setAction(Intent.ACTION_GET_CONTENT);
                    try {
                        startActivityForResult(picker, BACKUP_RESTORE_REQUEST);
                    } catch (Exception unavailable) {
                        webView.evaluateJavascript("nativeBackupSelected(false, true)", null);
                    }
                }
            });
        }

        @JavascriptInterface public synchronized boolean openBackupInput() {
            closeBackupInput();
            if (backupRestoreUri == null) return false;
            try {
                InputStream source = getContentResolver().openInputStream(backupRestoreUri);
                if (source == null) return false;
                backupRestoreReader = new InputStreamReader(openBackupStream(source),
                        StandardCharsets.UTF_8.newDecoder());
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        @JavascriptInterface public synchronized String readBackupInput() {
            if (backupRestoreReader == null) return null;
            try {
                int count = backupRestoreReader.read(backupReadBuffer);
                if (count == -1) return "";
                // Keep UTF-16 pairs together across separate bridge calls.
                if (Character.isHighSurrogate(backupReadBuffer[count - 1])) {
                    int next = backupRestoreReader.read();
                    if (next == -1) return null;
                    return new String(backupReadBuffer, 0, count) + (char) next;
                }
                return new String(backupReadBuffer, 0, count);
            } catch (Exception ignored) {
                return null;
            }
        }

        @JavascriptInterface public synchronized void closeBackupInput() {
            if (backupRestoreReader != null) {
                try { backupRestoreReader.close(); } catch (Exception ignored) { }
                backupRestoreReader = null;
            }
        }

        @JavascriptInterface public synchronized void releaseBackupInput() {
            closeBackupInput();
            backupRestoreUri = null;
        }

        @JavascriptInterface public void saveReport(String fileName, String base64) {
            saveFile(fileName, "text/html", base64);
        }
        @JavascriptInterface public synchronized void saveFile(String fileName, String mimeType, String base64) {
            if (saveDocumentPending || chunkedSaveStream != null) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "یک خروجی دیگر در حال ذخیره است.", Toast.LENGTH_LONG).show());
                return;
            }
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String safeName = (fileName == null || fileName.trim().isEmpty())
                        ? "gomrokyar-export.dat"
                        : fileName.replaceAll("[\\\\/:*?\\\"<>|]", "_");
                String safeMime = (mimeType == null || mimeType.trim().isEmpty())
                        ? "application/octet-stream" : mimeType;
                saveDocumentPending = true;
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
                        saveDocumentPending = false;
                        Toast.makeText(MainActivity.this, "امکان باز کردن محل ذخیره فایل وجود ندارد.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "آماده‌سازی فایل ناموفق بود.", Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface public synchronized boolean beginFileSave(String fileName, String mimeType) {
            if (saveDocumentPending || chunkedSaveStream != null) return false;
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
            if (saveDocumentPending || chunkedSaveStream != null) return false;
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
                chunkedSaveStream = new GZIPOutputStream(new FileOutputStream(pendingReportFile, false), 65536) {
                    { def.setLevel(Deflater.BEST_SPEED); }
                };
                return true;
            } catch (Exception e) {
                try { if (chunkedSaveStream != null) chunkedSaveStream.close(); } catch (Exception ignored) { }
                if (pendingReportFile != null) pendingReportFile.delete();
                pendingReportFile = null;
                chunkedSaveStream = null;
                return false;
            }
        }

        @JavascriptInterface public synchronized boolean appendBackupText(String text) {
            try {
                if (chunkedSaveStream == null || text == null) return false;
                chunkedSaveStream.write(text.getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (Exception e) {
                return false;
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
                saveDocumentPending = true;
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
                        saveDocumentPending = false;
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
                    File staging = File.createTempFile(".gomrokyar-", ".tmp", directory);
                    try {
                        try (InputStream in = new FileInputStream(pendingReportFile);
                             FileOutputStream out = new FileOutputStream(staging)) {
                            copyFile(in, out);
                            out.getFD().sync();
                        }
                        android.system.Os.rename(staging.getAbsolutePath(), new File(directory, saveName).getAbsolutePath());
                    } finally {
                        staging.delete();
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && deleteAutomaticBackupsByName(saveName, insertedUri)) {
                    // MediaStore may have added a numeric suffix while the older file still existed.
                    ContentValues name = new ContentValues();
                    name.put(MediaStore.MediaColumns.DISPLAY_NAME, saveName);
                    try { getContentResolver().update(insertedUri, name, null, null); } catch (Exception ignored) { }
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

        private boolean deleteAutomaticBackupsByName(String fileName, Uri keepUri) {
            if (fileName == null || fileName.trim().isEmpty() || fileName.contains("/") || fileName.contains("\\")) return false;
            boolean success = true;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    String relativePath = Environment.DIRECTORY_DOWNLOADS + "/GomrokYar/AutoBackups/";
                    String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME};
                    List<Uri> matches = new ArrayList<>();
                    try (Cursor cursor = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            projection, MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                                    + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                            new String[]{relativePath, backupBaseName(fileName) + "%"}, null)) {
                        if (cursor == null) return false;
                        int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                        int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
                        while (cursor.moveToNext()) {
                            Uri uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol));
                            if (!uri.equals(keepUri) && matchesBackupName(fileName, cursor.getString(nameCol))) matches.add(uri);
                        }
                    }
                    for (Uri uri : matches) {
                        if (getContentResolver().delete(uri, null, null) <= 0) success = false;
                    }
                } else {
                    File documents = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                    if (documents == null) return false;
                    File target = new File(new File(documents, "GomrokYar/AutoBackups"), fileName);
                    if (target.exists() && !target.delete()) success = false;
                }
            } catch (Exception e) {
                success = false;
            }
            return success;
        }

        @JavascriptInterface public synchronized boolean deleteAutomaticBackup(String fileName) {
            return deleteAutomaticBackupsByName(fileName, null);
        }

        private String backupBaseName(String fileName) {
            return fileName.endsWith(".json.gz") ? fileName.substring(0, fileName.length() - 8) : fileName;
        }

        private boolean matchesBackupName(String expected, String actual) {
            if (expected.equals(actual)) return true;
            return expected.endsWith(".json.gz") && actual != null && actual.matches(
                    java.util.regex.Pattern.quote(backupBaseName(expected))
                            + "(?: \\([0-9]+\\)\\.json|\\.json \\([0-9]+\\))\\.gz");
        }

        private int countBackupRecords(InputStream input) {
            try (JsonReader json = new JsonReader(new InputStreamReader(openBackupStream(input), StandardCharsets.UTF_8))) {
                json.beginObject();
                while (json.hasNext()) {
                    if ("records".equals(json.nextName())) {
                        int count = 0;
                        json.beginArray();
                        while (json.hasNext()) {
                            json.skipValue();
                            count++;
                        }
                        return count;
                    }
                    json.skipValue();
                }
            } catch (Exception ignored) { }
            return 0;
        }

        @JavascriptInterface public void requestAutomaticBackups(int requestId) {
            new Thread(() -> {
                String result = getAutomaticBackups();
                runOnUiThread(() -> {
                    if (!isDestroyed()) webView.evaluateJavascript(
                            "showAutomaticBackupList(" + requestId + "," + JSONObject.quote(result) + ")", null);
                });
            }, "gomrokyar-backup-list").start();
        }

        private String getAutomaticBackups() {
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

    private static InputStream openBackupStream(InputStream source) throws java.io.IOException {
        PushbackInputStream probe = new PushbackInputStream(new java.io.BufferedInputStream(source, 65536), 2);
        try {
            int first = probe.read(), second = probe.read();
            if (second != -1) probe.unread(second);
            if (first != -1) probe.unread(first);
            return first == 0x1f && second == 0x8b ? new GZIPInputStream(probe, 65536) : probe;
        } catch (java.io.IOException error) {
            probe.close();
            throw error;
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.N)
    private static class PersianCalendarConversion {
        static String convert(int year, int month, int day, boolean toGregorian) {
            android.icu.util.TimeZone utc = android.icu.util.TimeZone.getTimeZone("UTC");
            android.icu.util.Calendar persian = android.icu.util.Calendar.getInstance(
                    utc, new android.icu.util.ULocale("fa_IR@calendar=persian"));
            android.icu.util.Calendar gregorian = new android.icu.util.GregorianCalendar(utc);
            android.icu.util.Calendar source = toGregorian ? persian : gregorian;
            android.icu.util.Calendar target = toGregorian ? gregorian : persian;
            source.clear();
            source.setLenient(false);
            source.set(year, month - 1, day);
            target.setTimeInMillis(source.getTimeInMillis());
            return target.get(android.icu.util.Calendar.YEAR) + ","
                    + (target.get(android.icu.util.Calendar.MONTH) + 1) + ","
                    + target.get(android.icu.util.Calendar.DATE);
        }
    }

    private static void copyFile(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BACKUP_RESTORE_REQUEST) {
            boolean selected;
            synchronized (reportBridge) {
                backupRestoreUri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
                selected = backupRestoreUri != null;
            }
            webView.evaluateJavascript("nativeBackupSelected(" + selected + ", false)", null);
            return;
        }
        if (requestCode == REPORT_SAVE_REQUEST || requestCode == FILE_SAVE_REQUEST) {
            final File file;
            final byte[] bytes;
            synchronized (reportBridge) {
                file = pendingReportFile;
                bytes = pendingReportBytes;
                pendingReportFile = null;
                pendingReportBytes = null;
                pendingReportName = null;
                pendingMimeType = "application/octet-stream";
                saveDocumentPending = false;
            }
            Uri destination = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (destination == null || (file == null && bytes == null)) {
                if (file != null) file.delete();
                return;
            }
            new Thread(() -> {
                String message;
                try (OutputStream out = getContentResolver().openOutputStream(destination)) {
                    if (out == null) throw new java.io.IOException("Cannot open destination");
                    if (file != null) {
                        try (InputStream in = new FileInputStream(file)) { copyFile(in, out); }
                    } else out.write(bytes);
                    message = "فایل با موفقیت ذخیره شد.";
                } catch (Exception e) {
                    message = "ذخیره فایل ناموفق بود.";
                } finally {
                    if (file != null) file.delete();
                }
                final String status = message;
                runOnUiThread(() -> Toast.makeText(this, status, Toast.LENGTH_LONG).show());
            }, "gomrokyar-document-save").start();
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
        reportBridge.releaseBackupInput();
        if (cameraOutputFile != null && cameraOutputFile.exists()) cameraOutputFile.delete();
        if (chunkedSaveStream != null) { try { chunkedSaveStream.close(); } catch (Exception ignored) { } chunkedSaveStream = null; }
        if (pendingReportFile != null && pendingReportFile.exists()) pendingReportFile.delete();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
