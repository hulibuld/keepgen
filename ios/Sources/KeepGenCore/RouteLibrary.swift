import Foundation
import Combine

// MARK: - 路线库

public struct SavedRoute: Codable, Identifiable, Hashable, Sendable {
    public var id: UUID
    public var name: String
    public var points: [RoutePoint]
    public var createdAt: Date

    public init(id: UUID = UUID(), name: String, points: [RoutePoint], createdAt: Date = Date()) {
        self.id = id
        self.name = name
        self.points = points
        self.createdAt = createdAt
    }

    public var pointCount: Int { points.count }

    public var distanceMeters: Double { Geo.pathLength(points) }

    public var distanceKmText: String { String(format: "%.2f", distanceMeters / 1000) }
}

public final class RouteLibrary: ObservableObject {

    public static let shared = RouteLibrary()

    @Published public private(set) var routes: [SavedRoute] = []

    private let fileURL: URL

    public init(fileURL: URL? = nil) {
        self.fileURL = fileURL ?? Storage.fileURL("routes.json")
        load()
    }

    /// 保存一条路线。同名覆盖、点数不足 2 的丢弃。
    @discardableResult
    public func save(name: String, points: [RoutePoint]) -> SavedRoute? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard points.count >= 2, !trimmed.isEmpty else { return nil }
        if let index = routes.firstIndex(where: { $0.name == trimmed }) {
            routes[index].points = points
            routes[index].createdAt = Date()
            persist()
            return routes[index]
        }
        let route = SavedRoute(name: trimmed, points: points)
        routes.insert(route, at: 0)
        persist()
        return route
    }

    public func rename(id: UUID, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let index = routes.firstIndex(where: { $0.id == id }) else { return }
        routes[index].name = trimmed
        persist()
    }

    public func remove(id: UUID) {
        routes.removeAll { $0.id == id }
        persist()
    }

    public func route(id: UUID?) -> SavedRoute? {
        guard let id else { return nil }
        return routes.first { $0.id == id }
    }

    public func removeAll() {
        routes.removeAll()
        persist()
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        routes = (try? decoder.decode([SavedRoute].self, from: data)) ?? []
    }

    private func persist() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(routes) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}