# 小李桌面（XiaoLi Launcher）

面向 Android TV 的蓝色沉浸式桌面，提供大屏应用抽屉、遥控器焦点导航、常用应用管理、壁纸与桌面组件设置，并集成小李全联局域网文件互传。

**产品约束：** 无广告；电视大屏优先；应用图标和卡片按屏幕比例缩放；文件传输核心保持独立，UI 改造不重写传输协议。

## 模块结构

| 模块 | 职责 |
|---|---|
| app | 桌面首页、应用抽屉、设置中心、壁纸、时钟与默认桌面角色 |
| feature/quanlian | 小李全联电视界面、左侧导航、遥控器、触摸交互与局域网设备发现 |
| core/transfer-api | 设备与传输 API 模型 |
| core/transfer-android | TLS 设备身份、发送/接收前台服务、文件完整性校验与续传 |

## 本地构建

环境：JDK 17、Android SDK 35、Gradle 8.9。

Debug 构建与自动化测试：

    gradle --no-daemon lintDebug testDebugUnitTest assembleDebug

Release 变体（需要设置 Android 签名环境变量）：

    gradle --no-daemon lintRelease testReleaseUnitTest assembleRelease

## GitHub Actions

- android.yml：执行 Debug lint、单元测试、APK 构建，并用一次性临时密钥验证 Release 变体。
- release.yml：推送 v* 标签后，检查版本号、读取受保护的签名 Secrets、构建正式 Release APK、验证签名并发布 APK 与 SHA-256 校验文件。

Debug APK 与正式签名 Release APK 不同。正式发布密钥必须由仓库管理员保管，配置方法见 [正式签名说明](docs/RELEASE_SIGNING.md)。

## 安全与隐私

- 设备首次配对显示 TLS 证书 SHA-256 指纹，用户明确确认后保存信任关系。
- 已信任设备后使用证书指纹固定，证书变化时拒绝连接。
- 文件传输完成后执行 SHA-256 完整性校验；保留已有续传逻辑。
- 不加入广告 SDK、广告位或推广弹窗。
- 不要将 keystore、密码、Base64 密钥或个人设备信息提交到仓库。

## 验收文档

- [电视大屏设计规范](docs/TV_DESIGN_SPEC.md)
- [交付验收矩阵](docs/RELEASE_ACCEPTANCE_MATRIX.md)
- [正式签名与发布](docs/RELEASE_SIGNING.md)
- [设备实测步骤](docs/DEVICE_VALIDATION_RUNBOOK.md)

真实电视遥控器体验、跨设备大文件互传和正式签名产物仍必须按验收矩阵在真实环境验证，不能用 CI 编译代替。
