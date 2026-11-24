import SwiftUI
import KeepGenCore

// MARK: - 批量页

public struct BatchView: View {

    @ObservedObject var settings: AppSettings
    @ObservedObject var quota: QuotaStore
    @StateObject private var model = BatchModel()

    public init(settings: AppSettings, quota: QuotaStore) {
        self.settings = settings
        self.quota = quota
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                rangeCard
                templateCard
                weightCard
                runCard
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 20)
        }
        .task {
            model.applyDefaults(settings)
            quota.refresh()
        }
    }

    private var rangeCard: some View {
        Card {
            SectionLabel("日期范围 · 每天条数 · 时间窗")
            DatePicker("起始日", selection: $model.startDay, displayedComponents: [.date])
                .font(.system(size: 13))
                .datePickerStyle(.compact)
                .tint(Theme.accent)
            DatePicker("结束日", selection: $model.endDay, displayedComponents: [.date])
                .font(.system(size: 13))
                .datePickerStyle(.compact)
                .tint(Theme.accent)
            Stepper(value: $model.runsPerDay, in: 1...10) {
                HStack {
                    Text("每天条数")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.dim)
                    Spacer()
                    Text("\(model.runsPerDay) 条")
                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        .foregroundStyle(Theme.accent)
                }
            }
            HStack(spacing: 12) {
                hourStepper("起始小时", value: $model.windowStart, range: 0...23)
                hourStepper("结束小时", value: $model.windowEnd, range: 1...24)
            }
            HStack {
                Text("合计")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.dim)
                Spacer()
                Text("\(model.totalRuns) 条 / \(model.dayCount) 天 · 今日余量 \(quota.state().remaining) 条")
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(Theme.accent)
            }
        }
    }

    private func hourStepper(_ title: String, value: Binding<Int>, range: ClosedRange<Int>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 11))
                .foregroundStyle(Theme.faint)
            Stepper(value: value, in: range) {
                Text(String(format: "%02d:00", value.wrappedValue))
                    .font(.system(size: 13, weight: .medium, design: .monospaced))
                    .foregroundStyle(Theme.ink)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var templateCard: some View {
        Card {
            SectionLabel("单条模板")
            ValueSlider("时长（分钟）",
                        value: $model.durationMinutes,
                        range: 10...180,
                        step: 1,
                        display: String(format: "%.0f min", model.durationMinutes))
            ValueSlider("距离（km）",
                        value: $model.distanceKm,
                        range: 1...40,
                        step: 0.5,
                        display: String(format: "%.1f km", model.distanceKm))
            ValueSlider("静息心率（bpm）",
                        value: $model.restBpm,
                        range: 35...110,
                        step: 1,
                        display: "\(Int(model.restBpm)) bpm")
            ValueSlider("最高心率（bpm）",
                        value: $model.maxBpm,
                        range: 120...230,
                        step: 1,
                        display: "\(Int(model.maxBpm)) bpm")
            ValueSlider("最快 1 公里配速",
                        value: $model.paceMaxSeconds,
                        range: 150...600,
                        step: 5,
                        display: "\(Format.clock(model.paceMaxSeconds)) /km")
            Text(model.hrProfile.summary)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(Theme.accent)
            ValueSlider("体重（kg）",
                        value: $model.weightKg,
                        range: 40...120,
                        step: 0.5,
                        display: String(format: "%.1f kg", model.weightKg))
        }
    }

    private var weightCard: some View {
        Card {
            SectionLabel("活动类型权重")
            ForEach(ActivityKind.allCases) { kind in
                ValueSlider("\(kind.title)",
                            value: Binding(
                                get: { model.weights[kind] ?? 0 },
                                set: { model.weights[kind] = $0 }
                            ),
                            range: 0...1,
                            step: 0.05,
                            display: String(format: "%.2f", model.weights[kind] ?? 0))
            }
            Text("权重会归一化；全 0 时按等权处理。")
                .font(.system(size: 10))
                .foregroundStyle(Theme.faint)
        }
    }

    private var runCard: some View {
        Card {
            SectionLabel("执行")
            if model.isRunning {
                ProgressView(value: model.progress)
                    .tint(Theme.accent)
                Text("\(model.doneCount) 成功 / \(model.failCount) 失败 · 共 \(model.totalRuns) 条")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Theme.dim)
                GhostButton("中止", systemImage: "stop.fill", tint: Theme.danger) {
                    model.cancel()
                }
            } else {
                PrimaryButton("开始批量生成并写入", systemImage: "play.fill") {
                    Task { await model.run(settings: settings, quota: quota) }
                }
                Text("按日期范围与时间窗随机排点，逐条生成并写入健康；每天上限 \(QuotaStore.dailyLimit) 条。")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.faint)
            }

            if !model.log.isEmpty {
                Divider().overlay(Theme.line)
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(model.log.suffix(8).enumerated()), id: \.offset) { item in
                        Text(item.element)
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(item.element.hasPrefix("✗") ? Theme.danger : Theme.dim)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            if let error = model.errorText {
                Text(error)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.danger)
            }
        }
    }
}

