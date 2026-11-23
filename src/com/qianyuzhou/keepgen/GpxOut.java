package com.qianyuzhou.keepgen;

import java.io.OutputStream;

/**
 * 导出 GPX。
 *
 * 为什么还要 GPX：Keep 和 Strava 吃 TCX/FIT，但**华为运动健康、小米运动健康只认 GPX
 * 的「路线导入」**（导入后是"我的路线"里的一条线，不会生成运动记录 —— 这是它们的设计，
 * 不是我们没做好）。所以除了 TCX/FIT，再给一份 GPX，让用户至少能在这些 App 里看到轨迹。
 *
 * 坐标保持 WGS84：GPX 规范要求 WGS84，别在这里转 GCJ-02。
 * （转火星坐标只在「上传给 Keep」那一步做，见 KeepBody。）
 */
final class GpxOut {

    private GpxOut() {}

    static String build(Generator.Result r, String name) {
        StringBuilder sb = new StringBuilder(32 * 1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"keepgen\" ")
          .append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
          .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
          .append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 ")
          .append("http://www.topografix.com/GPX/1/1/gpx.xsd\">\n");
        sb.append("  <metadata><time>").append(Generator.iso(r.startMs)).append("</time></metadata>\n");
        sb.append("  <trk><name>").append(esc(name)).append("</name><type>running</type><trkseg>\n");

        double[][] ll = r.latlon;
        if (ll != null) {
            for (int i = 0; i < ll.length; i++) {
                long t = r.startMs + Math.round(i * r.dt * 1000);
                sb.append("    <trkpt lat=\"").append(fmt(ll[i][0]))
                  .append("\" lon=\"").append(fmt(ll[i][1])).append("\">");
                sb.append("<ele>0</ele><time>").append(Generator.iso(t)).append("</time>");
                if (r.hrs != null && i < r.hrs.length) {
                    sb.append("<extensions><gpxtpx:TrackPointExtension ")
                      .append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">")
                      .append("<gpxtpx:hr>").append(r.hrs[i]).append("</gpxtpx:hr>")
                      .append("</gpxtpx:TrackPointExtension></extensions>");
                }
                sb.append("</trkpt>\n");
            }
        }
        sb.append("  </trkseg></trk>\n</gpx>\n");
        return sb.toString();
    }

    /** 写进某个流。调用方负责选文件位置。 */
    static void write(OutputStream os, Generator.Result r, String name) throws Exception {
        os.write(build(r, name).getBytes("UTF-8"));
        os.flush();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.6f", v);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}