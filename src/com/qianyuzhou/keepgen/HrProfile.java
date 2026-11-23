package com.qianyuzhou.keepgen;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 心率参数与推断模型。全部离线，不联网，不读传感器。
 *
 * 用户在「设置」里填三个数：
 *   restBpm   静息心率，默认 60
 *   maxBpm    最高心率，默认 190
 *   paceMax   最快 1 公里配速（秒），默认 240（4:00）
 *
 * 推断用 Karvonen 心率储备法：
 *   HRR   = max − rest
 *   ratio = 当前速度 / 最快配速对应速度
 *   %HRR  = clamp(0.35 + 0.65 × ratio^0.8, 0.30, 1.00)
 *   HR    = rest + %HRR × HRR
 *
 * 再叠三层真实感：热身爬坡、心率漂移、末段冲刺。
 */
final class HrProfile {

    static final int DEF_REST = 60;
    static final int DEF_MAX = 190;
    static final int DEF_PACE_MAX = 240;   // 4:00/km

    int restBpm = DEF_REST;
    int maxBpm = DEF_MAX;
    int paceMaxSec = DEF_PACE_MAX;

    private static final String PREF = "hr_profile";

    static HrProfile load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        HrProfile p = new HrProfile();
        p.restBpm = sp.getInt("rest", DEF_REST);
        p.maxBpm = sp.getInt("max", DEF_MAX);
        p.paceMaxSec = sp.getInt("paceMax", DEF_PACE_MAX);
        p.sanitize();
        return p;
    }

    void save(Context c) {
        sanitize();
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt("rest", restBpm)
                .putInt("max", maxBpm)
                .putInt("paceMax", paceMaxSec)
                .apply();
    }

    /** 把用户乱填的数拉回合理区间 */
    void sanitize() {
        if (restBpm < 35) restBpm = 35;
        if (restBpm > 110) restBpm = 110;
        if (maxBpm < 120) maxBpm = 120;
        if (maxBpm > 230) maxBpm = 230;
        if (maxBpm <= restBpm + 20) maxBpm = restBpm + 20;
        if (paceMaxSec < 150) paceMaxSec = 150;   // 2:30
        if (paceMaxSec > 600) paceMaxSec = 600;   // 10:00
    }

    int hrr() { return maxBpm - restBpm; }

    /**
     * 给定瞬时速度（m/s）与已跑秒数，算心率。
     * @param v       瞬时速度
     * @param tSec    已跑秒数
     * @param durSec  总时长
     */
    int hrAt(double v, double tSec, double durSec) {
        double vMax = 1000.0 / paceMaxSec;
        double ratio = vMax > 0 ? v / vMax : 1.0;
        if (ratio < 0) ratio = 0;
        if (ratio > 1.15) ratio = 1.15;

        // 强度曲线：跑步的 %HRR 与相对速度不是纯线性，加一点指数压低压速段的斜率
        double pct = 0.35 + 0.65 * Math.pow(Math.min(1.0, ratio), 0.8);
        if (pct < 0.30) pct = 0.30;
        if (pct > 1.00) pct = 1.00;

        double hr = restBpm + pct * hrr();

        // 1) 热身：前 180 秒从静息线性升到目标强度
        double warm = Math.min(1.0, tSec / 180.0);
        hr = restBpm + (hr - restBpm) * warm;

        // 2) 心率漂移：长时间运动心率上浮，约每小时 +3%
        if (tSec > 600) {
            double hours = (tSec - 600) / 3600.0;
            hr *= 1.0 + 0.03 * hours;
        }

        // 3) 末段冲刺：最后 90 秒加 5~8 bpm，越靠近终点越明显
        double left = durSec - tSec;
        if (left < 90 && left > 0) {
            double kick = (1.0 - left / 90.0) * 7.0;
            hr += kick;
        }

        // 生理上限
        if (hr > maxBpm) hr = maxBpm;
        if (hr < restBpm * 0.85) hr = restBpm * 0.85;
        return (int) Math.round(hr);
    }

    /** 给界面显示的一句话摘要 */
    String summary() {
        return "静息 " + restBpm + "　最高 " + maxBpm + "　最快 1km "
                + Generator.clock(paceMaxSec);
    }

    /** 估算这套参数跑某个配速的稳定心率，用于界面预览 */
    int steadyHr(double paceSec) {
        double v = 1000.0 / paceSec;
        double vMax = 1000.0 / paceMaxSec;
        double ratio = vMax > 0 ? v / vMax : 1.0;
        double pct = 0.35 + 0.65 * Math.pow(Math.min(1.0, ratio), 0.8);
        if (pct > 1.0) pct = 1.0;
        if (pct < 0.30) pct = 0.30;
        return (int) Math.round(restBpm + pct * hrr());
    }
}
