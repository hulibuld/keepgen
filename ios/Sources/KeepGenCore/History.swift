import Foundation
import Combine

// MARK: - 历史记录

/// 本机记录每一条生成过的运动记录。删记录不会动「健康」里的数据。
public struct HistoryEntry: Codable, Identifiable, Hashable, Sendable {
    public var id: UUID
    public var startDate: Date
    public var kind: ActivityKind
    public var distanceMeters: Double
    public var durationSeconds: Double
    public var averageHeartRate: Int
    public var maxHeartRate: Int
    public var energyKcal: Double
    public var steps: Int
    public var wroteToHealth: Bool
    public var message: String
    public var createdAt: Date

    public init(id: UUID = UUID(),
                startDate: Date,
                kind: ActivityKind,
                distanceMeters: Double,
                durationSeconds: Double,
                averageHeartRate: Int,
                maxHeartRate: Int,
                energyKcal: Double,
                steps: Int,
                wroteToHealth: Bool,
                message: String = "",
                createdAt: Date = Date()) {
        self.id = id
        self.startDate = startDate
        self.kind = kind
        self.distanceMeters = distanceMeters
        self.durationSeconds = durationSeconds
        self.averageHeartRate = averageHeartRate
        self.maxHeartRate = maxHeartRate
        self.energyKcal = energyKcal
        self.steps = steps
        self.wroteToHealth = wroteToHealth
        self.message = message
        self.createdAt = createdAt
    }

    public var distanceKm: Double { distanceMeters / 1000 }

    public var paceSecondsPerKm: Double {
        distanceMeters > 0 ? durationSeconds / (distanceMeters / 1000) : 0
    }
}

public final class HistoryStore: ObservableObject {

    public static let shared = HistoryStore()

    /// 最新一条在最前。
    @Published public private(set) var entries: [HistoryEntry] = []

    private let fileURL: URL

    public init(fileURL: URL? = nil) {
        self.fileURL = fileURL ?? Storage.fileURL("history.json")
        load()
    }

    public var count: Int { entries.count }

    @discardableResult
    public func add(_ entry: HistoryEntry) -> HistoryEntry {
        entries.insert(entry, at: 0)
        if entries.count > 500 { entries.removeLast(entries.count - 500) }
        persist()
        return entry
    }

    public func remove(id: UUID) {
        entries.removeAll { $0.id == id }
        persist()
    }

    public func removeAll() {
        entries.removeAll()
        persist()
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        entries = (try? decoder.decode([HistoryEntry].self, from: data)) ?? []
    }

    private func persist() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(entries) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}