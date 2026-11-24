import Foundation

// MARK: - 活动类型

/// 四种活动，对齐 Android 版「运动数据生成」的活动面。
/// 室内跑步走跑步机语义（`HKMetadataKeyIndoorWorkout = true`），不带路线。
public enum ActivityKind: String, CaseIterable, Codable, Identifiable, Hashable, Sendable {
    case outdoorRun = "outdoor_run"
    case indoorRun = "indoor_run"
    case outdoorWalk = "outdoor_walk"
    case outdoorRide = "outdoor_ride"

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .outdoorRun: return "户外跑步"
        case .indoorRun: return "室内跑步"
        case .outdoorWalk: return "户外行走"
        case .outdoorRide: return "户外骑行"
        }
    }

    public var subtitle: String {
        switch self {
        case .outdoorRun: return "室外 · 可带路线"
        case .indoorRun: return "跑步机 · 无路线"
        case .outdoorWalk: return "室外 · 可带路线"
        case .outdoorRide: return "室外 · 可带路线"
        }
    }

    /// SF Symbol 名。iOS 16 起均可直接取到。
    public var symbol: String {
        switch self {
        case .outdoorRun: return "figure.run"
        case .indoorRun: return "figure.run.treadmill"
        case .outdoorWalk: return "figure.walk"
        case .outdoorRide: return "figure.outdoor.cycle"
        }
    }

    public var isIndoor: Bool { self == .indoorRun }

    /// 室内跑步不写路线。
    public var supportsRoute: Bool { !isIndoor }

    /// 骑行不写步数（对应 Java `Sport.cadence`）。
    public var countsSteps: Bool { self != .outdoorRide }

    // MARK: Sport 表（对齐 Generator.java 第 26–30 行）

    /// 对应 Java 的 sport key：run / tread / walk / bike。
    public var sportKey: String {
        switch self {
        case .outdoorRun: return "run"
        case .indoorRun: return "tread"
        case .outdoorWalk: return "walk"
        case .outdoorRide: return "bike"
        }
    }

    /// 配速上限（最快），秒/公里。
    public var paceCapSeconds: Double {
        switch self {
        case .outdoorRun, .indoorRun: return 240   // 4:00
        case .outdoorWalk: return 300              // 5:00
        case .outdoorRide: return 60               // 1:00
        }
    }

    /// 配速下限（最慢），秒/公里。
    public var paceFloorSeconds: Double {
        switch self {
        case .outdoorRun, .indoorRun: return 1800  // 30:00
        case .outdoorWalk: return 3600             // 60:00
        case .outdoorRide: return 1200             // 20:00
        }
    }

    /// 每公斤每公里卡路里系数（Java `Sport.calPerKm`）。
    public var caloriesPerKm: Double {
        switch self {
        case .outdoorRun, .indoorRun: return 1.036
        case .outdoorWalk: return 0.85
        case .outdoorRide: return 0.5
        }
    }

    /// 骑行显示时速，其余显示配速。
    public var showsPace: Bool { self != .outdoorRide }
}

// MARK: - 单位

public enum Units: String, CaseIterable, Codable, Identifiable, Hashable, Sendable {
    case metric
    case imperial

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .metric: return "公里"
        case .imperial: return "英里"
        }
    }

    public var distanceSuffix: String {
        switch self {
        case .metric: return "km"
        case .imperial: return "mi"
        }
    }

    public var distanceFactor: Double {
        switch self {
        case .metric: return 1.0
        case .imperial: return 0.621371
        }
    }
}

// MARK: - 地理小工具

public enum Geo {
    private static let earthRadius = 6_371_000.0

    /// Haversine，单位米。
    public static func distanceMeters(from a: RoutePoint, to b: RoutePoint) -> Double {
        let lat1 = a.latitude * .pi / 180
        let lat2 = b.latitude * .pi / 180
        let dLat = lat2 - lat1
        let dLng = (b.longitude - a.longitude) * .pi / 180
        let h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * earthRadius * asin(min(1, sqrt(h)))
    }

    public static func pathLength(_ points: [RoutePoint]) -> Double {
        guard points.count > 1 else { return 0 }
        var total = 0.0
        for i in 1..<points.count {
            total += distanceMeters(from: points[i - 1], to: points[i])
        }
        return total
    }
}

// MARK: - 路线点

public struct RoutePoint: Codable, Hashable, Identifiable, Sendable {
    public var id: UUID
    public var latitude: Double
    public var longitude: Double

    public init(id: UUID = UUID(), latitude: Double, longitude: Double) {
        self.id = id
        self.latitude = latitude
        self.longitude = longitude
    }
}

// MARK: - 路线来源

