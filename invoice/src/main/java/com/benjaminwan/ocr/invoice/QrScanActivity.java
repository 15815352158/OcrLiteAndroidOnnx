package com.benjaminwan.ocr.invoice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.benjaminwan.ocr.invoice.utils.InvoiceNumberUtils;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Hashtable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 二维码扫描获取发票号码：
 * CameraX ImageAnalysis 取 YUV 亮度帧 -> ZXing 解码 QR -> 按发票二维码格式解析号码。
 * 解析成功振动提示、展示结果并自动返回；失败则持续扫描。
 */
public class QrScanActivity extends AppCompatActivity {

    private static final int REQ_CAMERA_PERMISSION = 400;

    private com.benjaminwan.ocr.invoice.databinding.ActivityQrScanBinding binding;

    private volatile boolean scanning = false;
    private ImageAnalysis imageAnalysis;
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Hashtable<DecodeHintType, Object> decodeHints = new Hashtable<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = com.benjaminwan.ocr.invoice.databinding.ActivityQrScanBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.scanOverlay.setSquareMode(true); // 二维码用正方形取景框

        decodeHints.put(DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE));
        decodeHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        binding.startBtn.setOnClickListener(v -> ensurePermissionThenScan());
        binding.stopBtn.setOnClickListener(v -> stopScan("扫描已停止"));

        startCamera();
        // 进入页面直接开始扫描
        ensurePermissionThenScan();
    }

    private void ensurePermissionThenScan() {
        if (scanning) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startScan();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startScan();
        } else {
            Toast.makeText(this, "没有摄像头权限，无法扫描", Toast.LENGTH_LONG).show();
        }
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                Preview preview = new Preview.Builder().build();
                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(decodeExecutor, this::analyzeFrame);
                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();
                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview, imageAnalysis);
                preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());
            } catch (Exception e) {
                Toast.makeText(this, "摄像头启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startScan() {
        scanning = true;
        binding.startBtn.setEnabled(false);
        binding.stopBtn.setEnabled(true);
        binding.statusTV.setText("正在扫描二维码…请将发票左上角的二维码对准画面");
        binding.resultTV.setText("");
        binding.scanOverlay.startAnimation();
    }

    private void stopScan(String message) {
        scanning = false;
        binding.startBtn.setEnabled(true);
        binding.stopBtn.setEnabled(false);
        binding.scanOverlay.stopAnimation();
        binding.statusTV.setText(message);
    }

    /** 在 decodeExecutor 线程回调：解码当前帧的二维码 */
    private void analyzeFrame(@NonNull ImageProxy image) {
        try {
            if (!scanning) {
                return;
            }
            String qrText = decodeQr(image);
            if (qrText == null) {
                return;
            }
            String number = InvoiceNumberUtils.parseQrContent(qrText);
            if (number == null) {
                mainHandler.post(() -> {
                    if (scanning) {
                        binding.statusTV.setText("二维码中没有解析出发票号码，继续扫描…");
                    }
                });
                return;
            }
            mainHandler.post(() -> onMatched(qrText, number));
        } finally {
            image.close();
        }
    }

    private String decodeQr(ImageProxy image) {
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ByteBuffer buffer = yPlane.getBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        int rowStride = yPlane.getRowStride();
        PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                data, rowStride, image.getHeight(),
                0, 0, image.getWidth(), image.getHeight(), false);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        MultiFormatReader reader = new MultiFormatReader();
        try {
            Result result = reader.decode(bitmap, decodeHints);
            return result.getText();
        } catch (NotFoundException e) {
            return null;
        } finally {
            reader.reset();
        }
    }

    private void onMatched(String qrText, String number) {
        scanning = false;
        binding.startBtn.setEnabled(true);
        binding.stopBtn.setEnabled(false);
        binding.scanOverlay.stopAnimation();

        binding.statusTV.setText(number.length() == 20
                ? "解析成功（数电发票号码）" : "解析成功（发票号码）");
        binding.resultTV.setText("发票号码：" + number + "\n二维码内容：" + qrText);

        Intent data = new Intent();
        data.putExtra("invoiceNumber", number);
        setResult(Activity.RESULT_OK, data);
        // 短暂停留展示结果后自动退出，返回主界面
        mainHandler.postDelayed(this::finish, 1500);
    }

    @Override
    protected void onStop() {
        super.onStop();
        scanning = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanning = false;
        decodeExecutor.shutdownNow();
    }
}
