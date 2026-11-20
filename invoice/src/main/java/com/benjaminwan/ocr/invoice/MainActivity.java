package com.benjaminwan.ocr.invoice;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.benjaminwan.ocr.invoice.app.App;
import com.benjaminwan.ocr.invoice.databinding.ActivityMainBinding;
import com.benjaminwan.ocr.invoice.utils.AppConfig;
import com.benjaminwan.ocr.invoice.utils.BitmapUtils;
import com.benjaminwan.ocr.invoice.utils.InvoiceApi;
import com.benjaminwan.ocr.invoice.utils.InvoiceNumberUtils;
import com.benjaminwan.ocr.invoice.utils.InternalPingMonitor;
import com.benjaminwan.ocr.invoice.utils.NoCopyActionModeCallback;
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
public class MainActivity extends AppCompatActivity implements android.text.TextWatcher {

    private static final String TAG = "MainActivity";

    private static final int REQUEST_SELECT_IMAGE = 100;
    private static final int REQUEST_SCAN = 101;
    private static final int REQUEST_QR_SCAN = 102;

    /** 设置菜单项 Id */
    private static final int MENU_INTERNAL_IP = 201;
    private static final int MENU_EXTERNAL_IP = 202;
    private static final int MENU_PORT = 203;
    private static final int MENU_TOKEN = 204;
    private static final int MENU_RESTORE_DEFAULT = 205;
    private static final int MENU_AUTO_SUBMIT = 206;
    private static final int MENU_CONTINUOUS_SCAN = 208;
    private static final int MENU_ABOUT = 207;

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
    private AppConfig conf;

    /** 当前发票号码纯文本（提交用，不含着色） */
    private String currentNumber = "";
    /** 最近一次提交成功/重复的号码，未改成别的号码前禁止再次提交 */
    private String lastSubmittedNumber = "";
    /** 识别进行中临时禁用提交按钮 */
    private boolean recognizing = false;
    /** 最近一次进入的扫描方式（REQUEST_SCAN/REQUEST_QR_SCAN），连续扫描提交成功后自动重进 */
    private int lastScanRequest = 0;
    /** 提交中旋转进度对话框，提交完毕自动关闭 */
    private AlertDialog submitProgressDialog;
    /** 程序回填发票号码时屏蔽 TextWatcher，避免与手动编辑互相触发 */
    private boolean updatingText = false;

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
        conf = AppConfig.get(this);

        binding.selectBtn.setOnClickListener(v -> {
            showDefaultHint();
            openSelector();
        });
        binding.scanBtn.setOnClickListener(v -> {
            lastScanRequest = REQUEST_SCAN;
            showDefaultHint();
            startActivityForResult(new Intent(this, ScanActivity.class), REQUEST_SCAN);
        });
        binding.qrScanBtn.setOnClickListener(v -> {
            lastScanRequest = REQUEST_QR_SCAN;
            showQrHint();
            startActivityForResult(new Intent(this, QrScanActivity.class), REQUEST_QR_SCAN);
        });
        binding.submitBtn.setOnClickListener(v -> submitInvoice());
        binding.submitBtn.setEnabled(false);
        // 手动编辑发票号码：数字键盘输入，改动后刷新提交使能（不触发自动提交）
        binding.invoiceNumberET.addTextChangedListener(this);

        // 溢出菜单三个点图标改为齿轮：主题样式在部分机型不生效，直接在内部 Toolbar 上设置
        binding.getRoot().post(() -> {
            androidx.appcompat.widget.Toolbar bar =
                    findViewById(androidx.appcompat.R.id.action_bar);
            if (bar != null) {
                bar.setOverflowIcon(androidx.core.content.ContextCompat
                        .getDrawable(this, R.drawable.ic_settings));
            } else {
                Log.w(TAG, "未找到 action_bar Toolbar，溢出图标未替换");
            }
        });

