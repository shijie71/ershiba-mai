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

            long[] pat = new long[src.length + 1];
            pat[0] = 0; // 起始前静默等待
            System.arraycopy(src, 0, pat, 1, src.length);

            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)            // 绕过勿扰/系统静音
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            VibrationEffect effect;
            if (pat.length == 2) {
                effect = VibrationEffect.createOneShot(pat[1], 255); // 单次：最大振幅
            } else {
                int[] amps = new int[pat.length];
                for (int i = 0; i < pat.length; i++) amps[i] = 255;  // 全程最大振幅
                effect = VibrationEffect.createWaveform(pat, amps, -1);
            }
            v.cancel();
            v.vibrate(effect, aa);
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
