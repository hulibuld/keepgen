import SwiftUI
import KeepGenCore

// MARK: - 根视图

public struct RootView: View {

    public enum Tab: String, CaseIterable, Identifiable {
        case generate
        case batch
        case routes
        case settings

        public var id: String { rawValue }

        public var title: String {
            switch self {
            case .generate: return "生成"
            case .batch: return "批量"
            case .routes: return "路线"
            case .settings: return "设置"
            }
        }

        public var symbol: String {
            switch self {
            case .generate: return "waveform.path.ecg"
            case .batch: return "square.stack.3d.up"
            case .routes: return "point.topleft.down.curvedto.point.bottomright.up"
            case .settings: return "slider.horizontal.3"
            }
        }
    }

    @StateObject private var settings = AppSettings.shared
    @StateObject private var quota = QuotaStore.shared
    @State private var tab: Tab = .generate
    /// 每次现算（验签 + 绑机 + 有效期），不缓存「已激活」布尔值。
    @State private var license: LicenseState = LicenseStore.shared.state()
    @State private var showLicense = false

    public init() {}

    public var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            if license.allowsGeneration {
                VStack(spacing: 0) {
                    header
                    Group {
                        switch tab {
                        case .generate:
                            GenerateView(settings: settings, quota: quota)
                        case .batch:
                            BatchView(settings: settings, quota: quota)
                        case .routes:
                            RouteView()
                        case .settings:
                            SettingsView(settings: settings, quota: quota)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    tabBar
                }
            } else {
                LicenseGateView(gate: true) {
                    license = LicenseStore.shared.state()
                }
            }
        }
        .preferredColorScheme(.dark)
        .task { license = LicenseStore.shared.state() }
        .sheet(isPresented: $showLicense) {
            LicenseGateView(gate: false) {
                license = LicenseStore.shared.state()
            }
        }
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text("运动数据生成")
                    .font(.system(size: 19, weight: .bold))
                    .foregroundStyle(Theme.ink)
                Text("KeepGen · 写入 Apple 健康")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.faint)
            }
            Spacer()
            Button {
                showLicense = true
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: license.allowsGeneration ? "checkmark.seal.fill" : "lock.fill")
                        .font(.system(size: 10, weight: .semibold))
                    Text(license.allowsGeneration ? "已授权" : "未授权")
                        .font(.system(size: 11, weight: .medium))
                }
                .foregroundStyle(license.allowsGeneration ? Theme.ok : Theme.warn)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Theme.surface)
                .clipShape(Capsule())
            }
            .buttonStyle(.plain)
            HStack(spacing: 6) {
                Circle()
                    .fill(quota.state().isExhausted ? Theme.danger : Theme.accent)
                    .frame(width: 6, height: 6)
                Text("今日 \(quota.state().used)/\(QuotaStore.dailyLimit)")
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(Theme.dim)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Theme.surface)
            .clipShape(Capsule())
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 10)
    }

    private var tabBar: some View {
        HStack(spacing: 4) {
            ForEach(Tab.allCases) { item in
                Button {
                    tab = item
                } label: {
                    VStack(spacing: 4) {
                        Image(systemName: item.symbol)
                            .font(.system(size: 15, weight: .medium))
                        Text(item.title)
                            .font(.system(size: 10, weight: .medium))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .foregroundStyle(tab == item ? Theme.accent : Theme.faint)
                    .background(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(tab == item ? Theme.accent.opacity(0.12) : Color.clear)
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 10)
        .padding(.top, 6)
        .padding(.bottom, 6)
        .background(Theme.surface)
        .overlay(alignment: .top) {
            Rectangle().fill(Theme.line).frame(height: 1)
        }
    }
}