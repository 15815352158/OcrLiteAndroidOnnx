package com.benjaminwan.ocr.invoice.utils;

import android.util.Log;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 内网轮询监控：切换到外网优先后，定时在后台线程 ping 内网 IP。
 * ping 通 → 切回内网优先并关闭定时；ping 不通 → 等待下一轮。
 * 退出 App 前必须调用 {@link #stop()} 关闭定时。
 */
public class InternalPingMonitor {

    private static final String TAG = "InternalPingMonitor";

    private static ScheduledExecutorService scheduler;

    /** 启动轮询（幂等：已启动则忽略）。间隔单位：分钟 */
    public static synchronized void start(final AppConfig conf, int intervalMinutes) {
        if (scheduler != null) {
            Log.i(TAG, "内网轮询已在运行，忽略重复启动");
            return;
        }
        if (intervalMinutes <= 0) {
            intervalMinutes = AppConfig.DEFAULT_PING_INTERVAL_MINUTES;
        }
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "internal-ping");
                t.setDaemon(true);
                return t;
            }
        };
        scheduler = new ScheduledThreadPoolExecutor(1, factory);
        Log.i(TAG, "启动内网轮询，间隔 " + intervalMinutes + " 分钟");
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                checkInternal(conf);
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    /** 停止轮询（幂等） */
    public static synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            Log.i(TAG, "内网轮询已停止");
        }
    }

    public static synchronized boolean isRunning() {
        return scheduler != null;
    }

    /** 用系统 ping 命令探测内网（InetAddress.isReachable 对 ICMP 不可靠） */
    private static void checkInternal(AppConfig conf) {
        String ip = conf.getInternalIp();
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/ping", "-c", "1", "-W", "3", ip});
            int exit = p.waitFor();
            if (exit == 0) {
                Log.i(TAG, "内网 " + ip + " ping 通，切回内网提交并关闭轮询");
                InvoiceApi.setPreferInternal(true);
                stop();
            } else {
                Log.i(TAG, "内网 " + ip + " ping 不通(exit=" + exit + ")，等待下一轮");
            }
        } catch (Exception e) {
            Log.w(TAG, "ping 执行异常: " + e.getMessage());
        }
    }
}