public enum RouteMode: String, CaseIterable, Codable, Identifiable, Hashable, Sendable {
    case track
    case paste
    case library
    case none

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .track: return "田径场"
        case .paste: return "粘贴坐标"
        case .library: return "路线库"
        case .none: return "不带路线"
        }
    }
}

/// 田径场参数：跑道长度、中心坐标、方位角。
public struct TrackConfig: Codable, Hashable, Sendable {
    public var laneLengthMeters: Double
    public var centerLatitude: Double
    public var centerLongitude: Double
    public var headingDegrees: Double

    public init(laneLengthMeters: Double = 400,
                centerLatitude: Double = 30.2687,
                centerLongitude: Double = 120.1551,
                headingDegrees: Double = 0) {
        self.laneLengthMeters = laneLengthMeters
        self.centerLatitude = centerLatitude
        self.centerLongitude = centerLongitude
        self.headingDegrees = headingDegrees
    }
}

// MARK: - 单条生成参数

public struct RunPlan: Codable, Hashable, Sendable {
    public var kind: ActivityKind
    public var startDate: Date
    public var durationMinutes: Double
    public var distanceKm: Double
    /// Karvonen 心率参数：静息 / 最高 / 最快 1 公里配速（秒）。
    public var hrProfile: HrProfile
    public var cadence: Int
    public var weightKg: Double
    public var routeMode: RouteMode
    public var track: TrackConfig
    /// 粘贴模式下用户输入的原始文本，保留给界面回显。
    public var pastedCoordinates: String
    /// 已解析（粘贴）或已选定（路线库）的坐标。田径场模式忽略它。
    public var routePoints: [RoutePoint]
    /// 轨迹随机种子：同参数换 seed 得到不同环线。
    public var seed: Int

    public init(kind: ActivityKind = .outdoorRun,
                startDate: Date = Date(),
                durationMinutes: Double = 35,
                distanceKm: Double = 6,
                hrProfile: HrProfile = HrProfile(),
                cadence: Int = 172,
                weightKg: Double = 62,
                routeMode: RouteMode = .track,
                track: TrackConfig = TrackConfig(),
                pastedCoordinates: String = "",
                routePoints: [RoutePoint] = [],
                seed: Int = 7) {
        self.kind = kind
        self.startDate = startDate
        self.durationMinutes = durationMinutes
        self.distanceKm = distanceKm
        self.hrProfile = hrProfile
        self.cadence = cadence
        self.weightKg = weightKg
        self.routeMode = routeMode
        self.track = track
        self.pastedCoordinates = pastedCoordinates
        self.routePoints = routePoints
        self.seed = seed
    }

    public var durationSeconds: Double { max(durationMinutes, 0.1) * 60 }
    public var distanceMeters: Double { max(distanceKm, 0) * 1000 }

    /// 秒 / 公里。距离为 0 时返回 0。
    public var averagePaceSecondsPerKm: Double {
        distanceKm > 0 ? durationSeconds / distanceKm : 0
    }

    public var speedKmh: Double {
        durationSeconds > 0 ? distanceKm / (durationSeconds / 3600) : 0
    }

    public var endDate: Date { startDate.addingTimeInterval(durationSeconds) }
}

// MARK: - 生成结果

public struct HeartRateSample: Codable, Hashable, Sendable {
    public var date: Date
    public var bpm: Int

    public init(date: Date, bpm: Int) {
        self.date = date
        self.bpm = bpm
    }
}

public struct RouteSample: Codable, Hashable, Sendable {
    public var date: Date
    public var latitude: Double
    public var longitude: Double
    public var altitude: Double
    /// 米 / 秒
    public var speed: Double

    public init(date: Date, latitude: Double, longitude: Double, altitude: Double, speed: Double) {
        self.date = date
        self.latitude = latitude
        self.longitude = longitude
        self.altitude = altitude
        self.speed = speed
    }
}

public struct KilometreSplit: Codable, Hashable, Identifiable, Sendable {
    public var index: Int
    public var distanceMeters: Double
    public var durationSeconds: Double

    public var id: Int { index }

    public init(index: Int, distanceMeters: Double, durationSeconds: Double) {
        self.index = index
        self.distanceMeters = distanceMeters
        self.durationSeconds = durationSeconds
    }

    public var paceSecondsPerKm: Double {
        distanceMeters > 0 ? durationSeconds / (distanceMeters / 1000) : 0
    }
}

public struct RunResult: Codable, Hashable, Sendable {
    public var kind: ActivityKind
    public var startDate: Date
    public var durationSeconds: Double
    public var distanceMeters: Double
    public var averageHeartRate: Int
    public var maxHeartRate: Int
    public var cadence: Int
    public var weightKg: Double
    public var activeEnergyKcal: Double
    public var steps: Int
    public var heartRates: [HeartRateSample]
    public var route: [RouteSample]
    public var splits: [KilometreSplit]

