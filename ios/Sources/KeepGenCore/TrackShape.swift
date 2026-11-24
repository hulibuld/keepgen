import Foundation

// MARK: - 可复现的随机数（移植 Java Plan.Rng）

/// 与 Android 版 `Plan.Rng` 同算法的确定性随机数，保证同 seed 同结果。
public struct JavaRng: RandomNumberGenerator {
    private var t: UInt32

    public init(seed: Int) {
        self.t = UInt32(truncatingIfNeeded: seed)
    }

    public init(seed32: Int32) {
        self.t = UInt32(bitPattern: seed32)
    }

    /// 与 Java 的 `next()` 一致，返回 [0,1) 的 Double。
    public mutating func nextDouble() -> Double {
        t = t &+ 0x6D2B79F5
        var x = t
        x = (x ^ (x >> 15)) &* (x | 1)
        x ^= x &+ ((x ^ (x >> 7)) &* (x | 61))
        return Double((x ^ (x >> 14)) & 0xFFFF_FFFF) / 4_294_967_296.0
    }

    /// RandomNumberGenerator 要求（工程内部不直接用，留着方便接 Swift RNG 接口）。
    public mutating func next() -> UInt64 {
        UInt64(nextDouble() * 4_294_967_296.0)
    }
}

// MARK: - 跑道几何（移植 Track.java）

/// 400 m 标准跑道建模成「胶囊」：两条直道 + 两个半圆（`Field.std400` 的 84.39 m / 36.80 m 比例，
/// 按用户给的圈长等比缩放）。
///
/// 真人不贴第一道跑进线走：换道、超车、切内角。这里用「随机路点 + smoothstep 插值」建模横向位置，
/// 再叠一层整条轨迹的缓慢横向漂移和一点细节摆动。横向偏移只改形状，不改按距离参数化的速度。
///
/// 与 Java 版的两处差异，为了同时满足「整圈闭合」和「总长误差 <1%」：
/// 1. 横向摆动按**一圈周期**构造（路点首尾相接、细节摆动取圈长整数倍谐波），
///    整条轨迹的漂移项用 `sin(2π·u/span)` 且两端为 0 —— Java 版不保证闭合。
/// 2. 胶囊几何按「带横向偏移的一圈长度 = 用户给的圈长」做数值标定（`geometryScale`）。
///    Java 用弧长表把走过的距离映射到曲线上，这里改成把几何缩一点点（约 1–2%），
///    这样按 s 等距取点时，实际走的路长就等于输入距离。
public struct TrackShape: Sendable {

    // 与 Track.java 相同的常量
    public static let gapMin = 80.0, gapMax = 122.0
    public static let stepMin = 3.0, stepMax = 5.0
    public static let bound = 6.2
    public static let detailAmp = 0.22
    public static let curveBlend = 18.0
    public static let bendK = 0.35
    /// 整条轨迹的横向漂移幅度（米）
    public static let driftAmp = 2.0

    // MARK: 内部几何布局

    /// 固定路点 + 给定胶囊尺寸时的位置函数。
    private struct Layout {
        let straight: Double
        let radius: Double
        let curve: Double
        let lap: Double
        let waypointU: [Double]
        let waypointY: [Double]
        let harmonicA: Double
        let harmonicB: Double
        let ph1: Double
        let ph2: Double
        let amp: Double

        func phase(_ s: Double) -> Double {
            var p = s.truncatingRemainder(dividingBy: lap)
            if p < 0 { p += lap }
            return p
        }

        func centerline(_ s: Double) -> (x: Double, y: Double) {
            TrackShape.centerline(s, straight: straight, radius: radius, curveLength: curve, lapLength: lap)
        }

        func bend(_ s: Double) -> Double {
            TrackShape.bendFactor(s, straight: straight, curveLength: curve, lapLength: lap)
        }

        func detail(_ s: Double) -> Double {
            let p = phase(s)
            let a = 2 * .pi * harmonicA * p / lap + ph1
            let b = 2 * .pi * harmonicB * p / lap + ph2
            return TrackShape.detailAmp * (0.7 * sin(a) + 0.3 * sin(b))
        }

        func waypointSway(_ s: Double) -> Double {
            let u = phase(s)
            let n = waypointU.count
            if n == 0 { return 0 }
            if u <= waypointU[0] { return waypointY[0] }
            if u >= waypointU[n - 1] { return waypointY[n - 1] }
            var lo = 0
            var hi = n - 1
            while hi - lo > 1 {
                let mid = (lo + hi) / 2
                if waypointU[mid] <= u { lo = mid } else { hi = mid }
            }
            let segment = waypointU[hi] - waypointU[lo]
            let t = TrackShape.smoothstep(segment > 1e-9 ? (u - waypointU[lo]) / segment : 0)
            return waypointY[lo] + (waypointY[hi] - waypointY[lo]) * t
        }

