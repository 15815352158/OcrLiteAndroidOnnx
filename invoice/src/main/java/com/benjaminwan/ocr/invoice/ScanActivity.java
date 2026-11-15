package com.benjaminwan.ocr.invoice;

import android.Manifest;
import android.app.Activity;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.benjaminwan.ocr.invoice.app.App;
import com.benjaminwan.ocr.invoice.databinding.ActivityScanBinding;
import com.benjaminwan.ocr.invoice.utils.InvoiceNumberUtils;
import com.benjaminwan.ocrlibrary.OcrEngine;
import com.benjaminwan.ocrlibrary.OcrResult;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 摄像头连续扫描发票号码：
 * 循环取预览帧 -> OCR -> 小框过滤(<=20字符) -> 校验(8位或20位)。
 * 校验不通过则不出结果、继续扫描；通过后停止扫描、振动提示并返回结果。
 */
public class ScanActivity extends AppCompatActivity {

    private static final int REQ_CAMERA_PERMISSION = 300;
    /** 两帧 OCR 之间的间隔，避免 CPU 持续满载 */
    private static final long FRAME_INTERVAL_MS = 300;

    private ActivityScanBinding binding;
    private OcrEngine ocrEngine;

    private volatile boolean detectStart = false;
    private final ExecutorService ocrExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final RequestOptions glideOptions = new RequestOptions()
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityScanBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ocrEngine = App.getEngine(this);
        // 摄像头拍摄画面基本是正的，关闭方向检测以提升每帧速度
        ocrEngine.setDoAngle(false);

        binding.startBtn.setOnClickListener(v -> ensurePermissionThenScan());
        binding.stopBtn.setOnClickListener(v -> stopDetect("扫描已停止"));

