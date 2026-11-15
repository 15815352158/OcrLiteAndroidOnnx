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

    /** 独立的 12 位 / 10 位数字串（发票代码） */
    private static final Pattern CODE_12 = Pattern.compile("(?<!\\d)\\d{12}(?!\\d)");
    private static final Pattern CODE_10 = Pattern.compile("(?<!\\d)\\d{10}(?!\\d)");

    /**
     * 当 OCR 提取到的发票号码为 8 位时，在 OCR 全文中查找连续的 12 位或 10 位发票代码，
     * 找到则拼接为 20 位 / 18 位，使结果长度与二维码扫描解析保持一致；找不到则保持 8 位。
     */
    public static String concatCode(OcrResult result, String number) {
        if (number == null || number.length() != 8 || result == null) {
            return number;
        }
        String flat = result.getStrRes() == null
                ? "" : result.getStrRes().replaceAll("\\s+", "");
        Matcher m12 = CODE_12.matcher(flat);
        if (m12.find()) {
            return m12.group() + number;
        }
        Matcher m10 = CODE_10.matcher(flat);
        if (m10.find()) {
            return m10.group() + number;
        }
        return number;
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

    /** 关键字"发票号码"后紧跟的数字值（20位或8位），允许中间有冒号等少量非数字 */
    private static final Pattern KEY_NUMBER =
            Pattern.compile("发票号码(?!代码)[^\\d]{0,6}(\\d{20}|\\d{8})(?!\\d)");
    /** 关键字"发票代码"后紧跟的数字值（12位或10位） */
    private static final Pattern KEY_CODE =
            Pattern.compile("发票代码[^\\d]{0,6}(\\d{12}|\\d{10})(?!\\d)");

    /**
     * 从发票二维码内容中解析发票号码（按关键字提取）：
     * 1. 找"发票号码"关键字后面的数字：20 位（数电票）直接返回，不需要发票代码；
     * 2. 8 位号码：发票代码可能是 12 位、10 位或没有，
     *    拼接结果相应为 20 位 / 18 位 / 8 位；
     * 3. "校验码"后的数字不匹配任何关键字，天然被排除；
     * 4. 兜底：无关键字的逗号分隔格式，按字段位置取 20位 / 12或10+8位。
     */
    public static String parseQrContent(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        // 去掉所有空白：兼容标签与值分行、数字间带空格印刷
        String flat = content.replaceAll("\\s+", "");

        String number = null;
        Matcher mn = KEY_NUMBER.matcher(flat);
        if (mn.find()) {
            number = mn.group(1);
        }
        String code = null;
        Matcher mc = KEY_CODE.matcher(flat);
        if (mc.find()) {
            code = mc.group(1);
        }
        if (number != null) {
            if (number.length() == 20) {
                return number; // 数电票：20位号码本身
            }
            return code != null ? code + number : number;
        }
        // 兜底：无关键字逗号/分号分隔格式（如 01,10,发票代码,发票号码,...,校验码）
        String[] fields = flat.split("[,，;；]");
        String codeF = null;
        for (int i = 0; i < fields.length; i++) {
            String f = fields[i].trim();
            if (f.matches("\\d{20}")) {
                return f; // 数电票号字段
            }
            if (codeF == null && f.matches("\\d{12}|\\d{10}")) {
                codeF = f;
                if (i + 1 < fields.length && fields[i + 1].trim().matches("\\d{8}")) {
                    return codeF + fields[i + 1].trim();
                }
            }
        }
        return null;
    }
}
