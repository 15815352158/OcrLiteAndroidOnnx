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
        // 只查原文：代码数字串内部含空格/非数字即不算连续，直接排除
        String flat = result.getStrRes() == null ? "" : result.getStrRes();
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
        // 按行过滤：含"校验码"的行整行跳过（票面校验码是20位数字，会被误当成数电发票号码）
        for (String line : text.split("\\r?\\n")) {
            // 模糊匹配"校X码"，兼容 OCR 误识别（校脸码/校检码等）；先压缩空格
            String compact = line.replace(" ", "");
            if (compact.matches(".*校.{0,1}码.*") || compact.contains("校验码")) {
                continue;
            }
            // 只按原文匹配：数字之间有空格或非数字字符即视为不连续，直接排除，不做删除/粘合处理
            matchInto(line, nums20, nums8);
        }
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

    /**
     * 从发票二维码内容中解析发票号码（按位置提取）：
     * 原始内容以 ',' 分隔，取第 3 组（发票代码）与第 4 组（发票号码）拼接；
     * 某组为空（如数电票无发票代码）则只取另一组。
     */
    public static String parseQrContent(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        String[] fields = content.split(",");
        if (fields.length < 4) {
            return null;
        }
        String code = fields[2].trim();
        String number = fields[3].trim();
        if (code.isEmpty() && number.isEmpty()) {
            return null;
        }
        return code + number;
    }
}
