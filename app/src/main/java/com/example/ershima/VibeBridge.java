package com.example.ershima;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;

/**
 * 原生振动桥：网页通过 window.NativeVibe 调用这里的方法，
 * 由系统 Vibrator 直接驱动马达——小米系统无法屏蔽（区别于网页 Vibration API）。
 *
 * 关键修法（针对红米 K80 / HyperOS / Android 12+）：
 *  - 旧写法 vibrator.vibrate(long[], int) 在新系统上常被静默降级或振幅归零，导致"看起来没震"。
 *  - 改用官方推荐的 VibrationEffect（createOneShot / createWaveform），并明确使用
 *    VibrationEffect.DEFAULT_AMPLITUDE，保证一定能驱动马达。
 *
 * 网页传来的 pattern 是 [on, off, on, off, ...]（第一个值即"振动时长"）。
 * Android 的 pattern 要求 pattern[0] 为"启动前等待(off)"，故在开头补一个 0。
 */
public class VibeBridge {

    private final Vibrator vibrator;
    private final boolean hasVib;

    public VibeBridge(Context context) {
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        this.hasVib = (vibrator != null) && vibrator.hasVibrator();
    }

    /** 设备是否有振动器（供网页诊断显示） */
    @JavascriptInterface
    public boolean hasVibrator() {
        return hasVib;
    }

    /** 触发振动；返回是否成功（供网页诊断显示） */
    @JavascriptInterface
    public boolean vibrate(String patternJson) {
        if (vibrator == null || !hasVib) return false;
        try {
            long[] src;
            try {
                JSONArray arr = new JSONArray(patternJson);
                src = new long[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    src[i] = arr.optLong(i, 0);
                }
            } catch (Exception e) {
                return false;
            }

            // Android 要求 pattern[0] 为启动前等待（off）
            long[] pat = new long[src.length + 1];
            pat[0] = 0;
            System.arraycopy(src, 0, pat, 1, src.length);

            vibrator.cancel();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect;
                if (pat.length == 2) {
                    // 单次振动：用 createOneShot + 默认振幅（最可靠）
                    effect = VibrationEffect.createOneShot(pat[1], VibrationEffect.DEFAULT_AMPLITUDE);
                } else {
                    effect = VibrationEffect.createWaveform(pat, -1);
                }
                vibrator.vibrate(effect);
            } else {
                vibrator.vibrate(pat, -1);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 停止振动；返回是否成功 */
    @JavascriptInterface
    public boolean stop() {
        if (vibrator == null) return false;
        try {
            vibrator.cancel();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
