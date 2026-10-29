package com.example.ershima;

import android.content.Context;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;

/**
 * 原生振动桥：网页通过 window.NativeVibe 调用这里的方法，
 * 由系统 Vibrator 直接驱动马达——小米系统无法屏蔽（区别于网页 Vibration API）。
 *
 * 网页传来的 pattern 是 [on, off, on, off, ...]（第一个值就是"振动时长"）。
 * Android 的 vibrate(long[] pattern, int repeat) 要求 pattern[0] 是"启动前的等待(off)"，
 * 因此这里在开头补一个 0，变成 [0, on, off, on, off, ...]，节奏才正确。
 */
public class VibeBridge {

    private final Vibrator vibrator;

    public VibeBridge(Context context) {
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    @JavascriptInterface
    public void vibrate(String patternJson) {
        if (vibrator == null) return;
        try {
            JSONArray arr = new JSONArray(patternJson);
            long[] src = new long[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                src[i] = arr.optLong(i, 0);
            }
            long[] pat = new long[src.length + 1];
            pat[0] = 0; // 启动前等待（off）
            System.arraycopy(src, 0, pat, 1, src.length);
            vibrator.cancel();
            vibrator.vibrate(pat, -1); // -1：不循环，播完即停（网页侧会按节拍续批）
        } catch (Exception ignored) {
            // 任何异常都静默处理，避免影响网页
        }
    }

    @JavascriptInterface
    public void stop() {
        if (vibrator == null) return;
        try {
            vibrator.cancel();
        } catch (Exception ignored) {
        }
    }
}
