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
 * 1. 扫描 OCR 结果的每一行文本（以及全文），检测"连续数字字符串"；
 * 2. 数字串长度恰好为 8 位（传统发票号码）或 20 位（数电发票号码）才合法；
 *    10/12 位发票代码、9~19 位等其它长度一律丢弃；
 * 3. 若 8 位和 20 位号码都存在，优先取 20 位作为发票号码（返回列表第一个即最终号码）。
 *
 * 为兼容 "0 4 5 1 2 3 4 5" 这种带空格的印刷格式，
 * 每行会按原文与去空格文本各扫一遍。
 */
public class InvoiceNumberUtils {

    /** 恰好 20 位、且前后不紧邻其它数字 */
    private static final Pattern RUN_20 = Pattern.compile("(?<!\\d)\\d{20}(?!\\d)");
    /** 恰好 8 位、且前后不紧邻其它数字 */
    private static final Pattern RUN_8 = Pattern.compile("(?<!\\d)\\d{8}(?!\\d)");

    /** 校验是否为合法发票号码：恰好 8 位或 20 位纯数字 */
    public static boolean isValidInvoiceNumber(String num) {
        return num != null && (num.length() == 8 || num.length() == 20);
    }

    /**
     * 从 OCR 结果中提取所有合法发票号码，20 位优先排在列表前面。
     */
    public static List<String> extract(OcrResult result) {
        Set<String> nums20 = new LinkedHashSet<>();
        Set<String> nums8 = new LinkedHashSet<>();
        if (result == null) {
            return new ArrayList<>();
        }
        for (TextBlock block : result.getTextBlocks()) {
            collect(block.getText(), nums20, nums8);
        }
        // 兜底再扫一遍全文，防止个别行遗漏
        collect(result.getStrRes(), nums20, nums8);

        List<String> out = new ArrayList<>(nums20.size() + nums8.size());
        out.addAll(nums20); // 20 位优先
        out.addAll(nums8);
        return out;
    }

    private static void collect(String text, Set<String> nums20, Set<String> nums8) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // 原文：避免去空格后把两个相邻号码粘成一个长数字串
        matchInto(text, nums20, nums8);
        // 去空格：兼容数字之间被印出空格的情况
        matchInto(text.replaceAll("[\\s,，.。:：]", ""), nums20, nums8);
    }

    private static void matchInto(String text, Set<String> nums20, Set<String> nums8) {
        Matcher m20 = RUN_20.matcher(text);
        while (m20.find()) {
            nums20.add(m20.group());
        }
        Matcher m8 = RUN_8.matcher(text);
        while (m8.find()) {
            nums8.add(m8.group());
        }
    }
}
