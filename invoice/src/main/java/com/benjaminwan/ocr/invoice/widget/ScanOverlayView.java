package com.benjaminwan.ocr.invoice.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 摄像头扫描覆盖层：中央长方形取景框 + 动态扫描线。
 * startAnimation()/stopAnimation() 控制扫描线动画。
 */
public class ScanOverlayView extends View {

    /** 扫描线一个来回周期的时长 */
    private static final long SCAN_PERIOD_MS = 2400;

    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanPaint = new Paint();
    private final RectF frameRect = new RectF();
    private final Path outsidePath = new Path();
    private final Path insidePath = new Path();

    private float cornerRadius;
    private float scanLineWidth;
    private volatile boolean animating;
    private boolean rectValid;

    public ScanOverlayView(Context context) {
        this(context, null);
    }

    public ScanOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScanOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        cornerRadius = 12 * density;
        scanLineWidth = 2 * density;

        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(3 * density);
        framePaint.setColor(0xFF00E5A0);

        maskPaint.setStyle(Paint.Style.FILL);
        maskPaint.setColor(0x66000000);

        scanPaint.setStyle(Paint.Style.FILL);
    }

    public void startAnimation() {
        animating = true;
        postInvalidateOnAnimation();
    }

    /**
     * 取景矩形框在本视图坐标系中的位置。
     * 尚未完成首次绘制时返回 false。
     */
    public boolean getScanRect(RectF out) {
        if (!rectValid) {
            return false;
        }
        out.set(frameRect);
        return true;
    }

    public void stopAnimation() {
        animating = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        // 中央长方形取景框：宽 84%，高 22%，垂直居中偏上
        float rectW = w * 0.84f;
        float rectH = h * 0.22f;
        float cx = w / 2f;
        float cy = h * 0.45f;
        frameRect.set(cx - rectW / 2, cy - rectH / 2, cx + rectW / 2, cy + rectH / 2);
        rectValid = true;

        // 框外区域压暗，突出取景框
        outsidePath.reset();
        outsidePath.addRect(0, 0, w, h, Path.Direction.CW);
        insidePath.reset();
        insidePath.addRoundRect(frameRect, cornerRadius, cornerRadius, Path.Direction.CW);
        outsidePath.op(insidePath, Path.Op.DIFFERENCE);
        canvas.drawPath(outsidePath, maskPaint);

        canvas.drawRoundRect(frameRect, cornerRadius, cornerRadius, framePaint);

        if (animating) {
            // 扫描线在框内上下往返
            float t = (SystemClock.uptimeMillis() % SCAN_PERIOD_MS) / (float) SCAN_PERIOD_MS;
            float triangle = t < 0.5f ? t * 2 : (1 - t) * 2; // 0..1..0
            float bandHeight = frameRect.height() * 0.25f;
            float lineY = frameRect.top + triangle * (frameRect.height() - bandHeight);

            scanPaint.setShader(new LinearGradient(
                    0, lineY, 0, lineY + bandHeight,
                    new int[]{0x0000E5A0, 0x6600E5A0, 0xFF00FFC8},
                    new float[]{0f, 0.8f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.save();
            canvas.clipPath(insidePath);
            canvas.drawRect(frameRect.left, lineY, frameRect.right, lineY + bandHeight, scanPaint);
            canvas.restore();

            postInvalidateOnAnimation();
        } else {
            // 静止状态也画一条居中的亮线，提示取景位置
            float lineY = frameRect.centerY();
            canvas.drawRect(frameRect.left, lineY - scanLineWidth / 2,
                    frameRect.right, lineY + scanLineWidth / 2, framePaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        animating = false;
        super.onDetachedFromWindow();
    }
}
