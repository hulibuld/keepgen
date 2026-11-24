import SwiftUI
#if canImport(Darwin)
import Darwin
#endif

// MARK: - 使用须知与免责声明

/// 使用须知与免责声明页。
///
/// 首次启动时以 `.gate` 模式拦在兑换页之前：同意才继续，不同意直接退出。
/// 设置页以 `.sheet` 模式随时可以再打开。同意状态只记在 UserDefaults（键 `terms_v1`）。
public struct TermsView: View {

    /// 同意状态在 UserDefaults 里的键。
    public static let acceptedKey = "terms_v1"

    public enum Mode {
        /// 首启动拦截：底部是「同意 / 不同意退出」。
        case gate
        /// 设置页打开：右上角关闭。
        case sheet
    }

    private let mode: Mode
    private let onAccept: () -> Void

    @Environment(\.dismiss) private var dismiss

    public init(mode: Mode, onAccept: @escaping () -> Void = {}) {
        self.mode = mode
        self.onAccept = onAccept
    }

    public var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 12) {
                    if mode == .sheet { closeRow }
                    titleCard
                    sectionCard(
                        "一、这个软件到底做什么（请先读这一节）",
                        items: SectionOne.items
                    )
                    sectionCard(
                        "二、明确禁止的用途",
                        lead: SectionTwo.lead,
                        items: SectionTwo.items,
                        note: SectionTwo.note
                    )
                    sectionCard(
                        "四、健康数据的特别提示（这一节请务必读完）",
                        items: SectionFour.items
                    )
                    footerCard
                    if mode == .gate { actionCard }
                }
                .padding(.horizontal, 16)
                .padding(.top, mode == .gate ? 30 : 8)
                .padding(.bottom, mode == .gate ? 24 : 20)
            }
        }
        .preferredColorScheme(.dark)
    }

    // MARK: 结构

    private var closeRow: some View {
        HStack {
            Spacer()
            GhostButton("关闭", systemImage: "xmark", tint: Theme.dim) {
                dismiss()
            }
            .frame(width: 96)
        }
    }

    private var titleCard: some View {
        Card {
            Text("使用须知与免责声明")
                .font(.system(size: 21, weight: .bold))
                .foregroundStyle(Theme.ink)
            Text("下载、安装、激活或使用本项目，即视为你已阅读、理解并同意下列全部内容。不同意其中任何一条，请不要安装或使用。")
                .font(.system(size: 12))
                .foregroundStyle(Theme.dim)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func sectionCard(_ title: String,
                             lead: String? = nil,
                             items: [String],
                             note: String? = nil) -> some View {
        Card {
            SectionLabel(title)
            if let lead {
                Text(lead)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.dim)
                    .fixedSize(horizontal: false, vertical: true)
            }
            ForEach(Array(items.enumerated()), id: \.offset) { index, text in
                HStack(alignment: .top, spacing: 6) {
                    Text("\(index + 1).")
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        .foregroundStyle(Theme.accent)
                    Text(text)
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if let note {
                Text(note)
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var footerCard: some View {
        Card {
            SectionLabel("其余条款")
            Text("三、与第三方平台的关系；五、服务来源；六、兑换码与费用；七、担保与责任限制；八、合规使用与自查；九、未成年人；十、版权与侵权处理；十一、条款变更与解释；十二、联系方式——完整条文见随包源码根目录的 DISCLAIMER.md。")
                .font(.system(size: 11))
                .foregroundStyle(Theme.faint)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var actionCard: some View {
        Card {
            PrimaryButton("我已阅读并同意", systemImage: "checkmark.seal") {
                onAccept()
            }
            GhostButton("不同意，退出", systemImage: "xmark.circle", tint: Theme.danger) {
                exit(0)
            }
            Text("点击「我已阅读并同意」后即可继续兑换；之后在「设置」里随时能再看这一页。")
                .font(.system(size: 10))
                .foregroundStyle(Theme.faint)
        }
    }
}

// MARK: - 条文（与 DISCLAIMER.md 一致，不许改写）

private enum SectionOne {
    static let items = [
        "本软件生成运动数据：按你输入的日期、时长、距离、配速、心率与路线，计算出一份完整的训练记录，并写入你手机的「健康」应用（Android 为 Health Connect，iOS 为 Apple「健康」），或导出为 FIT / TCX / GPX 文件。",
        "这些数据是算法算出来的，不是你跑出来的。它们不对应任何真实的运动过程、真实的心率变化与真实的地理位置。",
        "本软件的设计目的，是个人训练记录的补全与技术研究：例如设备没电、跑步机不记录、换手机丢数据时，把当天的训练量补进自己的记录里，让月度统计连续。",
        "本软件不是测量工具、不是医疗设备、不是运动能力的证明工具。"
    ]
}

private enum SectionTwo {
    static let lead = "下列用途一律禁止。因下列用途产生的一切后果，由使用者自行承担，作者不承担任何责任："

    static let items = [
        "学校体育测试、体质健康测试、军训考核、毕业体测等任何以真实性为前提的成绩申报。",
        "各类线上或线下赛事、挑战赛、企业团建、单位考核与绩效评比。",
        "保险理赔、健康告知、体检报告、就医诊断、康复评估等与医疗或保险有关的场景。",
        "社交平台或运动平台的排行、勋章、等级、连续打卡等需要真实性的展示。",
        "伪造他人的运动记录，或冒充他人身份提交数据。",
        "任何需要证明「我确实完成了这项运动」的场合。"
    ]

    static let note = "用于上述场合可能构成违规、违纪，甚至构成欺诈；由此产生的纪律处分、民事赔偿或刑事责任，与本项目无关。"
}

private enum SectionFour {
    static let items = [
        "写入「健康」的数据会被系统与其他应用读取，包括可能被用于运动建议、心率区间统计与健康趋势分析。",
        "伪造的心率数据与训练数据可能干扰医疗判断。若你就医、体检或使用健康类应用，请先确认这些数据是否已被同步；必要时在「健康」中删除对应记录，并主动告知医生这些数据不是真实测量结果。",
        "写入的路线与位置是算法生成的坐标，不代表你曾经到过该地点。若你的设备开启了位置记录或家庭共享，相关数据可能与真实位置不一致，由此产生的误会由你自行处理。",
        "本项目不对写入数据的医学意义、安全性或适用性作任何承诺。"
    ]
}