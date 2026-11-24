import Foundation

// MARK: - 校验错误

/// 参数校验失败，文案对齐 Android 版 `Generator.generate` 里的 IllegalArgumentException。
public enum GenerationError: LocalizedError, Equatable {
    case distanceNotPositive
    case distanceTooLong
    case durationTooLong
    case paceTooFast(label: String, cap: Double, actual: Double)
    case paceTooSlow(label: String, floor: Double)
    case paceNotEnoughRoom

    public var errorDescription: String? {
        switch self {
        case .distanceNotPositive:
            return "距离必须大于 0"
        case .distanceTooLong:
            return "距离超过 50 公里"
        case .durationTooLong:
            return "时长超过 4 小时"
        case let .paceTooFast(label, cap, actual):
            return "\(label)最快只能 \(Format.clock(cap)) 一公里，现在是 \(Format.clock(actual))"
        case let .paceTooSlow(label, floor):
            return "\(label)最慢只能 \(Format.clock(floor)) 一公里"
        case .paceNotEnoughRoom:
            return "这个配速没有余量，请放慢一点"
        }
    }
}

// MARK: - 生成器

/// 轨迹 / 心率 / 分段 的生成逻辑。
///
/// 心率不再用固定公式拟合，而是按 Android 版 `HrProfile`（Karvonen 心率储备法）逐点算：
/// 瞬时速度 → %HRR → HR，再叠热身、漂移、末段冲刺。参数见 `HrProfile`。
public enum Generator {

    /// Java `MAX_TIME`：4 小时。
    public static let maxTimeSeconds: Double = 4 * 3600
    /// Java `MAX_DIST_M`：50 km。
    public static let maxDistanceMeters: Double = 50_000
    /// 心率采样间隔：10 秒。
    public static let heartRateInterval: Double = 10

    // MARK: 校验

    /// 与 Java `Generator.generate` 相同的顺序和口径：
    /// 距离 > 0 → ≤ 50 km → 时长 ≤ 4 h → 配速在 [paceCap, paceFloor] 内。
    public static func validate(_ plan: RunPlan) throws {
        let distance = plan.distanceMeters
        guard distance > 0 else { throw GenerationError.distanceNotPositive }
        guard distance <= maxDistanceMeters else { throw GenerationError.distanceTooLong }

        let duration = plan.durationSeconds
        guard duration <= maxTimeSeconds else { throw GenerationError.durationTooLong }

        let pace = duration / plan.distanceKm
        let kind = plan.kind
        if pace < kind.paceCapSeconds {
            throw GenerationError.paceTooFast(label: kind.title, cap: kind.paceCapSeconds, actual: pace)
        }
        if pace > kind.paceFloorSeconds {
            throw GenerationError.paceTooSlow(label: kind.title, floor: kind.paceFloorSeconds)
        }
    }

    /// 秒 / 公里。距离为 0 时返回 0。
    public static func paceSecondsPerKm(_ plan: RunPlan) -> Double {
        plan.distanceKm > 0 ? plan.durationSeconds / plan.distanceKm : 0
    }

    // MARK: 主流程

    /// 把一份 `RunPlan` 展开成一条完整的运动记录。
    public static func generate(_ plan: RunPlan) throws -> RunResult {
        try validate(plan)

        let distance = plan.distanceMeters
        let duration = plan.durationSeconds
        let start = plan.startDate
        let pace = paceSecondsPerKm(plan)
        // 配速恒定（跑步机就是匀速；户外也按平均配速走，速度细节交给分段与轨迹）。
        let speed = pace > 0 ? 1000.0 / pace : 0
        let profile = plan.hrProfile.sanitized()

        let points = resolvedPoints(for: plan, distanceMeters: distance)
        let route = routeSamples(from: points, start: start, duration: duration)

        // 心率：每 10 秒一个点，用逐点瞬时速度走 Karvonen。
        var times: [Double] = []
        var t = 0.0
        while t < duration - 0.001 {
            times.append(t)
            t += heartRateInterval
        }
        times.append(duration)

        let beats = times.map { profile.hrAt(speedMetresPerSecond: speed, elapsed: $0, duration: duration) }
        let heartRates = zip(times, beats).map { HeartRateSample(date: start.addingTimeInterval($0), bpm: $1) }

        let splits = kilometreSplits(durationSeconds: duration, distanceMeters: distance)
        let energy = activeEnergyKcal(kind: plan.kind, weightKg: plan.weightKg, distanceMeters: distance)
        let steps = plan.kind.countsSteps ? Int((Double(plan.cadence) * plan.durationMinutes).rounded()) : 0

        let avg = heartRates.isEmpty ? profile.restBpm
            : Int((Double(heartRates.reduce(0) { $0 + $1.bpm }) / Double(heartRates.count)).rounded())
        let peak = heartRates.map(\.bpm).max() ?? profile.maxBpm

        return RunResult(kind: plan.kind,
                         startDate: start,
                         durationSeconds: duration,
                         distanceMeters: distance,
                         averageHeartRate: avg,
                         maxHeartRate: max(peak, avg),
                         cadence: plan.kind.countsSteps ? plan.cadence : 0,
                         weightKg: plan.weightKg,
                         activeEnergyKcal: energy,
                         steps: steps,
                         heartRates: heartRates,
                         route: route,
                         splits: splits)
    }

