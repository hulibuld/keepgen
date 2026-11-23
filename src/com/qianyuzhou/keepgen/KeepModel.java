package com.qianyuzhou.keepgen;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Keep 云端记录的精简模型。
 *
 * 只干一件事：把返回的那坨 JSON 拆成界面直接能用的字段。
 * 不落盘、不做 ORM —— 这些数据在云端随时会变，本地再存一份就得处理
 * 「本地旧数据 / 云端新数据」谁算数的问题，收益远小于成本。
 *
 * 字段名按 2026-11-23 在 9.1.130 账号上实测的结构写：
 *   records[].logs[].stats   距离是米、时长是秒、开始时间是毫秒
 *   详情多出 heartRate{} 与 crossKmPoints[]；geoPoints 是加密串，只能判断有没有。
 */
final class KeepModel {

    private KeepModel() {}

    /** 列表卡片一行 */
    static final class Item {
        String id = "";
        /** 查询用的大类（running/cycling/walking）。详情地址靠它拼，不能用 dataType */
        String type = "";
        String date = "";
        String name = "";
        String dataType = "";
        String vendor = "";
        double distM;
        double durSec;
        long startMs;
        int calorie;
        int steps;
        double spm;
        boolean doubtful;

        /** 秒/公里。距离缺失时返回 0，交给界面显示「—」 */
        double paceSec() {
            return distM > 0 ? durSec / (distM / 1000.0) : 0;
        }
    }

    /** 详情里的一段公里 */
    static final class Km {
        int index;
        double distM;
        double durSec;
        double paceSec;
        int steps;
        double spm;
    }

    /** 详情弹层要的全部字段 */
    static final class Detail {
        double distM;
        double durSec;
        double paceSec;
        int calorie;
        int steps;
        double spm;
        int avgHr;
        int maxHr;
        /** 只能知道有没有，内容解不开 */
        boolean hasTrack;
        boolean doubtful;
        final List<Km> kms = new ArrayList<>();
    }

    // ---- 列表 ----

    /**
     * 解析一次 history 列表。
     *
     * type 由调用方传进来（就是这次请求用的那个大类），不猜 dataType：
     * 详情地址是 /pd/v3/{type}log/{id}，用 dataType（outdoorRunning）拼出来是 404。
     */
    static List<Item> stats(String body, String type) {
        List<Item> out = new ArrayList<>();
        JSONObject root = obj(body);
        JSONObject data = root == null ? null : root.optJSONObject("data");
        JSONArray records = data == null ? null : data.optJSONArray("records");
        if (records == null) return out;

        for (int i = 0; i < records.length(); i++) {
            JSONObject rec = records.optJSONObject(i);
            if (rec == null) continue;
            String day = rec.optString("date", "");
            JSONArray logs = rec.optJSONArray("logs");
            if (logs == null) continue;
            for (int j = 0; j < logs.length(); j++) {
                JSONObject log = logs.optJSONObject(j);
                if (log == null) continue;
                // 只认 type=stats 这一种。别的结构（路线分享、计划打卡）不去猜，
                // 猜错会在列表里混进一条点不开的卡片。
                JSONObject st = log.optJSONObject("stats");
                if (st == null) continue;
                Item it = fromStats(st);
                it.type = type;
                if (it.date.isEmpty()) it.date = day;
                out.add(it);
            }
        }
        return out;
    }

    private static Item fromStats(JSONObject st) {
        Item it = new Item();
        it.id = st.optString("id", "");
        it.name = st.optString("name", "");
        it.dataType = st.optString("dataType", "");
        it.distM = num(st, 0, "distance", "totalDistance");
        it.durSec = num(st, 0, "duration", "totalDuration");
        it.startMs = st.optLong("startTime", 0);
        it.calorie = (int) Math.round(num(st, 0, "calorie", "calorieSum"));
        it.steps = (int) Math.round(num(st, 0, "totalSteps"));
        it.spm = num(st, 0, "averageStepFrequency");
        it.doubtful = st.optBoolean("doubtful", false);
        it.vendor = vendorOf(st);
        return it;
    }

