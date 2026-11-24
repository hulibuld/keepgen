import SwiftUI
import KeepGenCore

// MARK: - 生成页

public struct GenerateView: View {

    @ObservedObject var settings: AppSettings
    @ObservedObject var quota: QuotaStore
    @ObservedObject private var library = RouteLibrary.shared
    @StateObject private var model = GenerateModel()
    @State private var showHistory = false

    public init(settings: AppSettings, quota: QuotaStore) {
        self.settings = settings
        self.quota = quota
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                activityCard
                timingCard
                bodyCard
                routeCard
                previewCard
                exportCard
                actionCard
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 20)
        }
        .task {
            model.applyDefaults(settings)
            quota.refresh()
            if model.preview == nil { model.refreshPreview() }
        }
        .sheet(isPresented: $showHistory) {
            HistoryView()
        }
    }

    // MARK: 活动

    private var activityCard: some View {
        Card {
            SectionLabel("活动类型")
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                ForEach(ActivityKind.allCases) { kind in
                    Button {
                        model.kind = kind
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: kind.symbol)
                                .font(.system(size: 16, weight: .medium))
                                .frame(width: 22)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(kind.title)
                                    .font(.system(size: 13, weight: .semibold))
                                Text(kind.subtitle)
                                    .font(.system(size: 10))
                                    .foregroundStyle(model.kind == kind ? Theme.accentInk.opacity(0.7) : Theme.faint)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 9)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .foregroundStyle(model.kind == kind ? Theme.accentInk : Theme.ink)
                        .background(model.kind == kind ? Theme.accent : Theme.surface2)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: 时间与距离

    private var timingCard: some View {
        Card {
            SectionLabel("时间 · 时长 · 距离")
            DatePicker("开始时间",
                       selection: $model.startDate,
                       displayedComponents: [.date, .hourAndMinute])
                .font(.system(size: 13))
                .foregroundStyle(Theme.dim)
                .datePickerStyle(.compact)
                .tint(Theme.accent)

            ValueSlider("时长（分钟）",
                        value: $model.durationMinutes,
                        range: 5...300,
                        step: 1,
                        display: String(format: "%.0f min", model.durationMinutes))

            ValueSlider("距离（\(settings.units.distanceSuffix)）",
                        value: $model.distanceKm,
                        range: 0.5...80,
                        step: 0.1,
                        display: String(format: "%.2f %@", model.distanceKm * settings.units.distanceFactor, settings.units.distanceSuffix))

            HStack {
                Text("平均\(model.kind.showsPace ? "配速" : "时速")")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.dim)
                Spacer()
                Text(model.kind.showsPace
                     ? "\(Format.pace(model.plan.averagePaceSecondsPerKm)) /km"
                     : "\(Format.speed(model.plan.speedKmh, units: .metric)) km/h")
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Theme.accent)
            }
            Text("配速由「时长 ÷ 距离」推得，不需要手填。")
                .font(.system(size: 10))
                .foregroundStyle(Theme.faint)
            if let warning = model.paceWarning {
                Text(warning)
                    .font(.system(size: 10))
                    .foregroundStyle(Theme.warn)
            }
        }
    }

    // MARK: 心率与身体

    private var bodyCard: some View {
        Card {
            SectionLabel("心率（Karvonen 心率储备）· 步频 · 体重")
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
            if model.kind.countsSteps {
                ValueSlider("步频（步/分钟）",
                            value: $model.cadence,
                            range: 120...220,
                            step: 1,
                            display: "\(Int(model.cadence)) spm")
            }
            ValueSlider("体重（kg，用于算卡路里）",
                        value: $model.weightKg,
                        range: 40...120,
                        step: 0.5,
                        display: String(format: "%.1f kg", model.weightKg))
        }
    }

    // MARK: 路线

    private var routeCard: some View {
        Card {
            SectionLabel("路线")
            if !model.kind.supportsRoute {
                Text("室内跑步不带路线；其余三种活动可选田径场 / 粘贴坐标 / 路线库。")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.faint)
            } else {
                Picker("路线来源", selection: $model.routeMode) {
                    ForEach(RouteMode.allCases) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()

                switch model.routeMode {
                case .track:
                    trackEditor
                case .paste:
                    pasteEditor
                case .library:
                    libraryEditor
                case .none:
                    Text("本次不写路线。")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.faint)
                }
            }
        }
    }

    private var trackEditor: some View {
        VStack(spacing: 10) {
            ValueSlider("跑道长度（米）",
                        value: $model.trackLaneMeters,
                        range: 100...500,
                        step: 10,
                        display: String(format: "%.0f m", model.trackLaneMeters))
            HStack(spacing: 10) {
                coordinateField("纬度", text: $model.trackLatitudeText)
                coordinateField("经度", text: $model.trackLongitudeText)
            }
            ValueSlider("方位角（度）",
                        value: $model.trackHeading,
                        range: 0...359,
                        step: 1,
                        display: String(format: "%.0f°", model.trackHeading))
            HStack {
                Text("圈数")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.dim)
                Spacer()
                Text(String(format: "%.2f 圈 / %.0f 点", model.plan.distanceMeters / max(model.trackLaneMeters, 50),
                            Double(max(Int(model.plan.distanceMeters / 5) + 1, 2))))
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(Theme.accent)
            }
            HStack(spacing: 10) {
                Text("轨迹种子")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.dim)
                Spacer()
                Text("\(Int(model.seed))")
                    .font(.system(size: 12, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Theme.accent)
                GhostButton("换一条", systemImage: "dice") {
                    model.shuffleSeed()
                }
                .frame(width: 100)
            }
            Text("同参数换种子会得到形状不同的环线（换道漂移 / 摆动 / 圈形都不一样）。")
                .font(.system(size: 10))
                .foregroundStyle(Theme.faint)
            RouteSketch(points: Generator.trackPoints(config: model.trackConfig,
                                                      distanceMeters: model.plan.distanceMeters,
                                                      seed: Int(model.seed.rounded())),
                        height: 140)
        }
    }

    private var pasteEditor: some View {
        VStack(spacing: 8) {
            TextEditor(text: $model.pastedText)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Theme.ink)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 120, maxHeight: 160)
                .padding(8)
                .background(Theme.surface2)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(Theme.line, lineWidth: 1)
                )
            HStack(spacing: 10) {
                GhostButton("解析坐标", systemImage: "text.magnifyingglass") {
                    model.parsePastedCoordinates()
                }
                Text(model.pasteStatus)
                    .font(.system(size: 11))
                    .foregroundStyle(model.parsedPoints.count >= 2 ? Theme.ok : Theme.faint)
            }
            Text("一行一个 `lat,lng`，如 `30.2687,120.1551`。")
                .font(.system(size: 10))
                .foregroundStyle(Theme.faint)
        }
    }

    private var libraryEditor: some View {
        VStack(spacing: 10) {
            if library.routes.isEmpty {
                Text("路线库还是空的，去「路线」页保存一条。")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.faint)
            } else {
                Picker("已保存路线", selection: $model.selectedRouteID) {
                    Text("未选择").tag(UUID?.none)
                    ForEach(library.routes) { route in
                        Text("\(route.name) · \(route.pointCount) 点").tag(UUID?.some(route.id))
                    }
                }
                .pickerStyle(.menu)
                .tint(Theme.accent)

                if let route = library.route(id: model.selectedRouteID) {
                    RouteSketch(points: route.points, height: 130)
                    KVRow("路线长度", "\(route.distanceKmText) km")
                }
            }
        }
    }

    private func coordinateField(_ title: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 11))
                .foregroundStyle(Theme.faint)
            TextField(title, text: text)
                .font(.system(size: 13, design: .monospaced))
                .textFieldStyle(.plain)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(Theme.surface2)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
    }

    // MARK: 预览

    private var previewCard: some View {
        Card {
            HStack {
                SectionLabel("预览")
                Spacer()
                Button {
                    model.refreshPreview()
                } label: {
                    Text("重新生成")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(Theme.accent)
                }
                .buttonStyle(.plain)
            }

            if let result = model.preview {
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                    StatTile("距离", Format.distance(result.distanceMeters, units: settings.units), unit: settings.units.distanceSuffix)
                    StatTile("时长", Format.duration(result.durationSeconds), unit: "h:m:s")
                    StatTile(result.kind.showsPace ? "平均配速" : "平均时速",
                             result.kind.showsPace ? Format.pace(result.averagePaceSecondsPerKm) : Format.speed(result.speedKmh, units: settings.units),
                             unit: result.kind.showsPace ? "/km" : "km/h")
                    StatTile("卡路里", String(format: "%.0f", result.activeEnergyKcal), unit: "kcal")
                    StatTile("平均心率", "\(result.averageHeartRate)", unit: "bpm")
                    StatTile("步数", result.kind.countsSteps ? "\(result.steps)" : "—", unit: result.kind.countsSteps ? "步" : "")
                }

                if !result.splits.isEmpty {
                    Divider().overlay(Theme.line)
                    Text("每公里分段")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Theme.dim)
                    ForEach(result.splits.prefix(6)) { split in
                        HStack {
                            Text("第 \(split.index) km")
                                .font(.system(size: 12))
                                .foregroundStyle(Theme.faint)
                            Spacer()
                            Text(Format.pace(split.paceSecondsPerKm))
                                .font(.system(size: 12, weight: .medium, design: .monospaced))
                                .foregroundStyle(Theme.ink)
                        }
                    }
                    if result.splits.count > 6 {
                        Text("还有 \(result.splits.count - 6) 段")
                            .font(.system(size: 10))
                            .foregroundStyle(Theme.faint)
                    }
                }

                if result.hasRoute {
                    KVRow("轨迹点", "\(result.route.count) 点 · 心率 \(result.heartRates.count) 个采样")
                } else {
                    KVRow("轨迹点", "无（不写路线）")
                }

                KVRow("心率模型", model.hrProfile.summary)
                KVRow("该配速稳态心率", "\(model.hrProfile.steadyBpm(paceSecondsPerKm: result.averagePaceSecondsPerKm)) bpm")
                if let low = result.heartRates.map(\.bpm).min(), let top = result.heartRates.map(\.bpm).max() {
                    KVRow("心率区间", "\(low) – \(top) bpm")
                }
            } else {
                Text(model.previewError ?? "还没有预览，点「重新生成」看一条。")
                    .font(.system(size: 12))
                    .foregroundStyle(model.previewError == nil ? Theme.faint : Theme.danger)
            }
        }
    }

    // MARK: 动作

    private var exportCard: some View {
        Card {
            SectionLabel("导出文件")
            HStack(spacing: 10) {
                GhostButton("生成导出文件", systemImage: "doc.badge.plus", tint: Theme.accent) {
                    model.prepareExports()
                }
            }
            if model.exports.isEmpty {
                Text("TCX / FIT / GPX 三份，纯本地拼装；分享面板里选「存储到文件」即可存进「文件」App。")
                    .font(.system(size: 10))
                    .foregroundStyle(Theme.faint)
            } else {
                ForEach(model.exports) { file in
                    ShareLink(item: file.url) {
                        HStack(spacing: 8) {
                            Image(systemName: "square.and.arrow.up")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(Theme.accent)
                            Text(file.name)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(Theme.ink)
                                .lineLimit(1)
                                .truncationMode(.middle)
                            Spacer(minLength: 6)
                            Text("\(file.byteCount) B")
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundStyle(Theme.faint)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .background(Theme.surface2)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
            if let error = model.exportError {
                Text(error)
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.danger)
            }
        }
    }

    private var actionCard: some View {
        Card {
            PrimaryButton(model.isWriting ? "正在写入健康…" : "生成并写入「健康」",
                          systemImage: "heart.text.square",
                          isEnabled: !model.isWriting) {
                Task { await model.write(settings: settings, quota: quota) }
            }
            if !HealthWriter.shared.isAvailable {
                Text("这台设备没有健康数据源，写入会失败；代码路径不变。")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.warn)
            }
            if let status = model.statusText {
                Text(status)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.ok)
            }
            if let error = model.errorText {
                Text(error)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.danger)
            }
            HStack(spacing: 10) {
                GhostButton("历史记录", systemImage: "clock.arrow.circlepath") {
                    showHistory = true
                }
                GhostButton("清空预览", systemImage: "arrow.counterclockwise") {
                    model.preview = nil
                }
            }
        }
    }
}

