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
    public static let keychainService = "com.keepgen.hulibuld"

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

    // MARK: - 首激活设备登记

    /// 首激活设备登记服务。端点只在这里写一次，别处不要另写字符串。
    public static let bindEndpoint = URL(string: "https://deepchat-writer.pages.dev/bind")!

    /// 兑换时的登记结果。
    public enum BindResult: Sendable, Equatable {
        /// 服务端放行：本来就是本机登记过（first = true 首次，false 是重装后再次登记）。
        case allowed(first: Bool, count: Int)
        /// 这枚码已经绑在别的设备上，必须拦。
        case deviceMismatch(bound: String)
        /// 联网失败 / 服务端异常。
        case network(String)
    }

    /// 本地验签通过后调用：把码本机设备号报到服务端，登记成功才算激活。
    /// 只在兑换时走这一条，启动时的门禁判断不联网。
    public func registerActivation(code: String, deviceId: String) async -> BindResult {
        var request = URLRequest(url: LicenseStore.bindEndpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try? JSONSerialization.data(withJSONObject: [
            "code": code,
            "deviceId": deviceId
        ])

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            return .network(error.localizedDescription)
        }

        guard let http = response as? HTTPURLResponse else {
            return .network("服务端响应异常")
        }
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]

        switch http.statusCode {
        case 200..<300:
            let first = (object?["first"] as? Bool) ?? true
            let count = (object?["count"] as? NSNumber)?.intValue ?? 1
            return .allowed(first: first, count: count)
        case 409:
            return .deviceMismatch(bound: (object?["bound"] as? String) ?? "")
        default:
            return .network("服务端返回 HTTP \(http.statusCode)")
        }
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