// MARK: - 批量模型

public final class BatchModel: ObservableObject {

    @Published public var startDay: Date = Date()
    @Published public var endDay: Date = Date()
    @Published public var runsPerDay: Int = 2
    @Published public var windowStart: Int = 7
    @Published public var windowEnd: Int = 21
    @Published public var weights: [ActivityKind: Double] = [
        .outdoorRun: 0.4, .indoorRun: 0.25, .outdoorWalk: 0.2, .outdoorRide: 0.15
    ]
    @Published public var durationMinutes: Double = 35
    @Published public var distanceKm: Double = 6
    /// Karvonen 心率参数（静息 / 最高 / 最快 1 公里配速，秒）。
    @Published public var restBpm: Double = 60
    @Published public var maxBpm: Double = 190
    @Published public var paceMaxSeconds: Double = 240
    @Published public var cadence: Double = 172
    @Published public var weightKg: Double = 62
    @Published public var routeMode: RouteMode = .track

    @Published public var isRunning = false
    @Published public var progress: Double = 0
    @Published public var doneCount = 0
    @Published public var failCount = 0
    @Published public var log: [String] = []
    @Published public var errorText: String?

    private var cancelRequested = false
    private var defaultsApplied = false

    public init() {}

    public var plan: BatchPlan {
        BatchPlan(startDay: startDay,
                  endDay: endDay,
                  runsPerDay: runsPerDay,
                  windowStartHour: windowStart,
                  windowEndHour: windowEnd,
                  weights: weights,
                  template: RunPlan(kind: .outdoorRun,
                                    durationMinutes: durationMinutes,
                                    distanceKm: distanceKm,
                                    hrProfile: hrProfile,
                                    cadence: Int(cadence),
                                    weightKg: weightKg,
                                    routeMode: routeMode))
    }

    /// 当前三项心率参数（已按 Java sanitize 规则收敛）。
    public var hrProfile: HrProfile {
        HrProfile(restBpm: Int(restBpm.rounded()),
                  maxBpm: Int(maxBpm.rounded()),
                  paceMaxSec: Int(paceMaxSeconds.rounded())).sanitized()
    }

    public var dayCount: Int { plan.days().count }
    public var totalRuns: Int { plan.totalRuns }

    public func applyDefaults(_ settings: AppSettings) {
        guard !defaultsApplied else { return }
        defaultsApplied = true
        weightKg = settings.defaultWeightKg
        durationMinutes = settings.defaultDurationMinutes
        distanceKm = settings.defaultDistanceKm
        cadence = Double(settings.defaultCadence)
        restBpm = Double(settings.restBpm)
        maxBpm = Double(settings.maxBpm)
        paceMaxSeconds = Double(settings.paceMaxSec)
    }

    public func cancel() {
        cancelRequested = true
        log.append("· 收到中止请求，当前这条写完后停下")
    }

    // MARK: 执行