// MARK: - 生成页模型

public final class GenerateModel: ObservableObject {

    @Published public var kind: ActivityKind = .outdoorRun {
        didSet {
            if !kind.supportsRoute { routeMode = .none }
            else if routeMode == .none { routeMode = .track }
            refreshPreview()
        }
    }

    @Published public var startDate: Date = Date()
    @Published public var durationMinutes: Double = 35
    @Published public var distanceKm: Double = 6
    /// Karvonen 心率参数：静息 / 最高 / 最快 1 公里配速（秒）。
    @Published public var restBpm: Double = 60
    @Published public var maxBpm: Double = 190
    @Published public var paceMaxSeconds: Double = 240
    @Published public var cadence: Double = 172
    @Published public var weightKg: Double = 62

    @Published public var routeMode: RouteMode = .track
    @Published public var trackLaneMeters: Double = 400
    @Published public var seed: Double = 7
    @Published public var trackHeading: Double = 0
    @Published public var trackLatitudeText: String = "30.2687"
    @Published public var trackLongitudeText: String = "120.1551"
    @Published public var pastedText: String = ""
    @Published public var parsedPoints: [RoutePoint] = []
    @Published public var pasteStatus: String = ""
    @Published public var selectedRouteID: UUID?

    @Published public var preview: RunResult?
    @Published public var previewError: String?
    @Published public var exports: [ExportFile] = []
    @Published public var exportError: String?
    @Published public var isWriting = false
    @Published public var statusText: String?
    @Published public var errorText: String?

