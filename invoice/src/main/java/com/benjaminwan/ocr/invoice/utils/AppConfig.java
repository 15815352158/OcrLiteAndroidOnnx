package com.benjaminwan.ocr.invoice.utils;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 本地配置管理：读写 filesDir/conf.json。
 * 字段：内网地址、外网地址、端口、Token、是否自动提交。
 * 文件缺失或损坏时全部回退默认值。
 */
public class AppConfig {

    private static final String TAG = "AppConfig";
    private static final String CONF_FILE = "conf.json";

    public static final String DEFAULT_INTERNAL_IP = "10.129.0.239";
    public static final String DEFAULT_EXTERNAL_IP = "122.91.119.142";
    public static final int DEFAULT_PORT = 12888;
    public static final String DEFAULT_TOKEN =
            "f931a466c79e818794144b2d57e153ec12dce5cfa5b26691231e75159172de1f";

    private static final String KEY_INTERNAL_IP = "internalIp";
    private static final String KEY_EXTERNAL_IP = "externalIp";
    private static final String KEY_PORT = "port";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_AUTO_SUBMIT = "autoSubmit";
    private static final String KEY_CONTINUOUS_SCAN = "continuousScan";

    private static AppConfig instance;

    private final File confFile;
    private String internalIp = DEFAULT_INTERNAL_IP;
    private String externalIp = DEFAULT_EXTERNAL_IP;
    private int port = DEFAULT_PORT;
    private String token = DEFAULT_TOKEN;
    private boolean autoSubmit = false;
    /** 连续扫描：扫描结果提交成功后自动重新进入同一扫描页 */
    private boolean continuousScan = false;

    public static synchronized AppConfig get(Context context) {
        if (instance == null) {
            instance = new AppConfig(context.getApplicationContext());
        }
        return instance;
    }

    private AppConfig(Context context) {
        confFile = new File(context.getFilesDir(), CONF_FILE);
        load();
    }

    private void load() {
        if (!confFile.exists()) {
            Log.i(TAG, "conf.json 不存在，使用默认配置");
            return;
        }
        try (FileInputStream fis = new FileInputStream(confFile);
             InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            JSONObject json = new JSONObject(sb.toString());
            internalIp = json.optString(KEY_INTERNAL_IP, DEFAULT_INTERNAL_IP);
            externalIp = json.optString(KEY_EXTERNAL_IP, DEFAULT_EXTERNAL_IP);
            port = json.optInt(KEY_PORT, DEFAULT_PORT);
            token = json.optString(KEY_TOKEN, DEFAULT_TOKEN);
            autoSubmit = json.optBoolean(KEY_AUTO_SUBMIT, false);
            continuousScan = json.optBoolean(KEY_CONTINUOUS_SCAN, false);
            Log.i(TAG, "已加载配置: internal=" + internalIp + " external=" + externalIp
                    + " port=" + port + " autoSubmit=" + autoSubmit
                    + " tokenLen=" + token.length());
        } catch (Exception e) {
            Log.e(TAG, "conf.json 解析失败，回退默认配置: " + e.getMessage());
        }
    }

    private void save() {
        try {
            JSONObject json = new JSONObject();
            json.put(KEY_INTERNAL_IP, internalIp);
            json.put(KEY_EXTERNAL_IP, externalIp);
            json.put(KEY_PORT, port);
            json.put(KEY_TOKEN, token);
            json.put(KEY_AUTO_SUBMIT, autoSubmit);
            json.put(KEY_CONTINUOUS_SCAN, continuousScan);
            try (FileOutputStream fos = new FileOutputStream(confFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(json.toString(2));
            }
            Log.i(TAG, "配置已保存: " + confFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "保存配置失败: " + e.getMessage());
        }
    }

    public String getInternalIp() {
        return internalIp;
    }

    public void setInternalIp(String ip) {
        internalIp = ip;
        save();
    }

    public String getExternalIp() {
        return externalIp;
    }

    public void setExternalIp(String ip) {
        externalIp = ip;
        save();
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
        save();
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
        save();
    }

    public boolean isAutoSubmit() {
        return autoSubmit;
    }

    public void setAutoSubmit(boolean autoSubmit) {
        this.autoSubmit = autoSubmit;
        save();
    }

    public boolean isContinuousScan() {
        return continuousScan;
    }

    public void setContinuousScan(boolean continuousScan) {
        this.continuousScan = continuousScan;
        save();
    }

    /** 恢复内/外网地址、端口、Token 四项为默认值（自动提交开关不变） */
    public void restoreDefaults() {
        internalIp = DEFAULT_INTERNAL_IP;
        externalIp = DEFAULT_EXTERNAL_IP;
        port = DEFAULT_PORT;
        token = DEFAULT_TOKEN;
        save();
        Log.i(TAG, "已恢复默认配置");
    }
}