    // MARK: 轨迹

    public static func resolvedPoints(for plan: RunPlan, distanceMeters: Double) -> [RoutePoint] {
        guard plan.kind.supportsRoute, plan.routeMode != .none, distanceMeters > 0 else { return [] }
        switch plan.routeMode {
        case .track:
            return trackPoints(config: plan.track, distanceMeters: distanceMeters, seed: plan.seed)
        case .paste:
            return plan.routePoints.isEmpty ? parseCoordinates(plan.pastedCoordinates) : plan.routePoints
        case .library:
            return plan.routePoints
        case .none:
            return []
        }
    }

    /// 田径场：移植 Android `Track.java` 的胶囊跑道 + 换道漂移 + 轻微摆动。
    /// 位置按「已跑距离」参数化，5 m 一点；整圈跑完回到起点，总长与输入距离误差 <1%。
    public static func trackPoints(config: TrackConfig, distanceMeters: Double, seed: Int = 7) -> [RoutePoint] {
        TrackShape(config: config, distanceMeters: distanceMeters, seed: seed)
            .points(distanceMeters: distanceMeters, spacing: 5)
    }

    /// 解析「一行一个 lat,lng」。容忍空格、分号、制表符；非法行跳过。
    public static func parseCoordinates(_ raw: String) -> [RoutePoint] {
        var points: [RoutePoint] = []
        for line in raw.split(whereSeparator: \.isNewline) {
            let cleaned = line.replacingOccurrences(of: ";", with: ",")
                .replacingOccurrences(of: "\t", with: ",")
                .replacingOccurrences(of: " ", with: "")
            let parts = cleaned.split(separator: ",")
            guard parts.count >= 2,
                  let lat = Double(parts[0]),
                  let lng = Double(parts[1]),
                  (-90...90).contains(lat),
                  (-180...180).contains(lng) else { continue }
            points.append(RoutePoint(latitude: lat, longitude: lng))
        }
        return points
    }

    /// 把坐标点按「累计距离占比」摊到 duration 上，得到 CLLocation 需要的序列。
    public static func routeSamples(from points: [RoutePoint], start: Date, duration: Double) -> [RouteSample] {
        guard points.count >= 2 else { return [] }
        var cumulative: [Double] = [0]
        cumulative.reserveCapacity(points.count)
        for i in 1..<points.count {
            cumulative.append(cumulative[i - 1] + Geo.distanceMeters(from: points[i - 1], to: points[i]))
        }
        let total = cumulative.last ?? 0
        guard total > 0 else { return [] }
        let speed = duration > 0 ? total / duration : 0

        return points.enumerated().map { index, point in
            let ratio = cumulative[index] / total
            let altitude = 6.0 + 2.4 * sin(ratio * 2 * .pi * 1.5 + 0.4)
            return RouteSample(date: start.addingTimeInterval(duration * ratio),
                               latitude: point.latitude,
                               longitude: point.longitude,
                               altitude: altitude,
                               speed: speed)
        }
    }

    // MARK: 每公里分段

    /// 每公里配速带轻微起伏：相邻公里不重复，但总时长严格等于输入时长。
    public static func kilometreSplits(durationSeconds: Double, distanceMeters: Double) -> [KilometreSplit] {
        guard durationSeconds > 0, distanceMeters > 0 else { return [] }
        let km = distanceMeters / 1000
        let full = Int(floor(km))
        let remainder = km - Double(full)

        var nominal: [Double] = Array(repeating: 1.0, count: full)
        if remainder > 0.02 { nominal.append(remainder) }
        guard !nominal.isEmpty else { return [] }

        let ph1 = Double.random(in: 0..<(2 * .pi))
        let ph2 = Double.random(in: 0..<(2 * .pi))
        let weighted = nominal.enumerated().map { index, span -> Double in
            let drift = 1 + 0.034 * sin(Double(index) * 1.7 + ph1) + 0.021 * cos(Double(index) * 0.9 + ph2 * 1.3)
            return span * drift
        }
        let weightSum = weighted.reduce(0, +)
        guard weightSum > 0 else { return [] }

        return weighted.enumerated().map { index, weight -> KilometreSplit in
            let seconds = durationSeconds * weight / weightSum
            return KilometreSplit(index: index + 1, distanceMeters: nominal[index] * 1000, durationSeconds: seconds)
        }
    }

    // MARK: 能量与步数

    /// 卡路里 = round(体重 kg × 公里 × 该运动的每公里系数)，与 Java 一致。
    public static func activeEnergyKcal(kind: ActivityKind, weightKg: Double, distanceMeters: Double) -> Double {
        let km = distanceMeters / 1000
        let raw = max(weightKg, 0) * km * kind.caloriesPerKm
        return Double(Int(raw.rounded()))
    }
}