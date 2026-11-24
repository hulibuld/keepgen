# 运动数据生成 · iOS 版

Android 版的 iOS 对应实现：SwiftUI 原生，用的是 Apple「健康」（HealthKit），不联网、不依赖第三方库。

- 源码：`ios/Sources/KeepGenCore`（生成算法与健康写入）、`ios/Sources/KeepGenUI`（界面）、`ios/App`（入口、Info.plist、entitlements、图标）
- 工程：`ios/KeepGen.xcodeproj`（由 `ios/project.yml` 经 xcodegen 生成，也可直接双击打开）
- 最低系统：iOS 16
- 已出过包：Xcode 26.3 + iOS 26.2 SDK

## 一、两条路装（不编译）

| 包 | 给谁用 | 怎么装 |
|---|---|---|
| `KeepGen-1.1.0-adhoc.ipa`（官网名 `keepgen-ios-1.1.0-trollstore.ipa`） | iOS 14.0b2 – 16.6.1、16.7 RC、17.0 | 已带 `platform-application`，TrollStore 直接吃，永久有效 |
| `KeepGen-1.1.0-unsigned.ipa` | iOS 17.0.1 及以上 | SideStore / AltStore / Sideloadly / 爱思自助签名，7 天续签 |

```
unsigned  1,057,707 字节  3d8f421b111d4312c1788fdf35bad2d5644f02426631d56b166aca05325e59eb
adhoc     1,084,897 字节  706a17c9bcdadb2d63d417210ae5662a0689f6c3756920612322f6b98a4a9794
```

**HealthKit 权限是这一版的门槛**：`.app` 里带 `KeepGen.entitlements`（`com.apple.developer.healthkit` + `healthkit.access`）。用 SideStore / Sideloadly 自签时，工具会用你的免费 Apple ID 重新签一遍，**免费个人团队是支持 HealthKit 的**，所以写健康能成；如果自签后生成时提示「无法写入健康」，八成是签名工具丢了 entitlement，改用下面的 Xcode 方案跑一次即可。

## 二、自己出包

```bash
brew install xcodegen
cd ios
./build-ipa.sh
```

脚本依次做：`xcodegen generate` → `xcodebuild` 出 arm64 Release（不签名）→ 压成未签名 `.ipa` → 复制一份用 ad-hoc 签名（entitlements 含 healthkit 两项 + `platform-application`）出 TrollStore 包。产物在 `ios/dist/`。

### 用 Xcode 装到自己手机（推荐，HealthKit 最稳）

1. 双击 `ios/KeepGen.xcodeproj`。
2. 选中 `KeepGen` target → Signing & Capabilities → Team 选你的免费 Apple ID。
3. **确认 Capabilities 里有 HealthKit**（`project.yml` 已声明，若 Xcode 提示修复就点一次 Fix Issue）。
4. 顶部选你的 iPhone → Run。7 天到期重新 Run 一次，本地历史保留。

## 三、怎么用

四个 tab：生成 / 批量 / 路线 / 设置。

**生成**
1. 先选活动：户外跑步、室内跑步、户外行走、户外骑行。室内跑步不产生任何坐标（跑步机不记 GPS）。
2. 填开跑时间、时长、距离。配速由时长与距离算出来，也可以只填两个、第三个自动推。
3. 心率三参数（设置里改默认值）：
   - 静息心率（默认 60）、最高心率（默认 190）、最快 1 公里（默认 4:00）
   - 心率按 Karvonen 心率储备法算：`%HRR = clamp(0.35 + 0.65 × (v/vMax)^0.8, 0.30, 1.00)`，`HR = rest + %HRR × (max − rest)`；
   - 再叠三层真实感：前 180 秒热身从静息爬到目标、10 分钟后按每小时 +3% 漂移、最后 90 秒冲刺最多 +7 bpm。
   - 语义提醒：**「最快 1 公里」填得越大，说明能力越弱**，同样配速下算出的心率越高。想整体压低心率，把这个数改小（比如 3:30）。
4. 路线三种来源：田径场（跑道长度 + 中心坐标 + 方位角，按圈数生成闭合轨迹）、粘贴坐标（一行一个 `lat,lng`）、路线库（保存过的）。
5. 点「生成」→ 先本地预演（轨迹点、每公里分段、心率区间、卡路里、步数），确认后点「写入健康」。

**批量**：选日期范围、每天条数、生成时间窗、四种活动的比例，一次补齐多天。执行中显示进度、可中止。**每天上限 50 条**（按自然日重置，记在本机）。

**写入的到底是什么**（跟 Android 版写 Health Connect 的对象一一对应）：

