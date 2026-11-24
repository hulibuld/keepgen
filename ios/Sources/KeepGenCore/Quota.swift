import Foundation
import Combine

// MARK: - 配额

public struct QuotaState: Hashable, Sendable {
    public var used: Int
    public var limit: Int
    public var dayKey: String

    public init(used: Int, limit: Int, dayKey: String) {
        self.used = used
        self.limit = limit
        self.dayKey = dayKey
    }

    public var remaining: Int { max(limit - used, 0) }
    public var isExhausted: Bool { remaining <= 0 }
}

/// 本地限流：每天最多生成 20 条，按自然日重置，计数落 `UserDefaults`。
public final class QuotaStore: ObservableObject {

    public static let shared = QuotaStore()
    public static let dailyLimit = 20

    private enum Key {
        static let day = "keepgen.quota.day"
        static let count = "keepgen.quota.count"
    }

    @Published public private(set) var used: Int = 0

    private let defaults: UserDefaults
    private let calendar: Calendar

    public init(defaults: UserDefaults = .standard, calendar: Calendar = .current) {
        self.defaults = defaults
        self.calendar = calendar
        refresh()
    }

    public static func dayKey(for date: Date, calendar: Calendar = .current) -> String {
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d",
                      components.year ?? 0,
                      components.month ?? 0,
                      components.day ?? 0)
    }

    /// 跨自然日自动归零。
    public func refresh(now: Date = Date()) {
        let key = QuotaStore.dayKey(for: now, calendar: calendar)
        if defaults.string(forKey: Key.day) != key {
            defaults.set(key, forKey: Key.day)
            defaults.set(0, forKey: Key.count)
        }
        let current = defaults.integer(forKey: Key.count)
        if current != used { used = current }
    }

    public func state(now: Date = Date()) -> QuotaState {
        refresh(now: now)
        return QuotaState(used: used, limit: QuotaStore.dailyLimit, dayKey: QuotaStore.dayKey(for: now, calendar: calendar))
    }

    public func canGenerate(_ count: Int = 1, now: Date = Date()) -> Bool {
        state(now: now).remaining >= count
    }

    /// 记一次生成。返回 false 表示已超上限、本次不计。
    @discardableResult
    public func record(_ count: Int = 1, now: Date = Date()) -> Bool {
        refresh(now: now)
        guard used + count <= QuotaStore.dailyLimit else { return false }
        used += count
        defaults.set(used, forKey: Key.count)
        return true
    }

    public func reset(now: Date = Date()) {
        defaults.set(QuotaStore.dayKey(for: now, calendar: calendar), forKey: Key.day)
        defaults.set(0, forKey: Key.count)
        used = 0
    }
}