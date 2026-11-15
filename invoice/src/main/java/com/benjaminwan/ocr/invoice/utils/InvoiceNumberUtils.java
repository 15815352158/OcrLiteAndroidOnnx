package com.benjaminwan.ocr.invoice.utils;

import com.benjaminwan.ocrlibrary.OcrResult;
import com.benjaminwan.ocrlibrary.TextBlock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发票号码提取与校验规则：
 * 1. 检测框过滤：文本长度超过 20 字符的矩形框（整段标题、表格行等）直接丢弃，
 *    发票号所在框大约 20 字符长度以内，小框识别更准；
 * 2. 号码校验：发票号码必须是 8 位（传统发票）或 20 位（数电发票）纯数字，
 *    否则视为无效，不出结果。
 */
public class InvoiceNumberUtils {

    /** 单个检测框参与提取的最大文本长度（约 20 个字符） */
    public static final int MAX_BLOCK_LEN = 20;

    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{8,20}");

    /** 校验是否为合法发票号码：恰好 8 位或 20 位纯数字 */
    public static boolean isValidInvoiceNumber(String num) {
        return num != null && (num.length() == 8 || num.length() == 20);
    }

    /**
     * 从 OCR 结果中提取所有合法发票号码（8 位或 20 位），
     * 仅扫描长度 <= MAX_BLOCK_LEN 的小矩形框。
     */
    public static List<String> extract(OcrResult result) {
        Set<String> matched = new LinkedHashSet<>();
        if (result == null) {
            return new ArrayList<>();
        }
        for (TextBlock block : result.getTextBlocks()) {
            String line = block.getText();
            if (line == null) {
                continue;
            }
            String compact = line.replaceAll("[\\s,，.。:：]", "");
            // 矩形框不要太大：超过 20 字符的框不参与发票号提取
            if (compact.length() > MAX_BLOCK_LEN) {
                continue;
            }
            Matcher m = DIGIT_RUN.matcher(compact);
            while (m.find()) {
                String num = m.group();
                if (isValidInvoiceNumber(num)) {
                    matched.add(num);
                }
            }
        }
        return new ArrayList<>(matched);
    }
}
