package com.example.ershima;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;

/**
 * 原生振动桥：网页通过 window.NativeVibe 调用，由系统 Vibrator 直接驱动马达。
 *
 * 针对红米 K80 / HyperOS / Android 12+ 的已知坑，本版做了三处关键修法：
 *  1. WebView 的 @JavascriptInterface 回调跑在【后台线程】，HyperOS 会静默忽略
 *     非主线程发起的振动 —— 故所有振动都 post 到主线程执行。
 *  2. VibrationEffect 的“默认振幅”在 HyperOS 上常被压成 0 —— 强制用最大振幅(255)。
 *  3. 指定 AudioAttributes.USAGE_ALARM，绕过“勿扰 / 系统静音”对振动的拦截。
 *
 * 网页传来的 pattern 是 [on, off, on, off, ...]（首个值即“振动时长”）。
 * Android 的 pattern 要求 pattern[0] 为“启动前等待(off)”，故在开头补一个 0。
 */
public class VibeBridge {

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Vibrator vibrator;
    private volatile boolean hasVib;

    public VibeBridge(Context context) {
        this.context = context;
        initVibrator();
    }

    private void initVibrator() {
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                Object o = context.getSystemService(Context.VIBRATOR_SERVICE);
                v = (o instanceof Vibrator) ? (Vibrator) o : null;
            }
            this.vibrator = v;
            this.hasVib = (v != null) && v.hasVibrator();
        } catch (Exception e) {
            this.vibrator = null;
            this.hasVib = false;
        }
    }

    /** 设备是否有振动器（供网页诊断显示） */
    @JavascriptInterface
    public boolean hasVibrator() {
        return hasVib;
    }

    /** 触发振动（从网页传入 JSON 数组，如 [400] 或 [100,80,100,80,100]） */
    @JavascriptInterface
    public void vibrate(String patternJson) {
        final long[] src;
        try {
            JSONArray arr = new JSONArray(patternJson);
            src = new long[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                src[i] = Math.max(1L, (long) arr.optDouble(i, 0));
            }
        } catch (Exception e) {
            return;
        }
        // 务必在主线程触发，规避 HyperOS 对后台线程振动的静默忽略
        mainHandler.post(() -> doVibrate(src));
    }

    private void doVibrate(long[] src) {
        try {
            Vibrator v = vibrator;
            if (v == null || !v.hasVibrator()) return;

            long[] timings;
            int[] amps;
            if (src.length == 1) {
                // 测试模式：单次最大振幅（如 [500]）
                timings = new long[]{0, Math.max(1, src[0])};
                amps = new int[]{0, 255};
            } else if (src.length % 2 == 0) {
                // 交错格式：[时长, 振幅, 时长, 振幅, ...]（网页 buildHaptic 产出）
                // 偶数索引=该段时长，奇数索引=该段振幅；振幅 0 才是真正静默（即“停”）
                int n = src.length / 2;
                timings = new long[n + 1];
                amps = new int[n + 1];
                timings[0] = 0; amps[0] = 0; // 起始前静默等待
                for (int i = 0; i < src.length; i += 2) {
                    int k = i / 2 + 1;
                    timings[k] = Math.max(1, src[i]);
                    amps[k] = (int) Math.max(0, Math.min(255, src[i + 1]));
                }
            } else {
                // 兼容旧式纯时长 [on, off, on, off, ...]（奇数索引为振动段）
                timings = new long[src.length + 1];
                amps = new int[src.length + 1];
                timings[0] = 0; amps[0] = 0;
                System.arraycopy(src, 0, timings, 1, src.length);
                for (int i = 1; i < amps.length; i++) amps[i] = (i % 2 == 1) ? 255 : 0;
            }

            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)            // 绕过勿扰/系统静音
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            v.cancel();
            v.vibrate(VibrationEffect.createWaveform(timings, amps, -1), aa);
        } catch (Exception e) {
            // 兜底：无 AudioAttributes 的旧写法（部分老机型新 API 无效）
            try {
                Vibrator v = vibrator;
                if (v == null || !v.hasVibrator()) return;
                long[] pat = new long[src.length + 1];
                pat[0] = 0;
                System.arraycopy(src, 0, pat, 1, src.length);
                v.cancel();
                if (pat.length == 2) v.vibrate(pat[1]);
                else v.vibrate(pat, -1);
            } catch (Exception ignore) {
            }
        }
    }

    /** 停止振动 */
    @JavascriptInterface
    public void stop() {
        mainHandler.post(() -> {
            try {
                if (vibrator != null) vibrator.cancel();
            } catch (Exception ignore) {
            }
        });
    }
}
