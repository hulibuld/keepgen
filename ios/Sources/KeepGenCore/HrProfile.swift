import Foundation

// MARK: - 心率模型（Karvonen 心率储备法）

/// 逐字移植 Android 版的 HrProfile 实现。
///
/// 用户填三个数：静息心率、最高心率、最快 1 公里配速（秒）。
///   HRR   = max − rest
///   ratio = 当前速度 / 最快配速对应速度
///   %HRR  = clamp(0.35 + 0.65 × ratio^0.8, 0.30, 1.00)
///   HR    = rest + %HRR × HRR
/// 再叠三层：热身爬坡（前 180 s）、心率漂移（600 s 后约 +3%/h）、末段冲刺（最后 90 s 最多 +7）。
public struct HrProfile: Codable, Hashable, Sendable {

    public static let defaultRest = 60
    public static let defaultMax = 190
    public static let defaultPaceMaxSec = 240   // 4:00 / km

    public var restBpm: Int
    public var maxBpm: Int
    /// 最快 1 公里配速，秒。越小越快。
    public var paceMaxSec: Int

    public init(restBpm: Int = HrProfile.defaultRest,
                maxBpm: Int = HrProfile.defaultMax,
                paceMaxSec: Int = HrProfile.defaultPaceMaxSec) {
        self.restBpm = restBpm
        self.maxBpm = maxBpm
        self.paceMaxSec = paceMaxSec
    }

    /// 把用户乱填的数拉回合理区间（同 Java `sanitize()`）。
    public func sanitized() -> HrProfile {
        var p = self
        if p.restBpm < 35 { p.restBpm = 35 }
        if p.restBpm > 110 { p.restBpm = 110 }
        if p.maxBpm < 120 { p.maxBpm = 120 }
        if p.maxBpm > 230 { p.maxBpm = 230 }
        if p.maxBpm <= p.restBpm + 20 { p.maxBpm = p.restBpm + 20 }
        if p.paceMaxSec < 150 { p.paceMaxSec = 150 }   // 2:30
        if p.paceMaxSec > 600 { p.paceMaxSec = 600 }   // 10:00
        return p
    }

    public var reserve: Int { maxBpm - restBpm }

    /// 给定瞬时速度（m/s）与已跑秒数，算心率。
    public func hrAt(speedMetresPerSecond v: Double,
                     elapsed tSec: Double,
                     duration durSec: Double) -> Int {
        let vMax = 1000.0 / Double(max(paceMaxSec, 1))
        var ratio = vMax > 0 ? v / vMax : 1.0
        if ratio < 0 { ratio = 0 }
        if ratio > 1.15 { ratio = 1.15 }

        // 强度曲线：%HRR 与相对速度不是纯线性，指数压低压速段的斜率。
        var pct = 0.35 + 0.65 * pow(min(1.0, ratio), 0.8)
        if pct < 0.30 { pct = 0.30 }
        if pct > 1.00 { pct = 1.00 }

        let rest = Double(restBpm)
        var hr = rest + pct * Double(reserve)

        // 1) 热身：前 180 秒从静息线性升到目标强度
        let warm = min(1.0, tSec / 180.0)
        hr = rest + (hr - rest) * warm

        // 2) 心率漂移：长时间运动心率上浮，约每小时 +3%
        if tSec > 600 {
            let hours = (tSec - 600) / 3600.0
            hr *= 1.0 + 0.03 * hours
        }

        // 3) 末段冲刺：最后 90 秒加 5~8 bpm，越靠近终点越明显
        let left = durSec - tSec
        if left < 90 && left > 0 {
            hr += (1.0 - left / 90.0) * 7.0
        }

        // 生理上限
        if hr > Double(maxBpm) { hr = Double(maxBpm) }
        if hr < rest * 0.85 { hr = rest * 0.85 }
        return Int(hr.rounded())
    }

    /// 估算某个配速下的稳定心率，给界面预览用（同 Java `steadyHr`）。
    public func steadyBpm(paceSecondsPerKm paceSec: Double) -> Int {
        guard paceSec > 0 else { return restBpm }
        let v = 1000.0 / paceSec
        let vMax = 1000.0 / Double(max(paceMaxSec, 1))
        let ratio = vMax > 0 ? v / vMax : 1.0
        var pct = 0.35 + 0.65 * pow(min(1.0, ratio), 0.8)
        if pct > 1.0 { pct = 1.0 }
        if pct < 0.30 { pct = 0.30 }
        return Int((Double(restBpm) + pct * Double(reserve)).rounded())
    }

    /// 界面摘要：静息 60　最高 190　最快 1km 4:00
    public var summary: String {
        "静息 \(restBpm)　最高 \(maxBpm)　最快 1km \(Format.clock(Double(paceMaxSec)))"
    }
}