    private var defaultsApplied = false

    public init() {}

    // MARK: 参数

    public var trackConfig: TrackConfig {
        TrackConfig(laneLengthMeters: trackLaneMeters,
                    centerLatitude: Double(trackLatitudeText.trimmingCharacters(in: .whitespaces)) ?? 30.2687,
                    centerLongitude: Double(trackLongitudeText.trimmingCharacters(in: .whitespaces)) ?? 120.1551,
                    headingDegrees: trackHeading)
    }

    public func applyDefaults(_ settings: AppSettings) {
        guard !defaultsApplied else { return }
        defaultsApplied = true
        weightKg = settings.defaultWeightKg
        cadence = Double(settings.defaultCadence)
        durationMinutes = settings.defaultDurationMinutes
        distanceKm = settings.defaultDistanceKm
        restBpm = Double(settings.restBpm)
        maxBpm = Double(settings.maxBpm)
        paceMaxSeconds = Double(settings.paceMaxSec)
    }

    public func makePlan() -> RunPlan {
        RunPlan(kind: kind,
                startDate: startDate,
                durationMinutes: durationMinutes,
                distanceKm: distanceKm,
                hrProfile: hrProfile,
                cadence: Int(cadence.rounded()),
                weightKg: weightKg,
                routeMode: kind.supportsRoute ? routeMode : .none,
                track: trackConfig,
                pastedCoordinates: pastedText,
                routePoints: resolvedPoints(),
                seed: Int(seed.rounded()))
    }

