import SwiftUI
import KeepGenCore

// MARK: - 主题

/// 深色一套：底、面、字、次要字、强调色，数量按用途定死。
public enum Theme {
    public static let bg = Color(red: 0.016, green: 0.055, blue: 0.055)       // #040E0E
    public static let surface = Color(red: 0.055, green: 0.118, blue: 0.114)  // #0E1E1D
    public static let surface2 = Color(red: 0.082, green: 0.165, blue: 0.157)
    public static let line = Color.white.opacity(0.08)
    public static let ink = Color(red: 0.906, green: 0.945, blue: 0.929)
    public static let dim = Color(red: 0.545, green: 0.647, blue: 0.627)
    public static let faint = Color(red: 0.353, green: 0.451, blue: 0.435)
    public static let accent = Color(red: 0.145, green: 0.827, blue: 0.741)   // #25D3BD 深蓝绿
    public static let accentDim = Color(red: 0.090, green: 0.518, blue: 0.463)
    public static let accentInk = Color(red: 0.012, green: 0.125, blue: 0.114)
    public static let ok = Color(red: 0.243, green: 0.812, blue: 0.557)
    public static let warn = Color(red: 0.961, green: 0.702, blue: 0.267)
    public static let danger = Color(red: 0.937, green: 0.451, blue: 0.451)

    public static let radius: CGFloat = 16
    public static let pill: CGFloat = 99
}

// MARK: - 容器

public struct Card<Content: View>: View {
    private let content: Content

    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            content
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface)
        .clipShape(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Theme.radius, style: .continuous)
                .stroke(Theme.line, lineWidth: 1)
        )
    }
}

public struct SectionLabel: View {
    private let text: String

    public init(_ text: String) {
        self.text = text
    }

    public var body: some View {
        Text(text.uppercased())
            .font(.system(size: 11, weight: .semibold))
            .tracking(1.1)
            .foregroundStyle(Theme.faint)
    }
}

// MARK: - 按钮

public struct PrimaryButton: View {
    private let title: String
    private let systemImage: String?
    private let isEnabled: Bool
    private let action: () -> Void

    public init(_ title: String, systemImage: String? = nil, isEnabled: Bool = true, action: @escaping () -> Void) {
        self.title = title
        self.systemImage = systemImage
        self.isEnabled = isEnabled
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage {
                    Image(systemName: systemImage).font(.system(size: 14, weight: .semibold))
                }
                Text(title).font(.system(size: 15, weight: .semibold))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 13)
            .background(isEnabled ? Theme.accent : Theme.surface2)
            .foregroundStyle(isEnabled ? Theme.accentInk : Theme.faint)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
    }
}

public struct GhostButton: View {
    private let title: String
    private let systemImage: String?
    private let tint: Color
    private let action: () -> Void

    public init(_ title: String, systemImage: String? = nil, tint: Color = Theme.ink, action: @escaping () -> Void) {
        self.title = title
        self.systemImage = systemImage
        self.tint = tint
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage {
                    Image(systemName: systemImage).font(.system(size: 13, weight: .medium))
                }
                Text(title).font(.system(size: 14, weight: .medium))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 11)
            .background(Theme.surface2)
            .foregroundStyle(tint)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 控件

public struct ToggleRow: View {
    private let title: String
    private let subtitle: String
    @Binding private var isOn: Bool

    public init(_ title: String, subtitle: String = "", isOn: Binding<Bool>) {
        self.title = title
        self.subtitle = subtitle
        self._isOn = isOn
    }

    public var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Theme.ink)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.faint)
                }
            }
        }
        .toggleStyle(.switch)
        .tint(Theme.accent)
    }
}

public struct ValueSlider: View {
    private let title: String
    private let display: String
    private let range: ClosedRange<Double>
    private let step: Double
    @Binding private var value: Double

    public init(_ title: String,
                value: Binding<Double>,
                range: ClosedRange<Double>,
                step: Double,
                display: String) {
        self.title = title
        self._value = value
        self.range = range
        self.step = step
        self.display = display
    }

    public var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                Text(title)
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.dim)
                Spacer(minLength: 8)
                Text(display)
                    .font(.system(size: 14, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Theme.accent)
            }
            Slider(value: $value, in: range, step: step)
                .tint(Theme.accent)
        }
    }
}

public struct KVRow: View {
    private let key: String
    private let value: String
    private let accent: Bool

    public init(_ key: String, _ value: String, accent: Bool = false) {
        self.key = key
        self.value = value
        self.accent = accent
    }

    public var body: some View {
        HStack {
            Text(key).font(.system(size: 12)).foregroundStyle(Theme.dim)
            Spacer(minLength: 8)
            Text(value)
                .font(.system(size: 13, weight: accent ? .semibold : .regular, design: .monospaced))
                .foregroundStyle(accent ? Theme.accent : Theme.ink)
        }
    }
}

public struct StatTile: View {
    private let label: String
    private let value: String
    private let unit: String

    public init(_ label: String, _ value: String, unit: String = "") {
        self.label = label
        self.value = value
        self.unit = unit
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.system(size: 11))
                .foregroundStyle(Theme.faint)
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value)
                    .font(.system(size: 18, weight: .semibold, design: .rounded))
                    .foregroundStyle(Theme.ink)
                if !unit.isEmpty {
                    Text(unit)
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.dim)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Theme.surface2)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

// MARK: - 轨迹草图

public struct RouteSketch: View {
    private let points: [RoutePoint]
    private let height: CGFloat

    public init(points: [RoutePoint], height: CGFloat = 150) {
        self.points = points
        self.height = height
    }

    public var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            let path = normalizedPath(in: size)
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Theme.surface2)
                if let path {
                    path
                        .stroke(Theme.accent, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                        .shadow(color: Theme.accent.opacity(0.35), radius: 4)
                } else {
                    Text("没有可画的轨迹")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.faint)
                }
            }
        }
        .frame(height: height)
    }

    private func normalizedPath(in size: CGSize) -> Path? {
        guard points.count >= 2 else { return nil }
        let lats = points.map(\.latitude)
        let lngs = points.map(\.longitude)
        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLng = lngs.min(), let maxLng = lngs.max() else { return nil }
        let spanLat = max(maxLat - minLat, 1e-6)
        let spanLng = max(maxLng - minLng, 1e-6)
        let inset: CGFloat = 14
        let drawable = CGSize(width: max(size.width - inset * 2, 1), height: max(size.height - inset * 2, 1))
        var path = Path()
        for (index, point) in points.enumerated() {
            let x = (point.longitude - minLng) / spanLng
            let y = (point.latitude - minLat) / spanLat
            let cgPoint = CGPoint(x: inset + drawable.width * CGFloat(x),
                                  y: size.height - inset - drawable.height * CGFloat(y))
            if index == 0 {
                path.move(to: cgPoint)
            } else {
                path.addLine(to: cgPoint)
            }
        }
        return path
    }
}