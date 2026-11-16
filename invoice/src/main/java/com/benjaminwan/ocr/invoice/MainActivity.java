package com.benjaminwan.ocr.invoice;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.benjaminwan.ocr.invoice.app.App;
import com.benjaminwan.ocr.invoice.databinding.ActivityMainBinding;
import com.benjaminwan.ocr.invoice.utils.BitmapUtils;
import com.benjaminwan.ocr.invoice.utils.InvoiceNumberUtils;
import com.benjaminwan.ocrlibrary.OcrEngine;
import com.benjaminwan.ocrlibrary.OcrResult;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.io.FileNotFoundException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 发票号码识别 Demo：
 * 1. 相册选图识别：OCR 后按规则提取合法发票号码（8 位或 20 位数字，仅扫描 <=20 字符的小矩形框）；
 * 2. 摄像头扫描：跳转 ScanActivity 连续扫描，校验通过才出结果。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_SELECT_IMAGE = 100;
    private static final int REQUEST_SCAN = 101;
    private static final int REQUEST_QR_SCAN = 102;

    /** 发票号码分组着色：每 4 个字符一组，按 红、蓝、绿、黄、黑 循环 */
    private static final int[] GROUP_COLORS = {
            0xFFE53935, // 红
            0xFF1E88E5, // 蓝
            0xFF43A047, // 绿
            0xFFF9A825, // 黄
            0xFF000000  // 黑
    };

    private ActivityMainBinding binding;
    private OcrEngine ocrEngine;
    private Bitmap selectedImg;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final RequestOptions glideOptions = new RequestOptions()
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ocrEngine = App.getEngine(this);
        ocrEngine.setDoAngle(true); // 相册照片方向不定，启用文字方向检测

        binding.selectBtn.setOnClickListener(v -> openSelector());
        binding.scanBtn.setOnClickListener(v ->
                startActivityForResult(new Intent(this, ScanActivity.class), REQUEST_SCAN));
        binding.qrScanBtn.setOnClickListener(v ->
                startActivityForResult(new Intent(this, QrScanActivity.class), REQUEST_QR_SCAN));
    }

    private void openSelector() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择发票图片"), REQUEST_SELECT_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == REQUEST_SCAN || requestCode == REQUEST_QR_SCAN)
                && resultCode == Activity.RESULT_OK && data != null) {
            String number = data.getStringExtra("invoiceNumber");
            String rawText = data.getStringExtra("rawText");
            binding.invoiceNumberET.setText(styleInvoiceNumber(number));
            // 扫描结果栏显示原样内容（OCR全文/二维码原文），便于排查提取是否正确
            binding.resultTV.setText(rawText == null ? "（无原始内容）" : rawText.trim());
            return;
        }
        if (requestCode == REQUEST_SELECT_IMAGE && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            Uri imgUri = data.getData();
            Glide.with(this).load(imgUri).apply(glideOptions).into(binding.imageView);
            try {
                selectedImg = BitmapUtils.decodeUri(this, imgUri);
            } catch (FileNotFoundException e) {
                Toast.makeText(this, "图片读取失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }
            binding.timeTV.setText("");
            binding.resultTV.setText("图片已就绪，开始自动识别...");
            // 选择图片后自动识别，无需手动点击按钮
            detectInvoice();
        }
    }

    private void detectInvoice() {
        if (selectedImg == null) {
            Toast.makeText(this, "请先选择发票图片", Toast.LENGTH_SHORT).show();
            return;
        }
        binding.selectBtn.setEnabled(false);
        binding.scanBtn.setEnabled(false);
        binding.qrScanBtn.setEnabled(false);
        binding.resultTV.setText("识别中，请稍候...");

        final Bitmap img = selectedImg;
        executor.execute(() -> {
            OcrResult result = null;
            String error = null;
            try {
                Bitmap boxImg = Bitmap.createBitmap(img.getWidth(), img.getHeight(), Bitmap.Config.ARGB_8888);
                int maxSideLen = Math.max(img.getWidth(), img.getHeight());
                result = ocrEngine.detect(img, boxImg, maxSideLen);
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final OcrResult ocrResult = result;
            final String err = error;
            mainHandler.post(() -> {
                binding.selectBtn.setEnabled(true);
                binding.scanBtn.setEnabled(true);
                binding.qrScanBtn.setEnabled(true);
                if (ocrResult == null) {
                    binding.resultTV.setText("识别失败: " + err);
                    return;
                }
                binding.timeTV.setText(String.format("识别时间:%d ms", (int) ocrResult.getDetectTime()));
                Glide.with(MainActivity.this)
                        .load(ocrResult.getBoxImg())
                        .apply(glideOptions)
                        .into(binding.imageView);
                binding.resultTV.setText(buildReport(ocrResult));
            });
        });
    }

    /**
     * 发票号码（提取结果，8位时自动拼接12/10位代码）填入文本框；
     * 扫描结果栏只显示 OCR 原样全文，便于排查识别问题。
     */
    private String buildReport(OcrResult ocrResult) {
        List<String> numbers = InvoiceNumberUtils.extract(ocrResult);

        // 第一个合法号码（20位优先）填入发票号码文本框，分组着色便于人工核对；
        // 8位号码时自动拼接全文中的12/10位发票代码，长度与二维码扫描一致
        binding.invoiceNumberET.setText(
                styleInvoiceNumber(InvoiceNumberUtils.concatCode(
                        ocrResult, numbers.isEmpty() ? "" : numbers.get(0))));

        return ocrResult.getStrRes().trim();
    }

    /**
     * 发票号码分组着色：每 4 个字符一组循环使用 红/蓝/绿/黄/黑，
     * 例如 20 位号码：前4红、5-8蓝、9-12绿、13-16黄、17-20黑。
     */
    private CharSequence styleInvoiceNumber(String num) {
        if (num == null || num.isEmpty()) {
            return "";
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(num);
        for (int i = 0; i < num.length(); i += 4) {
            int color = GROUP_COLORS[(i / 4) % GROUP_COLORS.length];
            int end = Math.min(i + 4, num.length());
            sb.setSpan(new ForegroundColorSpan(color), i, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