        startCamera();
        // 进入扫描页直接开始扫描，无需再点"开始扫描"
        ensurePermissionThenScan();
    }

    private void ensurePermissionThenScan() {
        if (detectStart) {
            return;
        }
        if (hasCameraPermission()) {
            detectLoop();
        } else {
            // Android 13+ 上 VIBRATE 也是运行时权限，一并申请
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.VIBRATE},
                    REQ_CAMERA_PERMISSION);
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION) {
            if (hasCameraPermission()) {
                detectLoop();
            } else {
                Toast.makeText(this, "没有摄像头权限，无法扫描", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                Preview preview = new Preview.Builder().build();
                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();
                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview);
                preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());
            } catch (Exception e) {
                Toast.makeText(this, "摄像头启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void detectLoop() {
        detectStart = true;
        binding.startBtn.setEnabled(false);
        binding.stopBtn.setEnabled(true);
        binding.resultTV.setText("扫描中…请将发票号码对准矩形取景框");
        binding.boxImageView.setVisibility(android.view.View.GONE);
        binding.scanOverlay.startAnimation();
        scheduleNextFrame();
    }

    private void stopDetect(String message) {
        detectStart = false;
        binding.startBtn.setEnabled(true);
        binding.stopBtn.setEnabled(false);
        binding.scanOverlay.stopAnimation();
        binding.resultTV.setText(message);
    }

    /** 主线程取当前预览帧并裁剪出取景框区域，交给 OCR 线程处理，形成循环 */
    private void scheduleNextFrame() {
        if (!detectStart) {
            return;
        }
        mainHandler.post(() -> {
            if (!detectStart) {
                return;
            }
            final Bitmap fullFrame = binding.viewFinder.getBitmap();
            if (fullFrame == null || fullFrame.getWidth() <= 0) {
                mainHandler.postDelayed(this::scheduleNextFrame, FRAME_INTERVAL_MS);
                return;
            }
            // 只识别矩形取景框内的区域，框外内容一律不参与识别
            final Bitmap frame = cropToScanRect(fullFrame);
            ocrExecutor.execute(() -> {
                OcrResult result = null;
                List<String> numbers = null;
                try {
                    Bitmap boxImg = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(),
                            Bitmap.Config.ARGB_8888);
                    int maxSideLen = Math.max(frame.getWidth(), frame.getHeight());
                    result = ocrEngine.detect(frame, boxImg, maxSideLen);
                    numbers = InvoiceNumberUtils.extract(result);
                } catch (Throwable ignored) {
                    // 单帧失败不中断扫描循环
                }
                final OcrResult ocrResult = result;
                final List<String> matched = (numbers != null && !numbers.isEmpty()) ? numbers : null;
                mainHandler.post(() -> {
                    if (!detectStart) {
                        return;
                    }
                    if (matched == null) {
                        // 校验不满足：不出结果，继续扫描
                        binding.statusTV.setText("未识别到有效发票号码（需8位或20位数字），继续扫描…");
                        mainHandler.postDelayed(this::scheduleNextFrame, FRAME_INTERVAL_MS);
                    } else {
                        onMatched(ocrResult, matched);
                    }
                });
            });
        });
    }

    /**
     * 按 ScanOverlayView 的取景矩形裁剪预览帧（视图坐标 -> 位图坐标）。
     * 取景框尚未完成首次绘制或裁剪区域过小时，退回整帧，保证扫描不中断。
     */
    private Bitmap cropToScanRect(Bitmap fullFrame) {
        RectF scanRect = new RectF();
        int vw = binding.scanOverlay.getWidth();
        int vh = binding.scanOverlay.getHeight();
        if (vw <= 0 || vh <= 0 || !binding.scanOverlay.getScanRect(scanRect)) {
            return fullFrame;
        }
        float sx = fullFrame.getWidth() / (float) vw;
        float sy = fullFrame.getHeight() / (float) vh;
        int left = Math.max(0, (int) (scanRect.left * sx));
        int top = Math.max(0, (int) (scanRect.top * sy));
        int right = Math.min(fullFrame.getWidth(), (int) (scanRect.right * sx));
        int bottom = Math.min(fullFrame.getHeight(), (int) (scanRect.bottom * sy));
        int cropW = right - left;
        int cropH = bottom - top;
        if (cropW < 32 || cropH < 32) {
            return fullFrame;
        }
        return Bitmap.createBitmap(fullFrame, left, top, cropW, cropH);
    }

    private void onMatched(OcrResult result, List<String> numbers) {
        detectStart = false;
        binding.startBtn.setEnabled(true);
        binding.stopBtn.setEnabled(false);
        binding.scanOverlay.stopAnimation();

        StringBuilder sb = new StringBuilder();
        sb.append("识别成功，发票号码：\n");
        for (String num : numbers) {
            sb.append("  ").append(num)
                    .append(num.length() == 20 ? "  (数电发票号码)" : "  (发票号码)").append("\n");
        }
        binding.resultTV.setText(sb.toString());
        binding.statusTV.setText(String.format("识别时间:%d ms", (int) result.getDetectTime()));
        binding.boxImageView.setVisibility(android.view.View.VISIBLE);
        Glide.with(this).load(result.getBoxImg()).apply(glideOptions).into(binding.boxImageView);
        vibration();

        Intent data = new Intent();
        // 8位号码时拼接全文中的12/10位发票代码，结果长度与二维码扫描一致（8/18/20位）
        data.putExtra("invoiceNumber",
                InvoiceNumberUtils.concatCode(result, numbers.get(0)));
        setResult(Activity.RESULT_OK, data);
        // 短暂停留展示结果后自动退出扫描页，返回主界面
        mainHandler.postDelayed(this::finish, 1200);
    }

    private void vibration() {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager = (VibratorManager) getSystemService(Service.VIBRATOR_MANAGER_SERVICE);
                vibrator = manager.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(Service.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(150);
            }
        } catch (SecurityException e) {
            // 无振动权限时静默降级，绝不影响结果展示
            Toast.makeText(this, "振动权限未授予，跳过振动提示", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        detectStart = false;
        binding.scanOverlay.stopAnimation();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        detectStart = false;
        ocrExecutor.shutdownNow();
    }
}