    public var plan: RunPlan { makePlan() }

    /// 当前三项心率参数（已按 Java sanitize 规则收敛）。
    public var hrProfile: HrProfile {
        HrProfile(restBpm: Int(restBpm.rounded()),
                  maxBpm: Int(maxBpm.rounded()),
                  paceMaxSec: Int(paceMaxSeconds.rounded())).sanitized()
    }

    /// 参数不合规时的中文提示（同 Java 的校验口径）。
    public var paceWarning: String? {
        do {
            try Generator.validate(makePlan())
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    private func resolvedPoints() -> [RoutePoint] {
        switch routeMode {
        case .track:
            return []
        case .paste:
            return parsedPoints.isEmpty ? Generator.parseCoordinates(pastedText) : parsedPoints
        case .library:
            return RouteLibrary.shared.route(id: selectedRouteID)?.points ?? []
        case .none:
            return []
        }
    }

    public func parsePastedCoordinates() {
        let points = Generator.parseCoordinates(pastedText)
        parsedPoints = points
        pasteStatus = points.count >= 2 ? "解析到 \(points.count) 个点" : "至少需要两个合法点"
        refreshPreview()
    }

    /// 换一条轨迹（种子 +1，回到 1…9999 循环）。
    public func shuffleSeed() {
        seed = Double((Int(seed.rounded()) % 9_999) + 1)
        refreshPreview()
    }

    /// 生成 TCX / FIT / GPX 三个文件，交给 ShareLink 分享或存到「文件」。
    public func prepareExports() {
        exportError = nil
        let result: RunResult
        do {
            result = try preview ?? Generator.generate(makePlan())
        } catch {
            exportError = error.localizedDescription
            return
        }
        do {
            exports = try ExportFormat.allCases.map { try Exporters.write(result, format: $0) }
        } catch {
            exports = []
            exportError = "写导出文件失败：\(error.localizedDescription)"
        }
    }

    public func refreshPreview() {
        do {
            preview = try Generator.generate(makePlan())
            previewError = nil
        } catch {
            preview = nil
            previewError = error.localizedDescription
        }
    }

    // MARK: 写入

    public func write(settings: AppSettings, quota: QuotaStore) async {
        guard !isWriting else { return }
        statusText = nil
        errorText = nil

        // 未激活：界面能看、参数能调，只在真正写入健康这一步拦下来（同 Android 版）。
        if let denial = LicenseStore.shared.denialMessage() {
            errorText = denial
            return
        }

        quota.refresh()
        guard quota.canGenerate() else {
            errorText = "今日已生成 \(quota.used) 条，本地上限 \(QuotaStore.dailyLimit) 条/天（按自然日重置）。"
            return
        }

        let plan = makePlan()
        do {
            try Generator.validate(plan)
        } catch {
            errorText = error.localizedDescription
            return
        }
        if plan.routeMode == .paste, plan.routePoints.count < 2 {
            errorText = "粘贴的坐标不足两个点，或者格式不是 lat,lng。"
            return
        }
        if plan.routeMode == .library, plan.routePoints.isEmpty {
            errorText = "路线库还没选一条路线。"
            return
        }

        isWriting = true
        let result: RunResult
        do {
            result = try Generator.generate(plan)
        } catch {
            errorText = error.localizedDescription
            isWriting = false
            return
        }
        preview = result

        let options = HealthWriteOptions(includeHeartRate: settings.writeHeartRate,
                                         includeSteps: settings.writeSteps,
                                         includeRoute: settings.writeRoute && plan.kind.supportsRoute)

        do {
            let report = try await HealthWriter.shared.write(result, options: options)
            quota.record()
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
            var text = "已写入健康：训练 1 条 · 样本 \(report.sampleCount) 条（心率 \(report.heartRateSamples)）"
            if report.routeSaved { text += " · 路线 \(report.routePointCount) 点" }
            statusText = text
            if !report.warnings.isEmpty {
                statusText = text + "\n注意：" + report.warnings.joined(separator: "；")
            }
        } catch {
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
            errorText = error.localizedDescription
        }

        isWriting = false
    }
}