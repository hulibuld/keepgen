import Foundation

// MARK: - 导出格式

public enum ExportFormat: String, CaseIterable, Identifiable, Hashable, Sendable {
    case tcx
    case fit
    case gpx

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .tcx: return "TCX"
        case .fit: return "FIT"
        case .gpx: return "GPX"
        }
    }

    public var fileExtension: String { rawValue }

    public var hint: String {
        switch self {
        case .tcx: return "Garmin / Strava / Keep 都吃"
        case .fit: return "Keep 导入首选，带心率与分圈"
        case .gpx: return "只含路线，华为 / 小米运动健康可导入"
        }
    }
}

/// 导出时用的逐点数据：有轨迹带经纬度，室内跑步只有时间 / 距离 / 心率。
public struct ExportPoint: Hashable, Sendable {
    public var date: Date
    public var latitude: Double?
    public var longitude: Double?
    public var altitude: Double
    public var distanceMeters: Double
    public var speed: Double
    public var heartRate: Int?
    public var cadence: Int?

    public init(date: Date,
                latitude: Double?,
                longitude: Double?,
                altitude: Double,
                distanceMeters: Double,
                speed: Double,
                heartRate: Int?,
                cadence: Int?) {
        self.date = date
        self.latitude = latitude
        self.longitude = longitude
        self.altitude = altitude
        self.distanceMeters = distanceMeters
        self.speed = speed
        self.heartRate = heartRate
        self.cadence = cadence
    }
}

public struct ExportFile: Identifiable, Hashable, Sendable {
    public var id: String { name }
    public var name: String
    public var url: URL
    public var format: ExportFormat
    public var byteCount: Int

    public init(name: String, url: URL, format: ExportFormat, byteCount: Int) {
        self.name = name
        self.url = url
        self.format = format
        self.byteCount = byteCount
    }
}

// MARK: - 导出器

/// 纯本地拼 XML / 二进制，不联网。字段口径对齐 Android 的 `Fit.java` / `GpxOut.java` / TCX 构建段。
public enum Exporters {

    public static let degreePerSemicircle = 180.0 / 2_147_483_648.0

    // MARK: 逐点数据

    public static func points(_ result: RunResult) -> [ExportPoint] {
        let cadence = result.kind.countsSteps ? max(result.cadence, 0) : nil
        let hrs = result.heartRates

        func heartRate(at date: Date) -> Int? {
            guard !hrs.isEmpty else { return nil }
            var lo = 0
            var hi = hrs.count - 1
            while hi - lo > 1 {
                let mid = (lo + hi) / 2
                if hrs[mid].date <= date { lo = mid } else { hi = mid }
            }
            let a = hrs[lo], b = hrs[hi]
            return abs(a.date.timeIntervalSince(date)) <= abs(b.date.timeIntervalSince(date)) ? a.bpm : b.bpm
        }

        if result.route.count >= 2 {
            var cumulative: [Double] = [0]
            cumulative.reserveCapacity(result.route.count)
            for i in 1..<result.route.count {
                let a = RoutePoint(latitude: result.route[i - 1].latitude, longitude: result.route[i - 1].longitude)
                let b = RoutePoint(latitude: result.route[i].latitude, longitude: result.route[i].longitude)
                cumulative.append(cumulative[i - 1] + Geo.distanceMeters(from: a, to: b))
            }
            let total = cumulative.last ?? 0
            let scale = total > 0 ? result.distanceMeters / total : 1
            return result.route.enumerated().map { index, sample in
                ExportPoint(date: sample.date,
                            latitude: sample.latitude,
                            longitude: sample.longitude,
                            altitude: sample.altitude,
                            distanceMeters: cumulative[index] * scale,
                            speed: sample.speed,
                            heartRate: heartRate(at: sample.date),
                            cadence: cadence)
            }
        }

        // 无轨迹（室内跑步）：按 10 秒网格推，只有时间 / 距离 / 心率
        let interval = Generator.heartRateInterval
        var points: [ExportPoint] = []
        var t = 0.0
        while t < result.durationSeconds - 0.001 {
            let ratio = result.durationSeconds > 0 ? t / result.durationSeconds : 0
            points.append(ExportPoint(date: result.startDate.addingTimeInterval(t),
                                      latitude: nil,
                                      longitude: nil,
                                      altitude: 0,
                                      distanceMeters: result.distanceMeters * ratio,
                                      speed: result.durationSeconds > 0 ? result.distanceMeters / result.durationSeconds : 0,
                                      heartRate: heartRate(at: result.startDate.addingTimeInterval(t)),
                                      cadence: cadence))
            t += interval
        }
        points.append(ExportPoint(date: result.endDate,
                                  latitude: nil,
                                  longitude: nil,
                                  altitude: 0,
                                  distanceMeters: result.distanceMeters,
                                  speed: result.durationSeconds > 0 ? result.distanceMeters / result.durationSeconds : 0,
                                  heartRate: heartRate(at: result.endDate),
                                  cadence: cadence))
        return points
    }