    /** 来源只在界面上做一个小标签，读不到就空着，不瞎写「第三方」 */
    private static String vendorOf(JSONObject st) {
        JSONObject v = st.optJSONObject("vendor");
        if (v == null) return "";
        String s = v.optString("source", "");
        if (s.isEmpty()) s = v.optString("name", "");
        return s;
    }

    // ---- 详情 ----

    static Detail detail(String body) {
        Detail d = new Detail();
        JSONObject root = obj(body);
        if (root == null) return d;

        JSONObject st = recordOf(root.optJSONObject("data"));
        if (st == null) return d;

        d.distM = num(st, 0, "distance", "totalDistance");
        d.durSec = num(st, 0, "duration", "totalDuration");
        d.calorie = (int) Math.round(num(st, 0, "calorie"));
        d.steps = (int) Math.round(num(st, 0, "totalSteps"));
        d.spm = num(st, 0, "averageStepFrequency");
        d.doubtful = st.optBoolean("doubtful", false);

        JSONObject hr = st.optJSONObject("heartRate");
        if (hr != null) {
            d.avgHr = (int) Math.round(num(hr, 0, "averageHeartRate"));
            d.maxHr = (int) Math.round(num(hr, 0, "maxHeartRate"));
        }

        // geoPoints 是一整串加密内容，解不开也不该假装能解。
        // 只判断长度：真有轨迹的账号这里不会返回空串。
        String geo = st.optString("geoPoints", "");
        d.hasTrack = geo != null && geo.length() > 20;

        JSONArray kms = st.optJSONArray("crossKmPoints");
        if (kms != null) {
            for (int i = 0; i < kms.length(); i++) {
                JSONObject k = kms.optJSONObject(i);
                if (k == null) continue;
                Km km = new Km();
                km.index = i + 1;
                km.distM = num(k, 0, "totalDistance", "distance");
                km.durSec = num(k, 0, "totalDuration", "duration");
                km.paceSec = num(k, 0, "kmPace", "pace");
                // kmPace 没给的时候用「这公里用时 ÷ 公里数」倒推，总比显示「—」强
                if (!(km.paceSec > 0) && km.distM > 0) {
                    km.paceSec = km.durSec / (km.distM / 1000.0);
                }
                km.steps = (int) Math.round(num(k, 0, "totalSteps"));
                km.spm = num(k, 0, "stepFrequency", "averageStepFrequency");
                d.kms.add(km);
            }
        }

        // 顶层没给总距离/总步数时退回最后一公里，详情页不至于空着
        if (!(d.distM > 0) && !d.kms.isEmpty()) {
            d.distM = d.kms.get(d.kms.size() - 1).distM;
        }
        if (d.steps <= 0 && !d.kms.isEmpty()) {
            d.steps = d.kms.get(d.kms.size() - 1).steps;
        }
        d.paceSec = d.distM > 0 ? d.durSec / (d.distM / 1000.0) : 0;
        return d;
    }

    /**
     * 详情有时把记录又包一层。按「自己带 distance/duration」判断哪层才是记录，
     * 不写死某一个 key —— 三种运动的包装名实测就不一样。
     */
    private static JSONObject recordOf(JSONObject data) {
        if (data == null) return null;
        if (looks(data)) return data;
        String[] keys = {"stats", "record", "log", "sportLog", "data"};
        for (String k : keys) {
            JSONObject n = data.optJSONObject(k);
            if (looks(n)) return n;
        }
        return null;
    }

    private static boolean looks(JSONObject o) {
        return o != null && (o.has("distance") || o.has("duration") || o.has("totalDistance"));
    }

    // ---- 小工具 ----

    private static JSONObject obj(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** 依次试几个 key，第一个像数字的就用；都没有才落回默认值 */
    private static double num(JSONObject o, double fallback, String... keys) {
        for (String k : keys) {
            double v = o.optDouble(k, Double.NaN);
            if (!Double.isNaN(v)) return v;
        }
        return fallback;
    }
}