        func lateral(_ s: Double) -> Double {
            let raw = (waypointSway(s) + detail(s)) * amp
            let bendK = bend(s)
            if bendK <= 0 { return raw }
            let inward = min(0, raw) * TrackShape.bendK
            return raw * (1 - bendK) + inward * bendK
        }

        func position(_ s: Double) -> (x: Double, y: Double) {
            let lo = centerline(s - 0.5)
            let hi = centerline(s + 0.5)
            var tx = hi.x - lo.x
            var ty = hi.y - lo.y
            let norm = (tx * tx + ty * ty).squareRoot()
            if norm > 1e-9 { tx /= norm; ty /= norm } else { tx = 1; ty = 0 }
            let cur = centerline(s)
            let offset = lateral(s)
            return (cur.x + (-ty) * offset, cur.y + tx * offset)
        }

        /// 带横向偏移的一圈折线长度（用于把几何缩到「圈长」）。
        func loopLength(step: Double) -> Double {
            var total = 0.0
            var prev = position(0)
            var cursor = step
            while cursor <= lap + 1e-9 {
                let p = position(cursor)
                let dx = p.x - prev.x
                let dy = p.y - prev.y
                total += (dx * dx + dy * dy).squareRoot()
                prev = p
                cursor += step
            }
            return total
        }
    }

    // MARK: 对外属性

    public let straight: Double
    public let radius: Double
    public let lapLength: Double
    public let curveLength: Double
    /// 摆动幅度标定系数（1.0 = 原 Java 幅度）
    public let amplitudeScale: Double

    private let bearing: Double
    private let centerLat: Double
    private let centerLng: Double
    private let metresPerDegreeLat: Double
    private let metresPerDegreeLng: Double
    private let span: Double
    private let layout: Layout

    // MARK: 静态几何

    /// 胶囊中心线上「距起点 s 米」处的平面坐标（长轴 x、横向 y），按圈长取模。
    public static func centerline(_ s: Double,
                                  straight: Double,
                                  radius: Double,
                                  curveLength: Double,
                                  lapLength: Double) -> (x: Double, y: Double) {
        var t = s.truncatingRemainder(dividingBy: lapLength)
        if t < 0 { t += lapLength }
        if t < straight { return (-straight / 2 + t, radius) }
        t -= straight
        if t < curveLength {
            let theta = t / radius
            return (straight / 2 + radius * sin(theta), radius * cos(theta))
        }
        t -= curveLength
        if t < straight { return (straight / 2 - t, -radius) }
        t -= straight
        let theta2 = t / radius
        return (-straight / 2 - radius * sin(theta2), -radius * cos(theta2))
    }

    static func smoothstep(_ t: Double) -> Double { t * t * (3 - 2 * t) }

    /// 弯道比例：弯道 1、直道 0（出入口各留 18 m 平滑）。
    public static func bendFactor(_ s: Double,
                                  straight: Double,
                                  curveLength: Double,
                                  lapLength: Double) -> Double {
        var t = s.truncatingRemainder(dividingBy: lapLength)
        if t < 0 { t += lapLength }
        if t < straight { return 0 }
        let a = t - straight
        if a < curveLength {
            let edge = max(1e-6, min(curveBlend, curveLength / 2))
            return smoothstep(min(1, min(a, curveLength - a) / edge))
        }
        return 0
    }

    // MARK: 初始化