    // MARK: 文件名与落盘

    public static func fileName(_ result: RunResult, format: ExportFormat) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd-HHmm"
        return "keepgen-\(f.string(from: result.startDate))-\(result.kind.sportKey).\(format.fileExtension)"
    }

    public static func data(_ result: RunResult, format: ExportFormat) -> Data {
        switch format {
        case .tcx: return tcxData(result)
        case .fit: return fitData(result)
        case .gpx: return gpxData(result)
        }
    }

    /// 写到临时目录，返回 URL 给分享 / 存到「文件」用。
    public static func write(_ result: RunResult, format: ExportFormat, directory: URL? = nil) throws -> ExportFile {
        let dir = directory ?? FileManager.default.temporaryDirectory
        let name = fileName(result, format: format)
        let url = dir.appendingPathComponent(name)
        let payload = data(result, format: format)
        try payload.write(to: url, options: .atomic)
        return ExportFile(name: name, url: url, format: format, byteCount: payload.count)
    }

    // MARK: 时间与文本工具

    private static func iso(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        return f.string(from: date)
    }

    private static func xmlEscaped(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
    }

    private static func fixed(_ value: Double, _ digits: Int) -> String {
        String(format: "%.\(digits)f", locale: Locale(identifier: "en_US_POSIX"), value.isFinite ? value : 0)
    }

    private static func tcxSportName(_ kind: ActivityKind) -> String {
        switch kind {
        case .outdoorRun, .indoorRun: return "Running"
        case .outdoorWalk: return "Walking"
        case .outdoorRide: return "Biking"
        }
    }

    private static func gpxType(_ kind: ActivityKind) -> String {
        switch kind {
        case .outdoorRun, .indoorRun: return "running"
        case .outdoorWalk: return "walking"
        case .outdoorRide: return "cycling"
        }
    }

    // MARK: TCX

    public static func tcxData(_ result: RunResult) -> Data {
        let pts = points(result)
        let sport = tcxSportName(result.kind)
        let startISO = iso(result.startDate)
        var xml = ""
        xml.reserveCapacity(pts.count * 300 + 4_000)
        xml += "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        xml += "<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\""
        xml += " xmlns:ns3=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\">"
        xml += "<Activities><Activity Sport=\"\(sport)\">"
        xml += "<Id>\(startISO)</Id>"
        xml += "<Lap StartTime=\"\(startISO)\">"
        xml += "<TotalTimeSeconds>\(fixed(result.durationSeconds, 1))</TotalTimeSeconds>"
        xml += "<DistanceMeters>\(fixed(result.distanceMeters, 1))</DistanceMeters>"
        xml += "<MaximumSpeed>\(fixed(result.speedKmh / 3.6, 2))</MaximumSpeed>"
        xml += "<Calories>\(Int(result.activeEnergyKcal.rounded()))</Calories>"
        xml += "<Intensity>Active</Intensity>"
        if result.kind.countsSteps, result.cadence > 0 {
            // Garmin 规范：Lap 级 Cadence 放半步频
            xml += "<Cadence>\(Int((Double(result.cadence) / 2).rounded()))</Cadence>"
        }
        xml += "<TriggerMethod>Manual</TriggerMethod><Track>"

        for p in pts {
            xml += "<Trackpoint><Time>\(iso(p.date))</Time>"
            if let lat = p.latitude, let lon = p.longitude {
                xml += "<Position><LatitudeDegrees>\(fixed(lat, 8))</LatitudeDegrees>"
                xml += "<LongitudeDegrees>\(fixed(lon, 8))</LongitudeDegrees></Position>"
            }
            xml += "<DistanceMeters>\(fixed(p.distanceMeters, 1))</DistanceMeters>"
            if let hr = p.heartRate {
                xml += "<HeartRateBpm><Value>\(hr)</Value></HeartRateBpm>"
            }
            if let cad = p.cadence {
                xml += "<Cadence>\(cad)</Cadence>"
                xml += "<Extensions><ns3:TPX><ns3:RunCadence>\(Int((Double(cad) / 2).rounded()))</ns3:RunCadence></ns3:TPX></Extensions>"
            }
            xml += "</Trackpoint>"
        }
        xml += "</Track>"

        if result.kind.countsSteps {
            xml += "<Extensions><ns3:LX>"
            if result.cadence > 0 {
                xml += "<ns3:AvgRunCadence>\(Int((Double(result.cadence) / 2).rounded()))</ns3:AvgRunCadence>"
                xml += "<ns3:MaxRunCadence>\(Int((Double(result.cadence) / 2).rounded()))</ns3:MaxRunCadence>"
            }
            if result.steps > 0 {
                xml += "<ns3:Steps>\(min(65_535, result.steps))</ns3:Steps>"
            }
            xml += "</ns3:LX></Extensions>"
        }
        xml += "</Lap>"

        // 每公里分段（Android 只写进 FIT，这里按需求也落 TCX）
        var elapsed = 0.0
        for split in result.splits {
            let lapStart = result.startDate.addingTimeInterval(elapsed)
            elapsed += split.durationSeconds
            xml += "<Lap StartTime=\"\(iso(lapStart))\">"
            xml += "<TotalTimeSeconds>\(fixed(split.durationSeconds, 1))</TotalTimeSeconds>"
            xml += "<DistanceMeters>\(fixed(split.distanceMeters, 1))</DistanceMeters>"
            xml += "<Calories>\(Int((result.activeEnergyKcal * split.distanceMeters / max(result.distanceMeters, 1)).rounded()))</Calories>"
            xml += "<Intensity>Active</Intensity><TriggerMethod>Manual</TriggerMethod>"
            xml += "<Track><Trackpoint><Time>\(iso(lapStart))</Time>"
            xml += "<DistanceMeters>\(fixed(split.distanceMeters, 1))</DistanceMeters>"
            xml += "</Trackpoint></Track></Lap>"
        }

        xml += "</Activity></Activities></TrainingCenterDatabase>"
        return Data(xml.utf8)
    }

    // MARK: GPX

    public static func gpxData(_ result: RunResult) -> Data {
        let pts = points(result).filter { $0.latitude != nil && $0.longitude != nil }
        var xml = ""
        xml.reserveCapacity(pts.count * 180 + 1_500)
        xml += "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        xml += "<gpx version=\"1.1\" creator=\"keepgen\""
        xml += " xmlns=\"http://www.topografix.com/GPX/1/1\""
        xml += " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
        xml += " xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\""
        xml += " xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n"
        xml += "  <metadata><time>\(iso(result.startDate))</time></metadata>\n"
        xml += "  <trk><name>\(xmlEscaped(fileName(result, format: .gpx).replacingOccurrences(of: ".gpx", with: "")))</name>"
        xml += "<type>\(gpxType(result.kind))</type><trkseg>\n"
        for p in pts {
            guard let lat = p.latitude, let lon = p.longitude else { continue }
            xml += "    <trkpt lat=\"\(fixed(lat, 6))\" lon=\"\(fixed(lon, 6))\">"
            xml += "<ele>\(fixed(p.altitude, 1))</ele><time>\(iso(p.date))</time>"
            if let hr = p.heartRate {
                xml += "<extensions><gpxtpx:TrackPointExtension>"
                xml += "<gpxtpx:hr>\(hr)</gpxtpx:hr>"
                if let cad = p.cadence {
                    xml += "<gpxtpx:cad>\(cad)</gpxtpx:cad>"
                }
                xml += "</gpxtpx:TrackPointExtension></extensions>"
            }
            xml += "</trkpt>\n"
        }
        xml += "  </trkseg></trk>\n</gpx>\n"
        return Data(xml.utf8)
    }

    // MARK: FIT

    private static let crcTable: [Int] = [
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    ]

    private static func crcUpdate(_ crc: Int, _ byte: Int) -> Int {
        var c = crc
        var tmp = crcTable[c & 0xF]
        c = (c >> 4) & 0x0FFF
        c = c ^ tmp ^ crcTable[byte & 0xF]
        tmp = crcTable[c & 0xF]
        c = (c >> 4) & 0x0FFF
        c = c ^ tmp ^ crcTable[(byte >> 4) & 0xF]
        return c
    }

    private static func crc16(_ bytes: [UInt8]) -> Int {
        var c = 0
        for b in bytes { c = crcUpdate(c, Int(b)) }
        return c & 0xFFFF
    }

    private enum FitType {
        static let enumT: UInt8 = 0x00
        static let uint8: UInt8 = 0x02
        static let uint16: UInt8 = 0x84
        static let sint32: UInt8 = 0x85
        static let uint32: UInt8 = 0x86
        static let uint32z: UInt8 = 0x8C

        static func size(_ t: UInt8) -> Int {
            if t == uint16 { return 2 }
            if t == sint32 || t == uint32 || t == uint32z { return 4 }
            return 1
        }
    }

    private struct FitField {
        let number: UInt8
        let type: UInt8
        let scale: Double

        init(_ number: Int, _ type: UInt8, _ scale: Double = 0) {
            self.number = UInt8(number)
            self.type = type
            self.scale = scale
        }
    }

    private struct FitMessage {
        let global: UInt16
        let fields: [FitField]
    }

    private static let fileIdMessage = FitMessage(global: 0, fields: [
        FitField(0, FitType.enumT), FitField(1, FitType.uint16), FitField(2, FitType.uint16),
        FitField(3, FitType.uint32z), FitField(4, FitType.uint32)
    ])

    private static let eventMessage = FitMessage(global: 21, fields: [
        FitField(253, FitType.uint32), FitField(0, FitType.enumT),
        FitField(1, FitType.enumT), FitField(4, FitType.uint8)
    ])

    private static let recordMessage = FitMessage(global: 20, fields: [
        FitField(253, FitType.uint32), FitField(0, FitType.sint32), FitField(1, FitType.sint32),
        FitField(2, FitType.uint16), FitField(3, FitType.uint8), FitField(4, FitType.uint8),
        FitField(5, FitType.uint32, 100), FitField(6, FitType.uint16, 1000),
        FitField(19, FitType.uint32)   // total_cycles：累计步数
    ])

    private static let lapMessage = FitMessage(global: 19, fields: [
        FitField(253, FitType.uint32), FitField(2, FitType.uint32),
        FitField(3, FitType.sint32), FitField(4, FitType.sint32),
        FitField(5, FitType.sint32), FitField(6, FitType.sint32),
        FitField(7, FitType.uint32, 1000), FitField(8, FitType.uint32, 1000),
        FitField(9, FitType.uint32, 100), FitField(10, FitType.uint32),
        FitField(11, FitType.uint16),
        FitField(13, FitType.uint16, 1000), FitField(14, FitType.uint16, 1000),
        FitField(15, FitType.uint8), FitField(16, FitType.uint8),
        FitField(17, FitType.uint8), FitField(18, FitType.uint8)
    ])

    private static let sessionMessage = FitMessage(global: 18, fields: [
        FitField(253, FitType.uint32), FitField(2, FitType.uint32),
        FitField(5, FitType.enumT), FitField(6, FitType.enumT),
        FitField(7, FitType.uint32, 1000), FitField(8, FitType.uint32, 1000),
        FitField(9, FitType.uint32, 100), FitField(10, FitType.uint32),
        FitField(11, FitType.uint16),
        FitField(14, FitType.uint16, 1000), FitField(15, FitType.uint16, 1000),
        FitField(16, FitType.uint8), FitField(17, FitType.uint8),
        FitField(18, FitType.uint8), FitField(19, FitType.uint8),
        FitField(24, FitType.uint8, 10), FitField(26, FitType.uint16), FitField(28, FitType.enumT)
    ])

    private static let activityMessage = FitMessage(global: 34, fields: [
        FitField(253, FitType.uint32), FitField(0, FitType.uint32, 1000),
        FitField(1, FitType.uint16), FitField(2, FitType.enumT),
        FitField(3, FitType.enumT), FitField(4, FitType.enumT),
        FitField(5, FitType.uint32), FitField(6, FitType.uint8)
    ])

    private struct FitWriter {
        var bytes: [UInt8] = []

        mutating func u8(_ v: Int) { bytes.append(UInt8(v & 0xFF)) }

        mutating func u16(_ v: Int) {
            bytes.append(UInt8(v & 0xFF))
            bytes.append(UInt8((v >> 8) & 0xFF))
        }

        mutating func u32(_ v: Int64) {
            bytes.append(UInt8(v & 0xFF))
            bytes.append(UInt8((v >> 8) & 0xFF))
            bytes.append(UInt8((v >> 16) & 0xFF))
            bytes.append(UInt8((v >> 24) & 0xFF))
        }

        mutating func definition(_ local: UInt8, _ msg: FitMessage) {
            u8(0x40 | Int(local & 0x0F))
            u8(0)
            u8(0)
            u16(Int(msg.global))
            u8(msg.fields.count)
            for f in msg.fields {
                u8(Int(f.number))
                u8(FitType.size(f.type))
                u8(Int(f.type))
            }
        }

        mutating func data(_ local: UInt8, _ msg: FitMessage, _ values: [Double]) {
            u8(Int(local & 0x0F))
            for (i, f) in msg.fields.enumerated() {
                let raw = i < values.count ? values[i] : Double.nan
                let size = FitType.size(f.type)
                if raw.isNaN {
                    if f.type == FitType.sint32 {
                        u32(0x7FFF_FFFF)
                    } else {
                        for _ in 0..<size { u8(0xFF) }
                    }
                    continue
                }
                let v = Int64((f.scale > 0 ? raw * f.scale : raw).rounded())
                switch f.type {
                case FitType.sint32, FitType.uint32, FitType.uint32z:
                    u32(v & 0xFFFF_FFFF)
                case FitType.uint16:
                    u16(Int(v & 0xFFFF))
                default:
                    u8(Int(v & 0xFF))
                }
            }
        }
    }

    public static func fitData(_ result: RunResult) -> Data {
        let pts = points(result)
        let startSec = Int64(result.startDate.timeIntervalSince1970)
        let endSec = Int64(result.endDate.timeIntervalSince1970)
        let duration = max(result.durationSeconds, 1)
        let distance = result.distanceMeters

        var fitSport = 1
        var fitSub = 0
        switch result.kind {
        case .outdoorRun: fitSport = 1
        case .indoorRun: fitSport = 1; fitSub = 1
        case .outdoorRide: fitSport = 2
        case .outdoorWalk: fitSport = 11
        }

        let hrValues = pts.compactMap(\.heartRate)
        let avgHr = hrValues.isEmpty ? Double.nan : Double(hrValues.reduce(0, +) / hrValues.count)
        let maxHr = hrValues.max().map(Double.init) ?? Double.nan
        let cadenceValues = pts.compactMap(\.cadence)
        let avgCad = cadenceValues.isEmpty ? Double.nan : Double(cadenceValues.reduce(0, +) / cadenceValues.count)
        let maxCad = cadenceValues.max().map(Double.init) ?? Double.nan
        let totalSteps = result.kind.countsSteps ? Double(result.steps) : Double.nan
        let maxSpeed = pts.map(\.speed).max() ?? 0

        // serial_number 每个文件唯一，避免 Keep 判重
        var serial = (startSec &* 2_654_435_761 &+ Int64(distance * 7)) & 0xFFFF_FFFF
        if serial == 0 { serial = 1 }

        var w = FitWriter()

        w.definition(0, fileIdMessage)
        w.data(0, fileIdMessage, [4, 1, 2600, Double(serial), Double(startSec)])

        w.definition(1, eventMessage)
        w.data(1, eventMessage, [Double(startSec), 0, 0, 0])

        w.definition(2, recordMessage)
        let trackable = result.kind.supportsRoute
        for (index, p) in pts.enumerated() {
            let t = Double(Int64(p.date.timeIntervalSince1970))
            let lat = trackable ? (p.latitude ?? Double.nan) : Double.nan
            let lon = trackable ? (p.longitude ?? Double.nan) : Double.nan
            let stepsCum: Double = result.kind.countsSteps && result.steps > 0
                ? (Double(result.steps) * (distance > 0 ? p.distanceMeters / distance : 0)).rounded()
                : Double.nan
            _ = index
            w.data(2, recordMessage, [
                t,
                lat.isNaN ? Double.nan : lat * (2_147_483_648.0 / 180.0),
                lon.isNaN ? Double.nan : lon * (2_147_483_648.0 / 180.0),
                p.altitude,
                p.heartRate.map(Double.init) ?? Double.nan,
                p.cadence.map(Double.init) ?? Double.nan,
                p.distanceMeters,
                p.speed,
                stepsCum
            ])
        }

        w.data(1, eventMessage, [Double(endSec), 0, 4, 0])

        // 分圈：每公里一段；没有分段时给整段一圈
        w.definition(3, lapMessage)
        var elapsedAtLapStart = 0.0
        if !result.splits.isEmpty {
            for split in result.splits {
                let lapStart = Double(startSec) + elapsedAtLapStart
                let lapEnd = lapStart + split.durationSeconds
                elapsedAtLapStart += split.durationSeconds
                let lapCalories = (result.activeEnergyKcal / Double(result.splits.count)).rounded()
                let lapSteps = result.kind.countsSteps
                    ? (Double(result.steps) * split.distanceMeters / max(distance, 1)).rounded()
                    : Double.nan
                w.data(3, lapMessage, [
                    lapEnd, lapStart,
                    Double.nan, Double.nan, Double.nan, Double.nan,
                    split.durationSeconds, split.durationSeconds, split.distanceMeters,
                    lapSteps, lapCalories,
                    split.distanceMeters / max(split.durationSeconds, 1), maxSpeed,
                    avgHr, maxHr, avgCad, maxCad
                ])
            }
        } else {
            w.data(3, lapMessage, [
                Double(endSec), Double(startSec),
                Double.nan, Double.nan, Double.nan, Double.nan,
                duration, duration, distance,
                totalSteps, Double(result.activeEnergyKcal.rounded()),
                distance / duration, maxSpeed,
                avgHr, maxHr, avgCad, maxCad
            ])
        }

        w.definition(4, sessionMessage)
        w.data(4, sessionMessage, [
            Double(endSec), Double(startSec), Double(fitSport), Double(fitSub),
            duration, duration, distance,
            totalSteps,
            Double(result.activeEnergyKcal.rounded()),
            distance / duration, maxSpeed,
            avgHr, maxHr, avgCad, maxCad,
            25, Double(max(result.splits.count, 1)), 0
        ])

        w.definition(5, activityMessage)
        w.data(5, activityMessage, [Double(endSec), duration, 1, 0, 26, 1, Double(startSec), 0])

        let body = w.bytes
        var header = [UInt8](repeating: 0, count: 14)
        header[0] = 14
        header[1] = 0x20
        header[2] = UInt8(2184 & 0xFF)
        header[3] = UInt8((2184 >> 8) & 0xFF)
        let bodyLen = body.count
        header[4] = UInt8(bodyLen & 0xFF)
        header[5] = UInt8((bodyLen >> 8) & 0xFF)
        header[6] = UInt8((bodyLen >> 16) & 0xFF)
        header[7] = UInt8((bodyLen >> 24) & 0xFF)
        header[8] = UInt8(ascii: ".")
        header[9] = UInt8(ascii: "F")
        header[10] = UInt8(ascii: "I")
        header[11] = UInt8(ascii: "T")
        let headerCrc = crc16(Array(header.prefix(12)))
        header[12] = UInt8(headerCrc & 0xFF)
        header[13] = UInt8((headerCrc >> 8) & 0xFF)

        var all = header + body
        let fileCrc = crc16(all)
        all.append(UInt8(fileCrc & 0xFF))
        all.append(UInt8((fileCrc >> 8) & 0xFF))
        return Data(all)
    }
}