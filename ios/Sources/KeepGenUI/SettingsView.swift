import SwiftUI
import KeepGenCore

// MARK: - 设置页

public struct SettingsView: View {

    @ObservedObject var settings: AppSettings
    @ObservedObject var quota: QuotaStore

    /// 使用须知常驻入口。
    @State private var showTerms = false

    public init(settings: AppSettings, quota: QuotaStore) {
        self.settings = settings
        self.quota = quota
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                defaultCard
                hrCard
                writeCard
                unitCard
                quotaCard
                termsCard
                aboutCard
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 20)
        }
        .task { quota.refresh() }
        .sheet(isPresented: $showTerms) {
            TermsView(mode: .sheet)
        }
    }

    private var defaultCard: some View {
        Card {
            SectionLabel("默认值")
            ValueSlider("默认体重（kg）",
                        value: $settings.defaultWeightKg,
                        range: 40...120,
                        step: 0.5,
                        display: String(format: "%.1f kg", settings.defaultWeightKg))
            ValueSlider("默认配速（秒/公里）",
                        value: $settings.defaultPaceSecondsPerKm,
                        range: 180...600,
                        step: 5,
                        display: "\(Format.pace(settings.defaultPaceSecondsPerKm)) /km")
            ValueSlider("默认时长（分钟）",
                        value: $settings.defaultDurationMinutes,
                        range: 10...180,
                        step: 1,
                        display: String(format: "%.0f min", settings.defaultDurationMinutes))
            ValueSlider("默认距离（km）",
                        value: $settings.defaultDistanceKm,
                        range: 1...40,
                        step: 0.5,
                        display: String(format: "%.1f km", settings.defaultDistanceKm))
            Stepper(value: $settings.defaultCadence, in: 120...220) {
                HStack {
                    Text("默认步频")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.dim)
                    Spacer()
                    Text("\(settings.defaultCadence) spm")
                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        .foregroundStyle(Theme.accent)
                }
            }
            Text("默认值在打开「生成」页时带入；已改过的单条参数不会被覆盖。")
                .font(.system(size: 10))
                .foregroundStyle(Theme.faint)
        }
    }

    private var hrCard: some View {
        Card {
            SectionLabel("心率模型（Karvonen 心率储备）")
            ValueSlider("静息心率（bpm）",
                        value: Binding(get: { Double(settings.restBpm) },
                                       set: { settings.restBpm = Int($0.rounded()) }),
                        range: 35...110,
                        step: 1,
                        display: "\(settings.restBpm) bpm")
            ValueSlider("最高心率（bpm）",
                        value: Binding(get: { Double(settings.maxBpm) },
                                       set: { settings.maxBpm = Int($0.rounded()) }),
                        range: 120...230,
                        step: 1,
                        display: "\(settings.maxBpm) bpm")
            ValueSlider("最快 1 公里配速",
                        value: Binding(get: { Double(settings.paceMaxSec) },
                                       set: { settings.paceMaxSec = Int($0.rounded()) }),
                        range: 150...600,
                        step: 5,
                        display: "\(Format.clock(Double(settings.paceMaxSec))) /km")
            Text(settings.hrSummary)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Theme.accent)
            Text("HRR = 最高 − 静息；%HRR = clamp(0.35 + 0.65 × (速度/最快配速速度)^0.8, 0.30, 1.00)；再叠热身 180 s、漂移 +3%/h、末段冲刺 90 s。")
                .font(.system(size: 10))
                .foregroundStyle(Theme.faint)
        }
    }

    private var writeCard: some View {
        Card {
            SectionLabel("写入内容")
            ToggleRow("写入心率序列", subtitle: "每 10 秒一个采样点", isOn: $settings.writeHeartRate)
            ToggleRow("写入步数", subtitle: "跑步与行走；骑行不写", isOn: $settings.writeSteps)
            ToggleRow("写入轨迹", subtitle: "有坐标时写 HKWorkoutRoute", isOn: $settings.writeRoute)
        }
    }

    private var unitCard: some View {
        Card {
            SectionLabel("单位")
            Picker("单位", selection: $settings.units) {
                ForEach(Units.allCases) { unit in
                    Text(unit.title).tag(unit)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            Text("只影响界面显示；写进健康的量统一是米 / 千卡 / 次。")
                .font(.system(size: 10))
                .foregroundStyle(Theme.faint)
        }
    }

    private var quotaCard: some View {
        Card {
            SectionLabel("本地限流")
            KVRow("今日已生成", "\(quota.state().used) / \(QuotaStore.dailyLimit) 条", accent: true)
            KVRow("剩余", "\(quota.state().remaining) 条")
            KVRow("计数日期", quota.state().dayKey)
            Text("按自然日重置，记在 UserDefaults；超了生成页会拦下来。")
                .font(.system(size: 10))
                .foregroundStyle(Theme.faint)
        }
    }

    private var termsCard: some View {
        Card {
            SectionLabel("使用须知与免责声明")
            Text("生成的是算法数据，不是真实运动；成绩申报、赛事、考核、保险、医疗等场景一律禁止使用。")
                .font(.system(size: 11))
                .foregroundStyle(Theme.dim)
                .fixedSize(horizontal: false, vertical: true)
            GhostButton("查看完整须知", systemImage: "doc.text", tint: Theme.ink) {
                showTerms = true
            }
        }
    }

    private var aboutCard: some View {
        Card {
            SectionLabel("关于")
            KVRow("版本", "1.2.0 (3)")
            KVRow("数据范围", "只写本机健康，不联网")
            KVRow("活动", "户外跑步 / 室内跑步 / 户外行走 / 户外骑行")
            KVRow("卡路里", "round(体重 × 公里 × 1.036/0.85/0.5)")
            GhostButton("恢复默认设置", systemImage: "arrow.counterclockwise", tint: Theme.warn) {
                settings.resetToDefaults()
            }
        }
    }
}