    public init(config: TrackConfig, distanceMeters: Double, seed: Int) {
        let lane = max(config.laneLengthMeters, 50)
        let scale = lane / 400.0
        let nominalStraight = 84.39 * scale
        let nominalRadius = 36.80 * scale
        let lap = 2 * nominalStraight + 2 * .pi * nominalRadius
        let totalSpan = max(distanceMeters, lap)

        // 路点：沿「已跑距离」方向每 80–122 m 一个，横向 ±6.2 m 内随机换道
        var rng = JavaRng(seed32: Int32(truncatingIfNeeded: seed) ^ Int32(0x51F3A7))
        var us: [Double] = [0]
        var ys: [Double] = [(rng.nextDouble() * 2 - 1) * 2.0]
        var u = 0.0
        var y = ys[0]
        var dir = rng.nextDouble() < 0.5 ? -1.0 : 1.0
        var guardCount = 0
        while u < lap && guardCount < 4_000 {
            guardCount += 1
            let gap = Self.gapMin + rng.nextDouble() * (Self.gapMax - Self.gapMin)
            if rng.nextDouble() < 0.5 { dir = -dir }
            let len = Self.stepMin + rng.nextDouble() * (Self.stepMax - Self.stepMin)
            var ny = y + dir * len
            if abs(ny) > Self.bound {
                dir = -dir
                ny = y + dir * len
            }
            u += gap
            if u >= lap { break }
            y = ny
            us.append(u)
            ys.append(y)
        }
        // 收尾段必须够长：最后一段若太短，横向要在几米内从 ±6 m 拉回起点，
        // 斜率会大到把轨迹长度撑出 1% 以上（也难看）。
        while us.count > 2, lap - us[us.count - 1] < Self.gapMin / 2 {
            us.removeLast()
            ys.removeLast()
        }
        // 首尾相接：最后一个路点落在圈末，横向值取回起点值 → 每圈摆动严格周期
        us.append(lap)
        ys.append(ys[0])

        var r2 = JavaRng(seed32: Int32(truncatingIfNeeded: seed) ^ Int32(0x2F91))
        let phase1 = r2.nextDouble() * .pi * 2
        let phase2 = r2.nextDouble() * .pi * 2
        let harmonic1 = max(1, (lap / 29.0).rounded())
        let harmonic2 = max(1, (lap / 11.0).rounded())

        func makeLayout(_ amp: Double) -> Layout {
            Layout(straight: nominalStraight,
                   radius: nominalRadius,
                   curve: .pi * nominalRadius,
                   lap: lap,
                   waypointU: us,
                   waypointY: ys,
                   harmonicA: harmonic1,
                   harmonicB: harmonic2,
                   ph1: phase1,
                   ph2: phase2,
                   amp: amp)
        }

        // 幅度标定：横向摆动会把实际路径拉长（约 0.7%）。目标是把一圈的折线长
        // 控制在圈长的 1.006 倍以内——既能保住换道的形状，又让总长误差 <1%。
        let target = lap * 1.006
        var amp = 1.0
        if makeLayout(1.0).loopLength(step: 2.0) > target {
            var low = 0.10
            var high = 1.0
            for _ in 0..<12 {
                let mid = (low + high) / 2
                if makeLayout(mid).loopLength(step: 2.0) > target { high = mid } else { low = mid }
            }
            amp = (low + high) / 2
        }
        let finalLayout = makeLayout(amp)

        self.straight = finalLayout.straight
        self.radius = finalLayout.radius
        self.lapLength = lap
        self.curveLength = finalLayout.curve
        self.amplitudeScale = amp
        self.bearing = config.headingDegrees * .pi / 180
        self.centerLat = config.centerLatitude
        self.centerLng = config.centerLongitude
        self.metresPerDegreeLat = 111_194.93   // 与 Geo（R=6371 km）一致
        self.metresPerDegreeLng = max(111_194.93 * cos(config.centerLatitude * .pi / 180), 1)
        self.span = totalSpan
        self.layout = finalLayout
    }

    // MARK: 实例侧

    public func centerline(_ s: Double) -> (x: Double, y: Double) { layout.centerline(s) }

    /// 横向偏移（米）：路点换道 + 细节摆动，弯道压缩向外那一半。
    public func sway(at s: Double) -> Double { layout.lateral(s) }

    /// 整条轨迹的缓慢换道漂移：两端为 0，整圈闭合不受影响。
    private func drift(at s: Double) -> Double {
        guard span > 0 else { return 0 }
        return Self.driftAmp * sin(2 * .pi * s / span)
    }

    /// 诊断用：一圈的折线长度。
    public func loopPolylineLength(step: Double = 1.0) -> Double {
        layout.loopLength(step: step)
    }

    /// 已跑 s 米处的经纬度。
    public func point(atDistance s: Double) -> RoutePoint {
        let pos = layout.position(s)
        let offset = drift(at: s)
        let lo = centerline(s - 0.5)
        let hi = centerline(s + 0.5)
        var tx = hi.x - lo.x
        var ty = hi.y - lo.y
        let norm = (tx * tx + ty * ty).squareRoot()
        if norm > 1e-9 { tx /= norm; ty /= norm } else { tx = 1; ty = 0 }
        let a = pos.x + (-ty) * offset
        let b = pos.y + tx * offset

        let ue = sin(bearing), un = cos(bearing)
        let we = -un, wn = ue
        let east = a * ue + b * we
        let north = a * un + b * wn

        return RoutePoint(latitude: centerLat + north / metresPerDegreeLat,
                          longitude: centerLng + east / metresPerDegreeLng)
    }

    /// 按路程等距取点。末点一定落在整段距离上（整圈时与起点重合）。
    public func points(distanceMeters: Double, spacing: Double = 5) -> [RoutePoint] {
        let total = max(distanceMeters, 1)
        let step = max(spacing, 1)
        var result: [RoutePoint] = []
        result.reserveCapacity(min(Int(total / step) + 2, 12_000))
        var s = 0.0
        while s < total - 1e-9 {
            result.append(point(atDistance: s))
            s += step
            if result.count >= 11_999 { break }
        }
        result.append(point(atDistance: total))
        return result
    }
}