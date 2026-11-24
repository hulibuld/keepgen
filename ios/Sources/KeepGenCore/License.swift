import Foundation
import CryptoKit

// MARK: - 凭证

/// 离线激活凭证的载荷：`{"v":2,"c":"码","d":"设备号或*","e":过期毫秒,"n":"随机串","o":0|1}`。
///
/// 一码通：payload 里**没有产品字段**，DeepChat 与 KeepGen 共用同一批码，同一枚码两边都能激活。
public struct LicenseClaims: Sendable, Equatable {
    public let code: String
    public let device: String
    public let expiresAt: Date
    public let nonce: String
    public let requiresOnline: Bool

    public init(code: String, device: String, expiresAt: Date, nonce: String, requiresOnline: Bool) {
        self.code = code
        self.device = device
        self.expiresAt = expiresAt
        self.nonce = nonce
        self.requiresOnline = requiresOnline
    }

    public var isExpired: Bool { expiresAt <= Date() }

    /// 凭证里的设备号支持通配 `*`：零售兑换码按「未绑定」签发，首次激活绑本机。
    public func matches(device current: String) -> Bool {
        device == current || device == "*"
    }
}

public enum LicenseState: Sendable, Equatable {
    case inactive
    case active(LicenseClaims)
    case expired(LicenseClaims)
    case wrongDevice(String)
    case invalid(String)
}

// MARK: - 校验

/// 离线激活凭证校验。
///
/// 凭证格式：`YC2.<base64url(payloadJSON)>.<base64url(DER ECDSA-P256-SHA256 签名)>`
/// 签名覆盖 payload 段的 ASCII 字节；客户端只内置公钥，**没有任何签发能力**。
/// 格式与公钥都和 DeepChat（iOS `DeepChatCore/License.swift`、Android `core/License.kt`）完全一致。
public enum LicenseVerifier {

    /// 与 DeepChat / Android 端同一把公钥（X.509 SPKI, base64）。
    public static let publicKeyBase64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAECdpw"
        + "eTopF/fN1ELhU0rVy7hp7lg3EFLs8ez464OL53VWQrRd"
        + "SCF+0lBkypzHNCuk230eMoRc1o0y1h6ftjRC5g=="

    public static let prefix = "YC2."
    public static let claimVersion = 2

    public enum Failure: Error, Equatable {
        case notAVoucher
        case malformed
        case badSignature
        case badVersion
        case missingField
        case badExpiry
    }

    /// 纯验签：只回答“这串是不是作者签的、内容有没有被改”。
    public static func verify(_ input: String) throws -> LicenseClaims {
        let cleaned = input
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\n", with: "")
            .replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "\t", with: "")

        guard cleaned.hasPrefix(prefix) else { throw Failure.notAVoucher }
        let body = String(cleaned.dropFirst(prefix.count))
        let parts = body.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 2 else { throw Failure.malformed }

        let payloadSeg = String(parts[0])
        guard let sigData = base64URLDecode(String(parts[1])) else { throw Failure.malformed }
        guard let payloadData = base64URLDecode(payloadSeg) else { throw Failure.malformed }

        guard let keyData = Data(base64Encoded: publicKeyBase64),
              let pub = try? P256.Signing.PublicKey(derRepresentation: keyData) else {
            throw Failure.badSignature
        }
        guard let sig = try? P256.Signing.ECDSASignature(derRepresentation: sigData) else {
            throw Failure.badSignature
        }
        guard pub.isValidSignature(sig, for: Data(payloadSeg.utf8)) else {
            throw Failure.badSignature
        }

        guard let obj = try? JSONSerialization.jsonObject(with: payloadData) as? [String: Any],
              let version = obj["v"] as? Int else { throw Failure.malformed }
        guard version == claimVersion else { throw Failure.badVersion }

        guard let code = (obj["c"] as? String), !code.isEmpty,
              let device = (obj["d"] as? String), !device.isEmpty else {
            throw Failure.missingField
        }
        let expiresNumber = (obj["e"] as? NSNumber)?.doubleValue ?? 0
        guard expiresNumber > 0 else { throw Failure.badExpiry }

        return LicenseClaims(
            code: code,
            device: device,
            expiresAt: Date(timeIntervalSince1970: expiresNumber / 1000.0),
            nonce: (obj["n"] as? String) ?? "",
            requiresOnline: (obj["o"] as? Int) == 1
        )
    }

    /// 验签 + 绑机 + 未过期，三者齐了才算激活。
    public static func evaluate(_ input: String, currentDevice: String, now: Date = Date()) -> LicenseState {
        do {
            let claims = try verify(input)
            if !claims.matches(device: currentDevice) {
                return .wrongDevice(claims.device)
            }
            if claims.expiresAt <= now {
                return .expired(claims)
            }
            return .active(claims)
        } catch Failure.notAVoucher {
            return .invalid("这不是激活凭证（应以 YC2. 开头）")
        } catch Failure.badSignature {
            return .invalid("凭证签名校验失败：内容被改动，或不是作者签发")
        } catch Failure.badVersion {
            return .invalid("凭证版本过新，请更新应用")
        } catch Failure.badExpiry {
            return .invalid("凭证缺少有效期")
        } catch {
            return .invalid("凭证格式不正确")
        }
    }

    /// 用于向作者换签：本机稳定标识。
    public static func makeDeviceId() -> String {
        UUID().uuidString.lowercased()
    }

    // MARK: - helpers

    static func base64URLDecode(_ s: String) -> Data? {
        var t = s.replacingOccurrences(of: "-", with: "+")
                 .replacingOccurrences(of: "_", with: "/")
        let rem = t.count % 4
        if rem > 0 { t += String(repeating: "=", count: 4 - rem) }
        return Data(base64Encoded: t)
    }
}

// MARK: - 状态文案

public extension LicenseState {

    /// 只有 `.active` 放行生成。
    var allowsGeneration: Bool {
        if case .active = self { return true }
        return false
    }

    var displayTitle: String {
        switch self {
        case .active: return "已激活"
        case .expired: return "已过期"
        case .wrongDevice: return "绑的是别的设备"
        case .invalid: return "凭证无效"
        case .inactive: return "未激活"
        }
    }

    /// 兑换页 / 授权入口显示的中文原因。
    var displayDetail: String {
        switch self {
        case .active(let claims):
            return "已绑定本机，码 \(LicenseState.mask(claims.code))，有效期至 \(LicenseState.dateText(claims.expiresAt))。"
        case .expired(let claims):
            return "这枚码已于 \(LicenseState.dateText(claims.expiresAt)) 过期，请重新兑换。"
        case .wrongDevice(let device):
            return "这枚码绑的是 \(device)，不是本机；一码一设备，换设备请联系作者重新登记。"
        case .invalid(let reason):
            return reason
        case .inactive:
            return "还没有兑换：把整串 YC2 凭证粘进来，一码一设备。"
        }
    }

    static func mask(_ code: String) -> String {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        guard trimmed.count > 8 else { return trimmed }
        return String(trimmed.prefix(4)) + "••••" + String(trimmed.suffix(4))
    }

    static func dateText(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: date)
    }
}