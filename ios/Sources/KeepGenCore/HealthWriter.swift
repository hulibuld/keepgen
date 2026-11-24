import Foundation
import CoreLocation
import HealthKit

// MARK: - 写入选项

public struct HealthWriteOptions: Hashable, Sendable {
    public var includeHeartRate: Bool
    public var includeSteps: Bool
    public var includeRoute: Bool

    public init(includeHeartRate: Bool = true, includeSteps: Bool = true, includeRoute: Bool = true) {
        self.includeHeartRate = includeHeartRate
        self.includeSteps = includeSteps
        self.includeRoute = includeRoute
    }
}

public struct HealthWriteReport: Hashable, Sendable {
    public var workoutSaved: Bool
    public var sampleCount: Int
    public var heartRateSamples: Int
    public var routeSaved: Bool
    public var routePointCount: Int
    public var warnings: [String]

    public init(workoutSaved: Bool,
                sampleCount: Int,
                heartRateSamples: Int,
                routeSaved: Bool,
                routePointCount: Int,
                warnings: [String]) {
        self.workoutSaved = workoutSaved
        self.sampleCount = sampleCount
        self.heartRateSamples = heartRateSamples
        self.routeSaved = routeSaved
        self.routePointCount = routePointCount
        self.warnings = warnings
    }
}

public enum HealthWriterError: LocalizedError {
    case unavailable
    case notAuthorized
    case authorizationFailed(String)
    case workoutFailed(String)
    case samplesFailed(String)
    case routeFailed(String)

    public var errorDescription: String? {
        switch self {
        case .unavailable:
            return "这台设备不支持 HealthKit（模拟器也可能没有健康数据源）"
        case .notAuthorized:
            return "健康权限被拒绝：去「设置 → 健康 → 数据访问」里给本 App 打开写入权限"
        case .authorizationFailed(let message):
            return "申请健康权限失败：\(message)"
        case .workoutFailed(let message):
            return "写入训练记录失败：\(message)"
        case .samplesFailed(let message):
            return "写入距离 / 能量 / 步数 / 心率样本失败：\(message)"
        case .routeFailed(let message):
            return "写入路线失败：\(message)"
        }
    }
}

// MARK: - 写 HealthKit

/// 一条记录写进「健康」的顺序（与 Android 版对齐）：
/// 1. `HKWorkout`（活动类型按四种活动映射，metadata 带 `HKMetadataKeyIndoorWorkout`）
/// 2. 距离 / 能量 / 步数 / 心率 样本，用 `addSamples(_:to:)` 挂到这条 workout 上
/// 3. 有轨迹时最后用 `HKWorkoutRouteBuilder` 收尾（`finishRoute`）
public final class HealthWriter {

    public static let shared = HealthWriter()

    private let store = HKHealthStore()

    public init() {}

    public var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    // MARK: 类型

    private var shareTypes: Set<HKSampleType> {
        var types: Set<HKSampleType> = [HKObjectType.workoutType()]
        for identifier in [HKQuantityTypeIdentifier.distanceWalkingRunning,
                           .distanceCycling,
                           .activeEnergyBurned,
                           .stepCount,
                           .heartRate] {
            if let type = HKQuantityType.quantityType(forIdentifier: identifier) {
                types.insert(type)
            }
        }
        return types
    }

    // MARK: 授权

