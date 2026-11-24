import SwiftUI
import KeepGenCore

// MARK: - 路线页

public struct RouteView: View {

    @ObservedObject private var library = RouteLibrary.shared
    @State private var newName: String = ""
    @State private var newText: String = ""
    @State private var message: String?
    @State private var selectedID: UUID?

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                addCard
                listCard
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 20)
        }
    }

    private var addCard: some View {
        Card {
            SectionLabel("新建路线")
            TextField("路线名字，例如「西湖环湖」", text: $newName)
                .font(.system(size: 13))
                .textFieldStyle(.plain)
                .padding(.horizontal, 10)
                .padding(.vertical, 9)
                .background(Theme.surface2)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            TextEditor(text: $newText)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Theme.ink)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 110, maxHeight: 150)
                .padding(8)
                .background(Theme.surface2)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(Theme.line, lineWidth: 1)
                )

            HStack(spacing: 10) {
                GhostButton("填入田径场示例", systemImage: "figure.run") {
                    let points = Generator.trackPoints(config: TrackConfig(), distanceMeters: 1_000)
                    newText = points.map { String(format: "%.6f,%.6f", $0.latitude, $0.longitude) }.joined(separator: "\n")
                    if newName.isEmpty { newName = "田径场 400m" }
                }
                GhostButton("保存路线", systemImage: "tray.and.arrow.down.fill", tint: Theme.accent) {
                    saveNew()
                }
            }

            Text("一行一个 `lat,lng`；同名会覆盖。")
                .font(.system(size: 10))
                .foregroundStyle(Theme.faint)

            if let message {
                Text(message)
                    .font(.system(size: 11))
                    .foregroundStyle(message.hasPrefix("已保存") ? Theme.ok : Theme.warn)
            }
        }
    }

    private var listCard: some View {
        Card {
            HStack {
                SectionLabel("路线库")
                Spacer()
                Text("\(library.routes.count) 条")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Theme.faint)
            }

            if library.routes.isEmpty {
                Text("还没有保存的路线。粘贴坐标后点「保存路线」，生成页就能选用它。")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.faint)
            } else {
                ForEach(library.routes) { route in
                    VStack(spacing: 8) {
                        HStack(spacing: 10) {
                            Button {
                                selectedID = (selectedID == route.id) ? nil : route.id
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(route.name)
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundStyle(Theme.ink)
                                    Text("\(route.pointCount) 点 · \(route.distanceKmText) km · \(Format.dateTime(route.createdAt))")
                                        .font(.system(size: 10, design: .monospaced))
                                        .foregroundStyle(Theme.faint)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .buttonStyle(.plain)

                            Button {
                                library.remove(id: route.id)
                                if selectedID == route.id { selectedID = nil }
                            } label: {
                                Image(systemName: "trash")
                                    .font(.system(size: 13))
                                    .foregroundStyle(Theme.danger)
                                    .padding(6)
                                    .background(Theme.surface2)
                                    .clipShape(Circle())
                            }
                            .buttonStyle(.plain)
                        }

                        if selectedID == route.id {
                            RouteSketch(points: route.points, height: 130)
                        }
                    }
                    .padding(.vertical, 6)

                    if route.id != library.routes.last?.id {
                        Divider().overlay(Theme.line)
                    }
                }
            }
        }
    }

    private func saveNew() {
        let points = Generator.parseCoordinates(newText)
        guard points.count >= 2 else {
            message = "至少要有两个合法坐标（一行一个 lat,lng）。"
            return
        }
        let name = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            message = "先给路线起个名字。"
            return
        }
        guard let saved = library.save(name: name, points: points) else {
            message = "保存失败，检查坐标格式。"
            return
        }
        message = "已保存：\(saved.name)（\(saved.pointCount) 点 / \(saved.distanceKmText) km）"
        selectedID = saved.id
        newName = ""
        newText = ""
    }
}