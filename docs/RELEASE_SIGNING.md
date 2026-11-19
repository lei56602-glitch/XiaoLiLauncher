# Android 正式签名与发布

正式发布使用独立 Android release keystore。不要把 keystore、密码、Base64 内容或签名文件提交到 Git，也不要把密码发到聊天中。

## 1. 在可信电脑上生成长期签名密钥

命令：

    keytool -genkeypair -v -keystore xiaoli-release.jks -alias xiaoli-release -keyalg RSA -keysize 4096 -validity 10000

妥善保存 keystore、alias、store password 和 key password。至少制作两份离线加密备份。丢失正式签名密钥会影响后续 APK 覆盖升级。

## 2. 配置 GitHub Actions Secrets

在仓库 Settings → Secrets and variables → Actions → New repository secret 中建立：

| Secret 名称 | 内容 |
|---|---|
| ANDROID_KEYSTORE_BASE64 | xiaoli-release.jks 文件的 Base64 单行内容 |
| ANDROID_STORE_PASSWORD | keystore 密码 |
| ANDROID_KEY_ALIAS | 生成密钥时使用的 alias |
| ANDROID_KEY_PASSWORD | key password |

Linux 可用以下命令生成单行 Base64，再把内容粘贴到 GitHub Secret：

    base64 -w 0 xiaoli-release.jks

macOS 可用：

    base64 < xiaoli-release.jks | tr -d '\n'

不要把 Base64 文件放入仓库。

## 3. 发布流程

每次发布前先递增 app/build.gradle.kts 中的 versionCode，并将 versionName 更新为目标版本。推送与 versionName 一致的 v* Git tag（例如 versionName 为 0.1.3 时推送 v0.1.3）后，.github/workflows/release.yml 会：

1. 检查四项签名 Secret 是否齐全。
2. 在 GitHub runner 临时目录还原 keystore。
3. 执行 lintRelease、testReleaseUnitTest、assembleRelease。
4. 使用 apksigner 验证 APK 签名并输出证书信息。
5. 创建 GitHub Release 并上传 APK。

当前仓库未配置上述 Secrets，因此正式签名发布流程已准备，但尚不能声称已产出正式签名 APK。Debug APK 与正式 Release APK 是不同交付物。
