import SwiftUI
import KeepGenCore

// MARK: - 历史页

public struct HistoryView: View {

    @ObservedObject private var history = HistoryStore.shared
    @Environment(\.dismiss) private var dismiss

    public init() {}

    public var body: some View {
        NavigationStack {
            ZStack {
                Theme.bg.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 12) {
                        summaryCard
                        ForEach(history.entries) { entry in
                            entryCard(entry)
                        }
                        if history.entries.isEmpty {
                            Card {
                                Text("还没有生成记录。")
                                    .font(.system(size: 13))
                                    .foregroundStyle(Theme.faint)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 20)
                    .padding(.top, 8)
                }
            }
            .navigationTitle("历史记录")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                        .foregroundStyle(Theme.accent)
                }
                ToolbarItem(placement: .primaryAction) {
                    Button("清空") { history.removeAll() }
                        .foregroundStyle(Theme.danger)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var summaryCard: some View {
        Card {
            SectionLabel("统计")
            HStack(spacing: 10) {
                StatTile("记录", "\(history.entries.count)", unit: "条")
                StatTile("成功", "\(history.entries.filter(\.wroteToHealth).count)", unit: "条")
                StatTile("失败", "\(history.entries.filter { !$0.wroteToHealth }.count)", unit: "条")
            }
            Text("删除记录不会删健康里的数据。")
                .font(.system(size: 10))
                .foregroundStyle(Theme.faint)
        }
    }

    private func entryCard(_ entry: HistoryEntry) -> some View {
        Card {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: entry.kind.symbol)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(entry.wroteToHealth ? Theme.accent : Theme.danger)
                    .padding(8)
                    .background(Theme.surface2)
                    .clipShape(Circle())

                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(entry.kind.title)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Theme.ink)
                        Text(entry.wroteToHealth ? "已写入" : "未写入")
                            .font(.system(size: 10, weight: .medium))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background((entry.wroteToHealth ? Theme.ok : Theme.danger).opacity(0.16))
                            .foregroundStyle(entry.wroteToHealth ? Theme.ok : Theme.danger)
                            .clipShape(Capsule())
                    }
                    Text("\(Format.dateTime(entry.startDate)) · \(Format.distance(entry.distanceMeters, units: .metric)) km · \(Format.duration(entry.durationSeconds)) · \(Format.pace(entry.paceSecondsPerKm))")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(Theme.dim)
                    Text("心率 \(entry.averageHeartRate)/\(entry.maxHeartRate) bpm · \(String(format: "%.0f", entry.energyKcal)) kcal · \(entry.steps) 步")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(Theme.faint)
                    if !entry.message.isEmpty {
                        Text(entry.message)
                            .font(.system(size: 10))
                            .foregroundStyle(Theme.warn)
                    }
                }

                Spacer(minLength: 0)

                Button {
                    history.remove(id: entry.id)
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.faint)
                        .padding(6)
                }
                .buttonStyle(.plain)
            }
        }
    }
}