import Foundation
import Security

// MARK: - 凭证存放

/// 本机凭证存放：设备号与激活凭证都进 Keychain（`kSecClassGenericPassword`）。
///
/// 卸载重装设备号会变——和 DeepChat 参考实现一致：凭证按设备号签发，
/// 所以重装后要去作者那里按新设备号换一枚。
///
/// Keychain 写不进去时（自签名 / ad-hoc 包的 keychain-access-group 可能被剥掉）
/// 退回 `UserDefaults`，保证「兑换成功后重启还在」不会被系统环境弄坏。
public final class LicenseStore {

    public static let shared = LicenseStore()

    /// Keychain service（与 Android 端 keystore 无关，这是 iOS 侧独立命名空间）。
    public static let keychainService = "com.qianyuzhou.keepgen.ios"

    private enum Account {
        static let device = "device-id"
        static let envelope = "license-envelope"
    }

    private let service: String
    private let defaults: UserDefaults

    public init(service: String = LicenseStore.keychainService, defaults: UserDefaults = .standard) {
        self.service = service
        self.defaults = defaults
    }

    // MARK: - 设备号

    /// 首次调用生成 UUID 并落 Keychain，之后稳定返回同一串。
    public func deviceId() -> String {
        if let existing = read(Account.device), !existing.isEmpty { return existing }
        let fresh = LicenseVerifier.makeDeviceId()
        write(Account.device, fresh)
        return fresh
    }

    // MARK: - 激活凭证

    public func loadEnvelope() -> String? {
        guard let value = read(Account.envelope) else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    public func saveEnvelope(_ voucher: String) {
        let trimmed = voucher.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            clearEnvelope()
        } else {
            write(Account.envelope, trimmed)
        }
    }

    public func clearEnvelope() {
        delete(Account.envelope)
        defaults.removeObject(forKey: fallbackKey(Account.envelope))
    }

    // MARK: - 当前状态

    /// 每次现算：验签 + 绑机 + 有效期，不缓存「已激活」的布尔值。
    public func state(now: Date = Date()) -> LicenseState {
        guard let envelope = loadEnvelope() else { return .inactive }
        return LicenseVerifier.evaluate(envelope, currentDevice: deviceId(), now: now)
    }

    /// 生成前的闸门：已激活返回 nil，否则返回给用户看的中文原因。
    public func denialMessage(now: Date = Date()) -> String? {
        let current = state(now: now)
        guard !current.allowsGeneration else { return nil }
        return "\(current.displayTitle)：\(current.displayDetail) 请先到「授权」兑换 1 元兑换码再生成。"
    }

    // MARK: - Keychain 原语

    private func keychainQuery(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }

    private func read(_ account: String) -> String? {
        var query = keychainQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
           let data = out as? Data,
           let text = String(data: data, encoding: .utf8),
           !text.isEmpty {
            return text
        }
        return defaults.string(forKey: fallbackKey(account))
    }

    private func write(_ account: String, _ value: String) {
        let data = Data(value.utf8)
        let query = keychainQuery(account)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecSuccess { return }
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        if SecItemAdd(add as CFDictionary, nil) == errSecSuccess { return }
        // Keychain 被签名环境挡住时退回落盘存储，否则设备号每次启动都换、凭证永远对不上。
        defaults.set(value, forKey: fallbackKey(account))
    }

    private func delete(_ account: String) {
        SecItemDelete(keychainQuery(account) as CFDictionary)
    }

    private func fallbackKey(_ account: String) -> String {
        "keepgen.license.\(account)"
    }
}