    public func run(settings: AppSettings, quota: QuotaStore) async {
        guard !isRunning else { return }
        cancelRequested = false
        isRunning = true
        doneCount = 0
        failCount = 0
        progress = 0
        errorText = nil
        log = []

        let plan = self.plan
        let days = plan.days()
        let total = max(days.count * runsPerDay, 1)
        let options = HealthWriteOptions(includeHeartRate: settings.writeHeartRate,
                                         includeSteps: settings.writeSteps,
                                         includeRoute: settings.writeRoute)

        quota.refresh()
        log.append("· 计划 \(days.count) 天 × \(runsPerDay) 条 = \(total) 条")

        var stoppedByQuota = false

        outer: for day in days {
            for index in 0..<runsPerDay {
                if cancelRequested { break outer }
                quota.refresh()
                guard quota.canGenerate() else {
                    stoppedByQuota = true
                    errorText = "今日已生成 \(quota.used) 条，达到上限 \(QuotaStore.dailyLimit) 条/天，批量停止。"
                    log.append("✗ 今日上限已满，停止")
                    break outer
                }

                let kind = pickKind()
                let start = randomStart(on: day, sequence: index)
                var item = RunPlan(kind: kind,
                                   startDate: start,
                                   durationMinutes: durationMinutes,
                                   distanceKm: distanceKm,
                                   hrProfile: hrProfile,
                                   cadence: Int(cadence),
                                   weightKg: weightKg,
                                   routeMode: kind.supportsRoute ? routeMode : .none)
                if kind == .indoorRun { item.routeMode = .none }

                let result: RunResult
                do {
                    result = try Generator.generate(item)
                } catch {
                    failCount += 1
                    log.append("✗ \(Format.dateTime(item.startDate)) \(item.kind.title) \(error.localizedDescription)")
                    progress = min(Double(doneCount + failCount) / Double(total), 1)
                    continue
                }
                do {
                    let report = try await HealthWriter.shared.write(result, options: options)
                    quota.record()
                    doneCount += 1
                    HistoryStore.shared.add(HistoryEntry(startDate: result.startDate,
                                                         kind: result.kind,
                                                         distanceMeters: result.distanceMeters,
                                                         durationSeconds: result.durationSeconds,
                                                         averageHeartRate: result.averageHeartRate,
                                                         maxHeartRate: result.maxHeartRate,
                                                         energyKcal: result.activeEnergyKcal,
                                                         steps: result.steps,
                                                         wroteToHealth: true,
                                                         message: report.warnings.joined(separator: "；")))
                    log.append("✓ \(Format.dateTime(result.startDate)) \(result.kind.title) \(String(format: "%.1f", result.distanceKm))km · 样本 \(report.sampleCount)" + (report.routeSaved ? " · 路线 \(report.routePointCount) 点" : ""))
                } catch {
                    failCount += 1
                    HistoryStore.shared.add(HistoryEntry(startDate: result.startDate,
                                                         kind: result.kind,
                                                         distanceMeters: result.distanceMeters,
                                                         durationSeconds: result.durationSeconds,
                                                         averageHeartRate: result.averageHeartRate,
                                                         maxHeartRate: result.maxHeartRate,
                                                         energyKcal: result.activeEnergyKcal,
                                                         steps: result.steps,
                                                         wroteToHealth: false,
                                                         message: error.localizedDescription))
                    log.append("✗ \(Format.dateTime(result.startDate)) \(result.kind.title) 写入失败：\(error.localizedDescription)")
                }

                progress = min(Double(doneCount + failCount) / Double(total), 1)
            }
        }

        if cancelRequested {
            log.append("· 已中止：成功 \(doneCount) 条 / 失败 \(failCount) 条")
        } else if !stoppedByQuota {
            log.append("· 完成：成功 \(doneCount) 条 / 失败 \(failCount) 条")
        }
        progress = doneCount + failCount >= total ? 1 : progress
        isRunning = false
    }

    // MARK: 随机

    private func pickKind() -> ActivityKind {
        let pairs = plan.normalizedWeights
        let roll = Double.random(in: 0..<1)
        var cumulative = 0.0
        for (kind, weight) in pairs {
            cumulative += weight
            if roll < cumulative { return kind }
        }
        return pairs.last?.0 ?? .outdoorRun
    }

    private func randomStart(on day: Date, sequence: Int) -> Date {
        let calendar = Calendar.current
        let dayStart = calendar.startOfDay(for: day)
        let low = min(windowStart, windowEnd - 1)
        let high = max(windowEnd, low + 1)
        let hour = Int.random(in: low..<high)
        let minute = Int.random(in: 0..<60)
        let base = calendar.date(byAdding: .minute, value: hour * 60 + minute, to: dayStart) ?? dayStart
        // 同一小时内多条时再错开一点，避免完全重合。
        return base.addingTimeInterval(Double(sequence) * 7 * 60)
    }
}