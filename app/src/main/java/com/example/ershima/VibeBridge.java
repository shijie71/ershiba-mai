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

import java.util.ArrayList;
import java.util.List;

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
    private volatile long playToken = 0;   // 每次 vibrate/stop 递增，使旧序列的待执行任务作废

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

    /**
     * 触发振动（从网页传入 JSON 数组）。
     * 交错格式 [时长, 振幅, 时长, 振幅, ...]（网页 buildHaptic 产出）：
     *   偶数索引=该段时长，奇数索引=该段振幅；振幅 0 = 真正静默（即“停”）。
     * 单值 [500] = 测试模式单次最大振幅。
     *
     * 关键修法（根治红米 K80 线性马达“持续嗡嗡”）：
     *   不再用 createWaveform 连续波形（LRA 会把它重采样成平滑嗡嗡，短静默压不住惯性），
     *   改为【离散的独立单次振动】——每个“搏动段”单独 createOneShot，振幅=0 的段就真不触发，
     *   让马达在两拍之间彻底停下，听感是“咚—停—咚—停”。
     */
    @JavascriptInterface
    public void vibrate(String patternJson) {
        final long[] src;
        try {
            JSONArray arr = new JSONArray(patternJson);
            src = new long[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                src[i] = Math.max(0L, (long) arr.optDouble(i, 0));
            }
        } catch (Exception e) {
            return;
        }
        final long token = ++playToken;
        // 务必在主线程触发，规避 HyperOS 对后台线程振动的静默忽略
        mainHandler.post(() -> scheduleBeats(src, token));
    }

    private void scheduleBeats(long[] src, long token) {
        try {
            Vibrator v = vibrator;
            if (v == null || !v.hasVibrator()) return;
            v.cancel();

            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)            // 绕过勿扰/系统静音
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            // 把 src 规整成【段】列表：每段 [时长, 振幅]
            List<long[]> segs = new ArrayList<>();
            if (src.length == 1) {
                segs.add(new long[]{ Math.max(1, src[0]), 255 });           // 测试：单次强振
            } else if (src.length % 2 == 0) {
                for (int i = 0; i + 1 < src.length; i += 2) {
                    segs.add(new long[]{ Math.max(1, src[i]),
                            (int) Math.max(0, Math.min(255, src[i + 1])) });
                }
            } else {
                // 兼容旧式纯时长 [on, off, on, ...]：奇索引振动(255)、偶索引静默(0)
                for (int i = 0; i < src.length; i++) {
                    segs.add(new long[]{ Math.max(1, src[i]), (i % 2 == 0) ? 255 : 0 });
                }
            }

            // 离散触发：每一【振动段】独立 createOneShot；振幅=0 的段不触发（即真实静默）。
            // 两拍之间的静默让线性马达彻底停下 —— 这是消除“持续嗡嗡”的核心。
            long base = 0;
            for (long[] s : segs) {
                final long dur = s[0];
                final int amp = (int) s[1];
                final long at = base;
                if (amp > 0) {
                    mainHandler.postDelayed(() -> {
                        if (token != playToken) return;     // 已被新一次 vibrate/stop 取代
                        try {
                            Vibrator vv = vibrator;
                            if (vv == null || !vv.hasVibrator()) return;
                            vv.cancel();
                            vv.vibrate(VibrationEffect.createOneShot(dur, amp), aa);
                        } catch (Exception ignore) {
                        }
                    }, at);
                }
                base += dur;
            }
            // 末尾兜底取消，避免任何残留驱动
            final long end = base;
            mainHandler.postDelayed(() -> {
                if (token != playToken) return;
                try {
                    if (vibrator != null) vibrator.cancel();
                } catch (Exception ignore) {
                }
            }, end + 40);
        } catch (Exception e) {
            // 兜底：无 VibrationEffect 的旧写法
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

    /** 停止振动（同时使所有待执行的旧脉冲序列作废） */
    @JavascriptInterface
    public void stop() {
        playToken++;
        mainHandler.post(() -> {
            try {
                if (vibrator != null) vibrator.cancel();
            } catch (Exception ignore) {
            }
        });
    }
}