    public init(kind: ActivityKind,
                startDate: Date,
                durationSeconds: Double,
                distanceMeters: Double,
                averageHeartRate: Int,
                maxHeartRate: Int,
                cadence: Int,
                weightKg: Double,
                activeEnergyKcal: Double,
                steps: Int,
                heartRates: [HeartRateSample],
                route: [RouteSample],
                splits: [KilometreSplit]) {
        self.kind = kind
        self.startDate = startDate
        self.durationSeconds = durationSeconds
        self.distanceMeters = distanceMeters
        self.averageHeartRate = averageHeartRate
        self.maxHeartRate = maxHeartRate
        self.cadence = cadence
        self.weightKg = weightKg
        self.activeEnergyKcal = activeEnergyKcal
        self.steps = steps
        self.heartRates = heartRates
        self.route = route
        self.splits = splits
    }

    public var endDate: Date { startDate.addingTimeInterval(durationSeconds) }
    public var distanceKm: Double { distanceMeters / 1000 }
    public var averagePaceSecondsPerKm: Double {
        distanceMeters > 0 ? durationSeconds / (distanceMeters / 1000) : 0
    }
    public var speedKmh: Double {
        durationSeconds > 0 ? (distanceMeters / 1000) / (durationSeconds / 3600) : 0
    }
    public var hasRoute: Bool { !route.isEmpty }
}

// MARK: - 批量生成

public struct BatchPlan: Codable, Hashable, Sendable {
    public var startDay: Date
    public var endDay: Date
    public var runsPerDay: Int
    public var windowStartHour: Int
    public var windowEndHour: Int
    /// 各活动权重，取值 0…1，调用侧会归一化。
    public var weights: [ActivityKind: Double]
    public var template: RunPlan

    public init(startDay: Date = Date(),
                endDay: Date = Date(),
                runsPerDay: Int = 2,
                windowStartHour: Int = 7,
                windowEndHour: Int = 21,
                weights: [ActivityKind: Double] = [
                    .outdoorRun: 0.4, .indoorRun: 0.25, .outdoorWalk: 0.2, .outdoorRide: 0.15
                ],
                template: RunPlan = RunPlan()) {
        self.startDay = startDay
        self.endDay = endDay
        self.runsPerDay = runsPerDay
        self.windowStartHour = windowStartHour
        self.windowEndHour = windowEndHour
        self.weights = weights
        self.template = template
    }

    public func days(calendar: Calendar = .current) -> [Date] {
        let start = calendar.startOfDay(for: startDay)
        let end = calendar.startOfDay(for: endDay)
        guard end >= start else { return [start] }
        var result: [Date] = []
        var cursor = start
        var guardCount = 0
        while cursor <= end && guardCount < 366 {
            result.append(cursor)
            guard let next = calendar.date(byAdding: .day, value: 1, to: cursor) else { break }
            cursor = next
            guardCount += 1
        }
        return result
    }

    public var totalRuns: Int {
        days().count * max(runsPerDay, 0)
    }

    /// 归一化后的权重表；全为 0 时退回等权。
    public var normalizedWeights: [(ActivityKind, Double)] {
        let pairs = ActivityKind.allCases.map { ($0, max(weights[$0] ?? 0, 0)) }
        let sum = pairs.reduce(0) { $0 + $1.1 }
        guard sum > 0 else {
            let even = 1.0 / Double(ActivityKind.allCases.count)
            return ActivityKind.allCases.map { ($0, even) }
        }
        return pairs.map { ($0.0, $0.1 / sum) }
    }
}

// MARK: - 数值格式化

public enum Format {
    /// 5'42" / 4:05 / --:--
    public static func pace(_ secondsPerKm: Double) -> String {
        guard secondsPerKm.isFinite, secondsPerKm > 0 else { return "--'--\"" }
        let total = Int(secondsPerKm.rounded())
        let m = total / 60
        let s = total % 60
        return String(format: "%d'%02d\"", m, s)
    }

    /// 与 Java `Generator.clock` 一致：4:00 / 1:02:03。
    public static func clock(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds.rounded())
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    public static func duration(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds.rounded())
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, s) }
        return String(format: "%d:%02d", m, s)
    }

    public static func distance(_ meters: Double, units: Units) -> String {
        let km = meters / 1000 * units.distanceFactor
        return String(format: "%.2f", km)
    }

    public static func speed(_ kmh: Double, units: Units) -> String {
        units == .metric ? String(format: "%.1f", kmh) : String(format: "%.1f", kmh * 0.621371)
    }

    public static func dateTime(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm"
        return f.string(from: date)
    }

    public static func day(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }

    public static func clock(_ hour: Int) -> String {
        String(format: "%02d:00", hour)
    }
}