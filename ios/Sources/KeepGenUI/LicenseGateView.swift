import SwiftUI
import KeepGenCore

// MARK: - 授权门槛

/// 兑换页：启动时未激活就整页显示这一页；已激活后也能从顶栏「已授权 / 未授权」再进来重贴码。
public struct LicenseGateView: View {

    private let gate: Bool
    private let store: LicenseStore
    private let onUnlocked: () -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var input: String = ""
    @State private var state: LicenseState = .inactive
    @State private var failure: String?
    @State private var device: String = ""
    @State private var copied = false

    public init(gate: Bool = true,
                store: LicenseStore = .shared,
                onUnlocked: @escaping () -> Void = {}) {
        self.gate = gate
        self.store = store
        self.onUnlocked = onUnlocked
    }

    public var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 12) {
                    if !gate { closeRow }
                    headerCard
                    redeemCard
                    deviceCard
                    footerCard
                }
                .padding(.horizontal, 16)
                .padding(.top, gate ? 30 : 8)
                .padding(.bottom, 24)
            }
        }
        .task { refresh() }
        .preferredColorScheme(.dark)
    }

    // MARK: 头部

    private var closeRow: some View {
        HStack {
            Spacer()
            GhostButton("关闭", systemImage: "xmark", tint: Theme.dim) {
                dismiss()
            }
            .frame(width: 96)
        }
    }

    private var headerCard: some View {
        Card {
            HStack(spacing: 8) {
                Text("兑换码")
                    .font(.system(size: 21, weight: .bold))
                    .foregroundStyle(Theme.ink)
                Spacer()
                HStack(spacing: 6) {
                    Circle()
                        .fill(state.allowsGeneration ? Theme.ok : Theme.warn)
                        .frame(width: 6, height: 6)
                    Text(state.displayTitle)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(state.allowsGeneration ? Theme.ok : Theme.warn)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Theme.surface2)
                .clipShape(Capsule())
            }
            Text("1 元一枚，永久有效；所有收入捐给贫困儿童教育事业。")
                .font(.system(size: 12))
                .foregroundStyle(Theme.dim)
            Text(state.displayDetail)
                .font(.system(size: 11))
                .foregroundStyle(state.allowsGeneration ? Theme.ok : Theme.faint)
        }
    }

    // MARK: 兑换

    private var redeemCard: some View {
        Card {
            SectionLabel("粘贴激活凭证")
            TextEditor(text: $input)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(Theme.ink)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 96, maxHeight: 132)
                .padding(8)
                .background(Theme.surface2)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(Theme.line, lineWidth: 1)
                )
                .overlay(alignment: .topLeading) {
                    if input.isEmpty {
                        Text("YC2.…（整串粘贴，换行也没关系）")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(Theme.faint)
                            .padding(.horizontal, 13)
                            .padding(.vertical, 16)
                            .allowsHitTesting(false)
                    }
                }
                .onChange(of: input) { _ in failure = nil }

            PrimaryButton("验证 / 兑换", systemImage: "checkmark.shield", isEnabled: !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
                redeem()
            }

            if let failure {
                Text(failure)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if state.allowsGeneration {
                Text("已激活。换码可以直接再贴一串覆盖。")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.ok)
            }
        }
    }

    // MARK: 设备号

    private var deviceCard: some View {
        Card {
            SectionLabel("本机设备号")
            Text(device)
                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                .foregroundStyle(Theme.accent)
                .textSelection(.enabled)
                .onTapGesture { copyDevice() }
            HStack(spacing: 10) {
                GhostButton(copied ? "已复制" : "复制设备号",
                            systemImage: copied ? "checkmark" : "doc.on.doc",
                            tint: copied ? Theme.ok : Theme.ink) {
                    copyDevice()
                }
                Text(copied ? "粘给作者即可签发" : "点数字或按钮都能复制")
                    .font(.system(size: 10))
                    .foregroundStyle(Theme.faint)
            }
            Text("凭证按这串设备号签发；它存在本机 Keychain 里，卸载重装会变，换设备请在作者处重新登记。")
                .font(.system(size: 10))
                .foregroundStyle(Theme.faint)
        }
    }

    private var footerCard: some View {
        Card {
            SectionLabel("关于收费")
            Text("收费是为了让工具能一直更新下去：1 元一枚，一码一设备。")
                .font(.system(size: 12))
                .foregroundStyle(Theme.dim)
            Text("兑换问题、退款、换设备重新登记，通过购买页联系作者。")
                .font(.system(size: 11))
                .foregroundStyle(Theme.faint)
        }
    }

    // MARK: 动作

    private func refresh() {
        device = store.deviceId()
        state = store.state()
    }

    private func redeem() {
        let voucher = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !voucher.isEmpty else { return }
        let result = LicenseVerifier.evaluate(voucher, currentDevice: store.deviceId())
        state = store.state()
        if result.allowsGeneration {
            store.saveEnvelope(voucher)
            state = store.state()
            failure = nil
            input = ""
            onUnlocked()
        } else {
            failure = "\(result.displayTitle)：\(result.displayDetail)"
        }
    }

    private func copyDevice() {
        #if os(iOS)
        UIPasteboard.general.string = device
        #endif
        copied = true
    }
}