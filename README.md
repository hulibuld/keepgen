# 运动数据生成

为 Keep 生成 FIT / TCX 运动数据文件的 Android 应用。填距离、配速（或总时长）、开跑时间，
生成一份标准格式的运动数据：可以导出到手机、可以写进 Keep 账号、也可以写进手机的
Health Connect。

## 它做什么

- **生成**：按跑道（操场/直线）和配速算出轨迹点，输出 TCX / FIT / GPX。
  轨迹不是折线，是不规则闭合环线，带换道漂移和轻微摆动，导入 Keep 后看起来像真实跑动。
- **写进 Keep**：登录账号后一键写入，不需要手动导文件。
- **补历史**：一次补齐最近几天，每天开跑时间自动错开。
- **Health Connect**：API 34+ 的设备可以写入系统健康数据（华为/小米运动健康不收第三方写入，
  所以另外提供 GPX 导出给它们导入）。
- **记录管理**：直接在应用里看已写入 Keep 的记录列表和明细。

## 编译

不需要 Gradle，纯 `javac` + R8 + `aapt2` + `apksigner`：

```bash
export ANDROID_SDK_ROOT=$HOME/android-build/sdk
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
VCODE=150 VNAME=32.0 bash build.sh
```

产物在 `out/keepgen.apk`。签名需要一个自己的 keystore，脚本里读 `KEEP_KS` / `KEEP_KS_PASS`。

## 目录

```
src/com/qianyuzhou/keepgen/
  MainActivity.java     主界面：参数、生成、写入
  Generator.java        轨迹与采样：跑道几何、配速曲线、心率
  Track.java            轨迹生成（不规则环线 + 换道 + 摆动）
  KeepApi.java          Keep 接口：登录、查询、写入
  KeepBody.java         组装 Keep 的建档报文（经纬度 GCJ-02、步数、分段）
  BatchActivity.java    补历史
  KeepListActivity.java 记录列表
  SettingsActivity.java 设置（iOS 分组卡片风）
  Wheel / Pickers       自研拨轮控件
  Fit / GpxOut / Hc     各格式导出与 Health Connect
  License / Redeem      授权与兑换码
  Ui / Sheet            设计系统与底部弹层
```

## 关于授权

这个仓库公开的是应用本体代码。兑换码核销、每日次数、撤销都在服务端（不在本仓库），
客户端只负责把凭据存在本机、生成前检查一次、以及启动时核对激活状态。

代码签名证书与密钥不在仓库里。

## 免责

生成的运动数据仅用于个人训练记录。请遵守所在学校、单位与 Keep 平台的规定，
不要把伪造的数据用于成绩申报或任何需要真实性的场合。