        // 原始内容折叠区：默认收起，点击标题展开/收起，收起时下方组件自动上移
        binding.resultHeaderTV.setOnClickListener(v -> {
            boolean expand = binding.resultScrollView.getVisibility()
                    != android.view.View.VISIBLE;
            binding.resultScrollView.setVisibility(
                    expand ? android.view.View.VISIBLE : android.view.View.GONE);
            binding.resultHeaderTV.setText(
                    expand ? "扫描原始内容  ▲" : "扫描原始内容  ▼");
        });
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
            updateInvoiceNumber(number);
            // 扫描结果栏显示原样内容（OCR全文/二维码原文），便于排查提取是否正确
            binding.resultTV.setText(rawText == null ? "（无原始内容）" : rawText.trim());
            // 摄像头扫描的识别图（带检测框）显示到主界面识别图栏目
            String boxImgPath = data.getStringExtra("boxImgPath");
            if (boxImgPath != null) {
                hideHints();
                Glide.with(this).load(new java.io.File(boxImgPath))
                        .apply(glideOptions).into(binding.imageView);
            }
            return;
        }
        if (requestCode == REQUEST_SELECT_IMAGE && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            Uri imgUri = data.getData();
            hideHints();
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
            // 优先按二维码逻辑：图中能解出二维码且解析出发票号码则直接返回
            String qrText = decodeQrFromBitmap(img);
            if (qrText != null) {
                final String qrNumber = InvoiceNumberUtils.parseQrContent(qrText);
                if (qrNumber != null && !qrNumber.isEmpty()) {
                    mainHandler.post(() -> {
                        setButtonsEnabled(true);
                        binding.timeTV.setText("二维码识别（相册图片）");
                        updateInvoiceNumber(qrNumber);
                        // 扫描结果栏显示二维码原始内容，便于排查
                        binding.resultTV.setText(qrText.trim());
                    });
                    return;
                }
                // 解出二维码但提取不到号码，回退到 OCR 逻辑
            }
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
                setButtonsEnabled(true);
                if (ocrResult == null) {
                    binding.resultTV.setText("识别失败: " + err);
                    return;
                }
                binding.timeTV.setText(String.format("识别时间:%d ms", (int) ocrResult.getDetectTime()));
                hideHints();
                Glide.with(MainActivity.this)
                        .load(ocrResult.getBoxImg())
                        .apply(glideOptions)
                        .into(binding.imageView);
                binding.resultTV.setText(buildReport(ocrResult));
            });
        });
    }

    private void setButtonsEnabled(boolean enabled) {
        binding.selectBtn.setEnabled(enabled);
        binding.scanBtn.setEnabled(enabled);
        binding.qrScanBtn.setEnabled(enabled);
        recognizing = !enabled;
        refreshSubmitEnabled();
    }

    /** 用 ZXing 解码静态图片中的二维码，解不到返回 null */
    private String decodeQrFromBitmap(Bitmap bmp) {
        try {
            int[] pixels = new int[bmp.getWidth() * bmp.getHeight()];
            bmp.getPixels(pixels, 0, bmp.getWidth(), 0, 0, bmp.getWidth(), bmp.getHeight());
            com.google.zxing.RGBLuminanceSource source =
                    new com.google.zxing.RGBLuminanceSource(bmp.getWidth(), bmp.getHeight(), pixels);
            com.google.zxing.BinaryBitmap bitmap =
                    new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(source));
            java.util.Hashtable<com.google.zxing.DecodeHintType, Object> hints = new java.util.Hashtable<>();
            hints.put(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS,
                    java.util.Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE));
            hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE);
            return new com.google.zxing.MultiFormatReader().decode(bitmap, hints).getText();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 发票号码（提取结果，8位时自动拼接12/10位代码）填入文本框；
     * 扫描结果栏只显示 OCR 原样全文，便于排查识别问题。
     */
    private String buildReport(OcrResult ocrResult) {
        List<String> numbers = InvoiceNumberUtils.extract(ocrResult);

        // 第一个合法号码（20位优先）填入发票号码文本框，分组着色便于人工核对；
        // 8位号码时自动拼接全文中的12/10位发票代码，长度与二维码扫描一致
        updateInvoiceNumber(InvoiceNumberUtils.concatCode(
                ocrResult, numbers.isEmpty() ? "" : numbers.get(0)));

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

    /**
     * 三种识别方式得到号码的统一入口：着色显示、刷新提交按钮使能、按需自动提交。
     */
    private void updateInvoiceNumber(String number) {
        currentNumber = number == null ? "" : number;
        updatingText = true;
        binding.invoiceNumberET.setText(styleInvoiceNumber(currentNumber));
        updatingText = false;
        refreshSubmitEnabled();
        if (conf.isAutoSubmit() && !currentNumber.isEmpty()
                && !currentNumber.equals(lastSubmittedNumber)) {
            Log.i(TAG, "自动提交已开启，识别到号码，自动提交: " + currentNumber);
            submitInvoice();
        }
    }

    /** 提交按钮使能规则：号码非空且与最近一次已提交号码不同（识别进行中临时禁用） */
    private void refreshSubmitEnabled() {
        binding.submitBtn.setEnabled(!recognizing && !currentNumber.isEmpty()
                && !currentNumber.equals(lastSubmittedNumber));
    }

    /** 提交发票号码到后端做防重检测（点击按钮或自动提交触发） */
    private void submitInvoice() {
        final String number = currentNumber;
        if (number.isEmpty() || number.equals(lastSubmittedNumber)) {
            return;
        }
        binding.submitBtn.setEnabled(false);
        Log.i(TAG, "开始提交发票号码: " + number + "，当前优先网络: "
                + (InvoiceApi.isPreferInternal() ? "内网" : "外网"));
        showSubmitProgress();
        executor.execute(() -> {
            final InvoiceApi.Result result = InvoiceApi.submit(conf, number);
            Log.i(TAG, "提交结果: kind=" + result.kind + " message=" + result.message);
            mainHandler.post(() -> {
                dismissSubmitProgress();
                if (result.kind == InvoiceApi.KIND_SUCCESS) {
                    // 后端无报错：Toast 提示稍后自动消失
                    lastSubmittedNumber = number;
                    Toast.makeText(this, "发票OK", Toast.LENGTH_SHORT).show();
                    // 连续扫描：提交成功后自动重新进入前一次的扫描页继续操作
                    if (conf.isContinuousScan()
                            && (lastScanRequest == REQUEST_SCAN || lastScanRequest == REQUEST_QR_SCAN)) {
                        Log.i(TAG, "连续扫描已开启，重新进入扫描页: " + lastScanRequest);
                        openScanActivity(lastScanRequest);
                    }
                } else if (result.kind == InvoiceApi.KIND_DUPLICATE) {
                    // 409 重复：对话框展示冲突信息，视为已提交，改号码前保持禁用
                    lastSubmittedNumber = number;
                    showErrorDialog(result.message);
                } else {
                    // 其他失败：对话框展示错误信息，手动确认后恢复使能可重试
                    showErrorDialog(result.message);
                }
                refreshSubmitEnabled();
            });
        });
    }

    /** 弹出提交中进度框（不可取消，提交结束自动关闭） */
    private void showSubmitProgress() {
        if (submitProgressDialog != null && submitProgressDialog.isShowing()) {
            return;
        }
        android.view.View view = getLayoutInflater()
                .inflate(R.layout.dialog_progress, null);
        submitProgressDialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(false)
                .create();
        submitProgressDialog.show();
    }

    private void dismissSubmitProgress() {
        if (submitProgressDialog != null) {
            if (submitProgressDialog.isShowing()) {
                submitProgressDialog.dismiss();
            }
            submitProgressDialog = null;
        }
    }

    /** 识别区恢复默认占位文字"图片识别区" */
    private void showDefaultHint() {
        binding.imageView.setImageDrawable(null);
        binding.qrHintIV.setVisibility(android.view.View.GONE);
        binding.imageHintTV.setVisibility(android.view.View.VISIBLE);
    }

    /** 识别区显示二维码图示（点击二维码扫描时） */
    private void showQrHint() {
        binding.imageView.setImageDrawable(null);
        binding.imageHintTV.setVisibility(android.view.View.GONE);
        binding.qrHintIV.setVisibility(android.view.View.VISIBLE);
    }

    /** 隐藏占位提示，让位给识别图 */
    private void hideHints() {
        binding.imageHintTV.setVisibility(android.view.View.GONE);
        binding.qrHintIV.setVisibility(android.view.View.GONE);
    }

    /** 按请求码进入对应扫描页（连续扫描复用），携带已提交号码防止重复命中同一张发票立即弹回 */
    private void openScanActivity(int requestCode) {
        Class<?> cls = requestCode == REQUEST_QR_SCAN ? QrScanActivity.class : ScanActivity.class;
        startActivityForResult(new Intent(this, cls)
                .putExtra("ignoreNumber", lastSubmittedNumber), requestCode);
    }

    // ---------------- 发票号码手动编辑（TextWatcher） ----------------

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(android.text.Editable s) {
        if (updatingText) {
            return;
        }
        String raw = s.toString().trim();
        if (raw.equals(currentNumber)) {
            return;
        }
        // 手动编辑：只刷新号码与提交使能，不触发自动提交（自动提交仅识别入口触发）
        currentNumber = raw;
        refreshSubmitEnabled();
        // 重新套用分组着色，光标置于末尾
        updatingText = true;
        binding.invoiceNumberET.setText(styleInvoiceNumber(currentNumber));
        binding.invoiceNumberET.setSelection(binding.invoiceNumberET.getText().length());
        updatingText = false;
    }

    /** 失败对话框：显示服务器错误信息，必须手动点击"确定"关闭；
     *  连续扫描开启时，确认后自动重新进入前一次的扫描页继续扫描 */
    private void showErrorDialog(String message) {
        new AlertDialog.Builder(this)
                .setTitle("提交失败")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("确定", (d, w) -> {
                    if (conf.isContinuousScan()
                            && (lastScanRequest == REQUEST_SCAN || lastScanRequest == REQUEST_QR_SCAN)) {
                        Log.i(TAG, "错误弹框已确认，连续扫描重新进入扫描页: " + lastScanRequest);
                        openScanActivity(lastScanRequest);
                    }
                })
                .show();
    }

    // ---------------- 设置菜单 ----------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // 设置项直接作为一级菜单显示在溢出弹层中（齿轮按钮）
        menu.add(0, MENU_INTERNAL_IP, 0, "配置内网地址");
        menu.add(0, MENU_EXTERNAL_IP, 0, "配置外网地址");
        menu.add(0, MENU_PORT, 0, "配置端口");
        menu.add(0, MENU_TOKEN, 0, "配置Token");
        menu.add(0, MENU_RESTORE_DEFAULT, 0, "恢复默认值");
        MenuItem auto = menu.add(0, MENU_AUTO_SUBMIT, 0, "是否自动提交");
        auto.setCheckable(true);
        auto.setChecked(conf.isAutoSubmit());
        MenuItem continuous = menu.add(0, MENU_CONTINUOUS_SCAN, 0, "是否连续扫描");
        continuous.setCheckable(true);
        continuous.setChecked(conf.isContinuousScan());
        menu.add(0, MENU_ABOUT, 0, "关于");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == MENU_INTERNAL_IP) {
            showEditDialog("配置内网地址", conf.getInternalIp(),
                    InputType.TYPE_CLASS_PHONE, true, value -> {
                        conf.setInternalIp(value);
                        Toast.makeText(this, "内网地址已保存: " + value, Toast.LENGTH_SHORT).show();
                    });
            return true;
        }
        if (id == MENU_EXTERNAL_IP) {
            showEditDialog("配置外网地址", conf.getExternalIp(),
                    InputType.TYPE_CLASS_PHONE, true, value -> {
                        conf.setExternalIp(value);
                        Toast.makeText(this, "外网地址已保存: " + value, Toast.LENGTH_SHORT).show();
                    });
            return true;
        }
        if (id == MENU_PORT) {
            showEditDialog("配置端口", String.valueOf(conf.getPort()),
                    InputType.TYPE_CLASS_NUMBER, true, value -> {
                        conf.setPort(Integer.parseInt(value));
                        Toast.makeText(this, "端口已保存: " + value, Toast.LENGTH_SHORT).show();
                    });
            return true;
        }
        if (id == MENU_TOKEN) {
            // 不回显当前 Token，输入框为密码样式
            showEditDialog("配置Token", null,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    false, value -> {
                        conf.setToken(value);
                        Toast.makeText(this, "Token已保存", Toast.LENGTH_SHORT).show();
                    });
            return true;
        }
        if (id == MENU_RESTORE_DEFAULT) {
            new AlertDialog.Builder(this)
                    .setTitle("恢复默认值")
                    .setMessage("将内网地址、外网地址、端口、Token 恢复为默认值？")
                    .setPositiveButton("恢复", (d, w) -> {
                        conf.restoreDefaults();
                        Toast.makeText(this, "已恢复默认值", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        }
        if (id == MENU_AUTO_SUBMIT) {
            boolean enable = !item.isChecked();
            item.setChecked(enable);
            conf.setAutoSubmit(enable);
            Toast.makeText(this, enable ? "自动提交已开启" : "自动提交已关闭",
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        if (id == MENU_CONTINUOUS_SCAN) {
            boolean enable = !item.isChecked();
            item.setChecked(enable);
            conf.setContinuousScan(enable);
            Toast.makeText(this, enable ? "连续扫描已开启" : "连续扫描已关闭",
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        if (id == MENU_ABOUT) {
            new AlertDialog.Builder(this)
                    .setTitle("关于")
                    .setMessage("软件名称: 发票防重检测app\n版本: V1.0\n开发者: HiSon\nEmail: zhoums@gsdtech.com")
                    .setPositiveButton("确定", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 设置项输入对话框回调 */
    private interface OnSaveValue {
        void onSave(String value);
    }

    /**
     * 通用输入对话框。
     *
     * @param current  预填的当前值（password 传 null 不回显）
     * @param prefill  是否预填当前值
     * @param onSave   校验通过后的保存回调（子线程无关，主线程调用）
     */
    private void showEditDialog(String title, String current, int inputType,
                                boolean prefill, OnSaveValue onSave) {
        EditText input = new EditText(this);
        input.setInputType(inputType);
        if (prefill && current != null) {
            input.setText(current);
            input.setSelection(current.length());
        } else {
            input.setHint("输入新值（当前值不显示）");
        }
        // Token 密码框：允许粘贴，但屏蔽复制/剪切/分享，且密码样式始终掩码不回显明文
        if ((inputType & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) {
            input.setCustomSelectionActionModeCallback(new NoCopyActionModeCallback());
        }
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout wrapper = new android.widget.FrameLayout(this);
        wrapper.setPadding(pad, pad / 2, pad, 0);
        wrapper.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrapper)
                .setPositiveButton("保存", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) {
                        Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (inputType == InputType.TYPE_CLASS_NUMBER) {
                        try {
                            int p = Integer.parseInt(value);
                            if (p <= 0 || p > 65535) {
                                Toast.makeText(this, "端口范围 1-65535", Toast.LENGTH_SHORT).show();
                                return;
                            }
                        } catch (NumberFormatException e) {
                            Toast.makeText(this, "端口必须为数字", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    onSave.onSave(value);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 退出前关闭内网 ping 轮询定时
        InternalPingMonitor.stop();
        dismissSubmitProgress();
        executor.shutdownNow();
    }
}
