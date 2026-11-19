package com.benjaminwan.ocr.invoice.utils;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 发票防重检测接口：POST https://{ip}:{port}/api/invoices
 * 鉴权使用 X-API-Token 头（API文档.md 第4节外部系统调用方式）。
 * 服务端为自签名证书，信任全部证书与主机名（等价 curl -k）。
 * 优先内网地址提交，连接异常自动回退外网地址重试。
 */
public class InvoiceApi {

    private static final String TAG = "InvoiceApi";
    private static final int TIMEOUT_MS = 5000;

    /** 提交结果类型（用 int 常量而非枚举，规避旧版 D8 对嵌套枚举的 dex NPE） */
    /** HTTP 200 新发票录入成功 */
    public static final int KIND_SUCCESS = 0;
    /** HTTP 409 发票号码重复 */
    public static final int KIND_DUPLICATE = 1;
    /** 其他失败：错误状态码或内外网均不可达 */
    public static final int KIND_ERROR = 2;

    /** 提交结果：kind + 可展示给用户的信息 */
    public static class Result {
        public final int kind;
        public final String message;

        Result(int kind, String message) {
            this.kind = kind;
            this.message = message;
        }
    }

    private static SSLContext trustAllSslContext;

    private static synchronized SSLContext getTrustAllSslContext() throws Exception {
        if (trustAllSslContext == null) {
            TrustManager[] trustManagers = {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            trustAllSslContext = SSLContext.getInstance("TLS");
            trustAllSslContext.init(null, trustManagers, new java.security.SecureRandom());
        }
        return trustAllSslContext;
    }

    private static final HostnameVerifier TRUST_ALL_HOSTNAME = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    /**
     * 提交发票号码做防重检测（同步方法，必须在子线程调用）。
     * 先试内网，连接异常再试外网。
     */
    public static Result submit(AppConfig conf, String invoiceNumber) {
        String body = "{\"invoiceNumber\":\"" + invoiceNumber + "\"}";
        Result internal = postTo(conf.getInternalIp(), conf, invoiceNumber, body, "内网");
        if (internal != null) {
            return internal;
        }
        Log.i(TAG, "内网提交失败，回退外网 " + conf.getExternalIp());
        Result external = postTo(conf.getExternalIp(), conf, invoiceNumber, body, "外网");
        if (external != null) {
            return external;
        }
        return new Result(KIND_ERROR, "网络错误：内网(" + conf.getInternalIp()
                + ")与外网(" + conf.getExternalIp() + ")均连接失败");
    }

    /**
     * 向指定地址提交。
     *
     * @return 服务器有响应时返回结果；连接层异常（不可达/超时/SSL）返回 null 以便回退
     */
    private static Result postTo(String ip, AppConfig conf, String invoiceNumber,
                                 String body, String netLabel) {
        String url = "https://" + ip + ":" + conf.getPort() + "/api/invoices";
        Log.i(TAG, netLabel + "提交: " + url + " invoiceNumber=" + invoiceNumber);
        HttpsURLConnection conn = null;
        try {
            conn = (HttpsURLConnection) new java.net.URL(url).openConnection();
            conn.setSSLSocketFactory(getTrustAllSslContext().getSocketFactory());
            conn.setHostnameVerifier(TRUST_ALL_HOSTNAME);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-API-Token", conf.getToken());
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String resp = readStream(code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream());
            Log.i(TAG, netLabel + "响应 code=" + code + " body=" + resp);
            if (code == 200) {
                return new Result(KIND_SUCCESS, "发票OK");
            }
            if (code == 409) {
                String msg = jsonField(resp, "message");
                return new Result(KIND_DUPLICATE,
                        msg != null ? msg : "发票号码已存在（重复）");
            }
            String detail = jsonField(resp, "detail");
            return new Result(KIND_ERROR, "服务器错误(" + netLabel + " HTTP " + code + ")："
                    + (detail != null ? detail : resp));
        } catch (Exception e) {
            Log.w(TAG, netLabel + "连接异常: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readStream(InputStream is) {
        if (is == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            is.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 从 JSON 响应中取字符串字段，解析失败返回 null */
    private static String jsonField(String json, String key) {
        try {
            return new org.json.JSONObject(json).optString(key, null);
        } catch (Exception e) {
            return null;
        }
    }
}