| 对象 | HealthKit 类型 |
|---|---|
| 一次训练 | `HKWorkout`（活动类型按四种映射，metadata 带 `HKMetadataKeyIndoorWorkout`） |
| 距离 | `HKQuantitySample` `.distanceWalkingRunning` / `.distanceCycling` |
| 活动能量 | `.activeEnergyBurned`（体重 × 公里 × 每公里系数，跑步 1.036 / 行走 0.85 / 骑行 0.5） |
| 步数 | `.stepCount`（仅跑步与行走） |
| 心率序列 | `.heartRate`，每 10 秒一个点，挂在那次训练上 |
| 路线 | `HKWorkoutRoute`（`HKWorkoutRouteBuilder` 生成，室内跑步不写） |

写完打开 iPhone 的「健康」→ 浏览 → 活动 → 训练，或者体能训练里就能看到这条记录。

## 四、iOS 版和 Android 版差在哪

| 能力 | Android | iOS 1.1.0 |
|---|---|---|
| 四种活动 | 有 | 有 |
| 轨迹：田径场 / 粘贴坐标 / 路线库 | 有 | 有 |
| 心率：三参数 + 热身 + 漂移 + 冲刺 | 有 | 有，同一套 Karvonen 公式 |
| 每公里分段、卡路里、步频 | 有 | 有 |
| 轨迹真实感（换道漂移 + 不规则环线 + 摆动，同参数换种子不同形状） | 有 | 有 |
| 批量补历史 | 有 | 有 |
| 每天生成上限 | 每台机器 50 条 | 每台机器 50 条，按自然日重置 |
| 解锁方式 | 1 元兑换码，永久 | 1 元兑换码，永久；与 DeepChat 同一枚码通用（一码通） |
| 写入系统健康库 | Health Connect（Android 14） | Apple「健康」，含心率序列与路线 |
| 导出 FIT / TCX / GPX | 有 | 有，三种格式都能导出 |
| 登录 Keep 直写 | 有 | 未做 |
| 无障碍自动走到 Keep 导入页 | 有 | 未做（iOS 没有这个入口） |

**为什么 iOS 没有「登录 Keep 直写」**：Keep 的写入接口走账号登录，iOS 上做这件事等于在别人的应用里伪造训练记录，而且 Keep iOS 本身没有「运动数据文件导入」这个入口。iOS 版的位置在于系统健康库——先把记录写进「健康」，再由你自己决定要不要让 Keep 去读。Keep iOS 若开了健康同步，就能看到这些记录；具体支持到什么程度取决于 Keep 的版本，本应用不做承诺。

## 五、解锁（1 元兑换码）

下载免费，生成要码。凭证与 DeepChat 同一套格式、同一把公钥，**同一枚码两边都能激活**：

```
YC2.<base64url(payload)>.<base64url(DER ECDSA-P256-SHA256 签名)>
payload: {"v":2,"c":"码","d":"设备号或*","e":过期毫秒,"n":"随机串","o":0|1}
```

- 装完第一次打开停在兑换页，页面上写着**本机设备号**；把整串粘进去点「验证」就进主界面。
- `d="*"` 的零售码在任意设备激活（首次激活绑这台机器）；`d="设备号"` 的码只能在指定设备用。
- 客户端只有 P-256 公钥，**没有任何签发能力**。
- 换手机或重装后设备号会变（Keychain 里存的 UUID；若系统把 ad-hoc 包的 Keychain 挡了会退回 UserDefaults），需要按新设备号换签。
- 界面能看、参数能调，只有「生成」那一步会检查授权——和 Android 版口径一致。

## 六、隐私

- 全部计算在本机，不联网、不请求任何服务端（Android 版的解锁码核销与云端骨架在 iOS 这版没有）。
- 生成的坐标、心率只写进系统「健康」，不落第三方服务器。
- 卸载应用不会删掉已写进健康的记录；要清除得去「健康」里手动删。

## 七、已知限制

- 心率曲线的瞬时速度取的是平均配速，没有逐点抖动（Android 版来自轨迹微分）。曲线形态是「热身斜坡 → 平台 → 漂移 → 末段冲刺」，数值落在同一区间。
- 轨迹是本地几何（不规则闭合环线 + 换道漂移 + 摆动），不用 Android 那套云端骨架模板，所以形状与 Android 生成的不是同一条。
- 没有 Keep 登录与无障碍自动导入（iOS 上 Keep 没有文件导入入口）。
- 每天 50 条上限记在 UserDefaults，重装会清零；授权状态在 Keychain，重装后设备号会变。