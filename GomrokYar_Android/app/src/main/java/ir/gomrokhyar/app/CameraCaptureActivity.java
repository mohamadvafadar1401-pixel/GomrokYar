package ir.gomrokhyar.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraCaptureActivity extends ComponentActivity {
    public static final String EXTRA_OUTPUT_PATH = "gomrokyar.output_path";
    private static final int CAMERA_PERMISSION_REQUEST = 7301;
    private PreviewView previewView;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private String outputPath;
    private boolean captureInProgress;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        outputPath = getIntent().getStringExtra(EXTRA_OUTPUT_PATH);
        if (outputPath == null || outputPath.trim().isEmpty()) {
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }
        cameraExecutor = Executors.newSingleThreadExecutor();
        getWindow().setStatusBarColor(Color.rgb(5, 25, 53));
        getWindow().setNavigationBarColor(Color.rgb(5, 25, 53));
        buildCameraUi();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private void buildCameraUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(5, 25, 53));
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        TextView hint = new TextView(this);
        hint.setText("دوربین پشت · کیفیت کم‌حجم · اولتراواید در صورت پشتیبانی");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(18, 12, 18, 12);
        hint.setBackgroundColor(0x99051935);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(hint, hintParams);

        Button capture = new Button(this);
        capture.setText("ثبت عکس");
        capture.setTextSize(17);
        capture.setTextColor(Color.WHITE);
        capture.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(0, 174, 230)));
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(-1, 64, Gravity.BOTTOM);
        buttonParams.setMargins(28, 12, 28, 28);
        root.addView(capture, buttonParams);
        capture.setOnClickListener(v -> takePhoto());
        setContentView(root);
    }

    private String findUltraWidePhysicalCameraId(String logicalCameraId) {
        if (android.os.Build.VERSION.SDK_INT < 28) return null;
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics logical = manager.getCameraCharacteristics(logicalCameraId);
            Set<String> physicalIds = logical.getPhysicalCameraIds();
            String bestId = null;
            float shortestFocal = Float.MAX_VALUE;
            for (String id : physicalIds) {
                CameraCharacteristics physical = manager.getCameraCharacteristics(id);
                Integer facing = physical.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                float[] focalLengths = physical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (focalLengths == null || focalLengths.length == 0) continue;
                float shortest = focalLengths[0];
                for (float focal : focalLengths) shortest = Math.min(shortest, focal);
                if (shortest < shortestFocal) {
                    shortestFocal = shortest;
                    bestId = id;
                }
            }
            // Only select a physical camera when it is materially wider than the logical main lens.
            float[] mainFocals = logical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (bestId != null && mainFocals != null && mainFocals.length > 0) {
                float main = mainFocals[0];
                for (float focal : mainFocals) main = Math.min(main, focal);
                if (shortestFocal < main * 0.75f) return bestId;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void startCamera() {
        com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
                String ultraWideId = findUltraWidePhysicalCameraId(findBackLogicalCameraId());
                Preview.Builder previewBuilder = new Preview.Builder();
                ImageCapture.Builder imageBuilder = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetResolution(new Size(640, 480))
                        .setJpegQuality(45);
                if (ultraWideId != null) {
                    new Camera2Interop.Extender<>(previewBuilder).setPhysicalCameraId(ultraWideId);
                    new Camera2Interop.Extender<>(imageBuilder).setPhysicalCameraId(ultraWideId);
                }
                Preview preview = previewBuilder.build();
                imageCapture = imageBuilder.build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview, imageCapture);
            } catch (Exception e) {
                Toast.makeText(this, "راه‌اندازی دوربین ناموفق بود.", Toast.LENGTH_LONG).show();
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private String findBackLogicalCameraId() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
            }
        } catch (Exception ignored) { }
        return "";
    }

    private void takePhoto() {
        if (imageCapture == null || captureInProgress) return;
        captureInProgress = true;
        File output = new File(outputPath);
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(output).build();
        imageCapture.takePicture(options, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(ImageCapture.OutputFileResults result) {
                runOnUiThread(() -> {
                    setResult(Activity.RESULT_OK);
                    finish();
                });
            }
            @Override public void onError(ImageCaptureException error) {
                runOnUiThread(() -> {
                    captureInProgress = false;
                    Toast.makeText(CameraCaptureActivity.this, "ثبت عکس انجام نشد؛ دوباره تلاش کن.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
            else {
                Toast.makeText(this, "برای عکاسی، اجازه دسترسی به دوربین لازم است.", Toast.LENGTH_LONG).show();
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        }
    }

    @Override protected void onDestroy() {
        if (cameraExecutor != null) cameraExecutor.shutdown();
        super.onDestroy();
    }
}
