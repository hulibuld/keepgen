import Foundation
import Combine

// MARK: - 本机存储位置

public enum Storage {
    /// iOS 落在 App 沙盒的 Application Support 下；macOS 落在 ~/Library/Application Support。
    public static func directory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = base.appendingPathComponent("KeepGen", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    public static func fileURL(_ name: String) -> URL {
        directory().appendingPathComponent(name)
    }
}

// MARK: - 设置

/// 设置项统一落 `UserDefaults`；界面用 `@ObservedObject` 观察。
public final class AppSettings: ObservableObject {

    public static let shared = AppSettings()

    private enum Key {
        static let weight = "keepgen.settings.weight"
        static let pace = "keepgen.settings.pace"
        static let heartRate = "keepgen.settings.writeHeartRate"
        static let steps = "keepgen.settings.writeSteps"
        static let route = "keepgen.settings.writeRoute"
        static let units = "keepgen.settings.units"
        static let cadence = "keepgen.settings.cadence"
        static let duration = "keepgen.settings.duration"
        static let distance = "keepgen.settings.distance"
        static let restBpm = "keepgen.settings.restBpm"
        static let maxBpm = "keepgen.settings.maxBpm"
        static let paceMaxSec = "keepgen.settings.paceMaxSec"
    }

    private let defaults: UserDefaults

    /// 默认体重，kg。卡路里按它算。
    @Published public var defaultWeightKg: Double {
        didSet { defaults.set(defaultWeightKg, forKey: Key.weight) }
    }

    /// 默认配速，秒 / 公里。
    @Published public var defaultPaceSecondsPerKm: Double {
        didSet { defaults.set(defaultPaceSecondsPerKm, forKey: Key.pace) }
    }

    @Published public var writeHeartRate: Bool {
        didSet { defaults.set(writeHeartRate, forKey: Key.heartRate) }
    }

    @Published public var writeSteps: Bool {
        didSet { defaults.set(writeSteps, forKey: Key.steps) }
    }

    @Published public var writeRoute: Bool {
        didSet { defaults.set(writeRoute, forKey: Key.route) }
    }

    @Published public var units: Units {
        didSet { defaults.set(units.rawValue, forKey: Key.units) }
    }

    @Published public var defaultCadence: Int {
        didSet { defaults.set(defaultCadence, forKey: Key.cadence) }
    }

    @Published public var defaultDurationMinutes: Double {
        didSet { defaults.set(defaultDurationMinutes, forKey: Key.duration) }
    }

    @Published public var defaultDistanceKm: Double {
        didSet { defaults.set(defaultDistanceKm, forKey: Key.distance) }
    }

    /// Karvonen 心率模型参数：静息心率、最高心率、最快 1 公里配速（秒）。
    @Published public var restBpm: Int {
        didSet { defaults.set(restBpm, forKey: Key.restBpm) }
    }

    @Published public var maxBpm: Int {
        didSet { defaults.set(maxBpm, forKey: Key.maxBpm) }
    }

    @Published public var paceMaxSec: Int {
        didSet { defaults.set(paceMaxSec, forKey: Key.paceMaxSec) }
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.defaultWeightKg = defaults.object(forKey: Key.weight) as? Double ?? 62
        self.defaultPaceSecondsPerKm = defaults.object(forKey: Key.pace) as? Double ?? 360
        self.writeHeartRate = defaults.object(forKey: Key.heartRate) as? Bool ?? true
        self.writeSteps = defaults.object(forKey: Key.steps) as? Bool ?? true
        self.writeRoute = defaults.object(forKey: Key.route) as? Bool ?? true
        self.units = Units(rawValue: defaults.string(forKey: Key.units) ?? "") ?? .metric
        self.defaultCadence = defaults.object(forKey: Key.cadence) as? Int ?? 172
        self.defaultDurationMinutes = defaults.object(forKey: Key.duration) as? Double ?? 35
        self.defaultDistanceKm = defaults.object(forKey: Key.distance) as? Double ?? 6
        self.restBpm = defaults.object(forKey: Key.restBpm) as? Int ?? HrProfile.defaultRest
        self.maxBpm = defaults.object(forKey: Key.maxBpm) as? Int ?? HrProfile.defaultMax
        self.paceMaxSec = defaults.object(forKey: Key.paceMaxSec) as? Int ?? HrProfile.defaultPaceMaxSec
    }

    public func resetToDefaults() {
        defaultWeightKg = 62
        defaultPaceSecondsPerKm = 360
        writeHeartRate = true
        writeSteps = true
        writeRoute = true
        units = .metric
        defaultCadence = 172
        defaultDurationMinutes = 35
        defaultDistanceKm = 6
        restBpm = HrProfile.defaultRest
        maxBpm = HrProfile.defaultMax
        paceMaxSec = HrProfile.defaultPaceMaxSec
    }

    public var defaultPaceText: String { Format.pace(defaultPaceSecondsPerKm) }

    /// 当前设置对应的心率参数（已 sanitize）。
    public var hrProfile: HrProfile {
        HrProfile(restBpm: restBpm, maxBpm: maxBpm, paceMaxSec: paceMaxSec).sanitized()
    }

    /// 设置页那行摘要：静息 60　最高 190　最快 1km 4:00
    public var hrSummary: String { hrProfile.summary }
}