    public func requestAuthorization() async throws {
        guard isAvailable else { throw HealthWriterError.unavailable }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            store.requestAuthorization(toShare: shareTypes, read: []) { success, error in
                if let error {
                    continuation.resume(throwing: HealthWriterError.authorizationFailed(error.localizedDescription))
                } else if !success {
                    continuation.resume(throwing: HealthWriterError.notAuthorized)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    // MARK: 活动类型映射

    public func workoutActivityType(for kind: ActivityKind) -> HKWorkoutActivityType {
        switch kind {
        case .outdoorRun, .indoorRun: return .running
        case .outdoorWalk: return .walking
        case .outdoorRide: return .cycling
        }
    }

    public func distanceTypeIdentifier(for kind: ActivityKind) -> HKQuantityTypeIdentifier {
        kind == .outdoorRide ? .distanceCycling : .distanceWalkingRunning
    }

    // MARK: 主写入

    public func write(_ result: RunResult, options: HealthWriteOptions) async throws -> HealthWriteReport {
        guard isAvailable else { throw HealthWriterError.unavailable }
        try await requestAuthorization()

        let start = result.startDate
        let end = result.endDate
        var warnings: [String] = []

        // 1) workout
        let metadata: [String: Any] = [
            HKMetadataKeyIndoorWorkout: result.kind.isIndoor,
            HKMetadataKeyExternalUUID: UUID().uuidString,
            "KeepGenActivity": result.kind.rawValue
        ]

        let workout = HKWorkout(activityType: workoutActivityType(for: result.kind),
                                start: start,
                                end: end,
                                workoutEvents: nil,
                                totalEnergyBurned: HKQuantity(unit: .kilocalorie(), doubleValue: result.activeEnergyKcal),
                                totalDistance: HKQuantity(unit: .meter(), doubleValue: result.distanceMeters),
                                metadata: metadata)
        do {
            try await save([workout])
        } catch {
            throw HealthWriterError.workoutFailed(error.localizedDescription)
        }

        // 2) 距离 / 能量 / 步数 / 心率
        var samples: [HKSample] = []

        if let type = HKQuantityType.quantityType(forIdentifier: distanceTypeIdentifier(for: result.kind)),
           result.distanceMeters > 0 {
            samples.append(HKQuantitySample(type: type,
                                            quantity: HKQuantity(unit: .meter(), doubleValue: result.distanceMeters),
                                            start: start,
                                            end: end))
        }

        if let type = HKQuantityType.quantityType(forIdentifier: .activeEnergyBurned),
           result.activeEnergyKcal > 0 {
            samples.append(HKQuantitySample(type: type,
                                            quantity: HKQuantity(unit: .kilocalorie(), doubleValue: result.activeEnergyKcal),
                                            start: start,
                                            end: end))
        }

        if options.includeSteps, result.kind.countsSteps, result.steps > 0,
           let type = HKQuantityType.quantityType(forIdentifier: .stepCount) {
            samples.append(HKQuantitySample(type: type,
                                            quantity: HKQuantity(unit: .count(), doubleValue: Double(result.steps)),
                                            start: start,
                                            end: end))
        }

        var heartRateCount = 0
        if options.includeHeartRate,
           let type = HKQuantityType.quantityType(forIdentifier: .heartRate) {
            let bpm = HKUnit.count().unitDivided(by: .minute())
            for point in result.heartRates {
                let sampleStart = max(point.date, start)
                let sampleEnd = min(sampleStart.addingTimeInterval(Generator.heartRateInterval), end)
                guard sampleEnd >= sampleStart else { continue }
                samples.append(HKQuantitySample(type: type,
                                                quantity: HKQuantity(unit: bpm, doubleValue: Double(point.bpm)),
                                                start: sampleStart,
                                                end: sampleEnd))
                heartRateCount += 1
            }
        }

        if !samples.isEmpty {
            do {
                try await addSamples(samples, to: workout)
            } catch {
                warnings.append("挂到训练记录失败，改为单独保存样本：\(error.localizedDescription)")
                do {
                    try await save(samples)
                } catch {
                    throw HealthWriterError.samplesFailed(error.localizedDescription)
                }
            }
        }

        // 3) 路线（finishRoute 放在最后）
        var routeSaved = false
        var routePointCount = 0
        if options.includeRoute, result.kind.supportsRoute, result.route.count >= 2 {
            let locations = result.route.map { sample -> CLLocation in
                CLLocation(coordinate: CLLocationCoordinate2D(latitude: sample.latitude,
                                                              longitude: sample.longitude),
                           altitude: sample.altitude,
                           horizontalAccuracy: 4,
                           verticalAccuracy: 6,
                           course: -1,
                           speed: max(sample.speed, 0),
                           timestamp: sample.date)
            }
            do {
                try await writeRoute(locations, to: workout)
                routeSaved = true
                routePointCount = locations.count
            } catch {
                warnings.append("路线写入失败：\(error.localizedDescription)")
            }
        }

        return HealthWriteReport(workoutSaved: true,
                                 sampleCount: samples.count,
                                 heartRateSamples: heartRateCount,
                                 routeSaved: routeSaved,
                                 routePointCount: routePointCount,
                                 warnings: warnings)
    }

    // MARK: HealthKit 的异步封装

    private func save(_ objects: [HKObject]) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            store.save(objects) { success, error in
                if success {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: HealthWriterError.workoutFailed(error?.localizedDescription ?? "未知错误"))
                }
            }
        }
    }

    private func addSamples(_ samples: [HKSample], to workout: HKWorkout) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            store.add(samples, to: workout) { success, error in
                if success {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: HealthWriterError.samplesFailed(error?.localizedDescription ?? "未知错误"))
                }
            }
        }
    }

    private func writeRoute(_ locations: [CLLocation], to workout: HKWorkout) async throws {
        let builder = HKWorkoutRouteBuilder(healthStore: store, device: nil)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            builder.insertRouteData(locations) { success, error in
                if success {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: HealthWriterError.routeFailed(error?.localizedDescription ?? "插入轨迹点失败"))
                }
            }
        }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            builder.finishRoute(with: workout, metadata: nil) { route, error in
                if route != nil {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: HealthWriterError.routeFailed(error?.localizedDescription ?? "收尾路线失败"))
                }
            }
        }
    }
}