# Hchat alt-entry

这是 Hchat 的单频道 alt-entry 源码快照，仓库只保留该频道代码，不包含主线分支和内部开发文档。

## 构建

项目使用 Android Gradle Plugin、Kotlin Compose 和 compileSdk 34。正式构建需要：

- app/keystore/。。.jks
- HCAT_STORE_PASSWORD
- HCAT_KEY_ALIAS
- HCAT_KEY_PASSWORD

GitHub Actions 通过仓库 Secrets 注入签名材料，源码中不保存签名密码。手动构建时可以按同名环境变量提供签名配置：

HCAT_STORE_PASSWORD=... \
HCAT_KEY_ALIAS=... \
HCAT_KEY_PASSWORD=... \
sh ./gradlew :app:assembleRelease

无 R8 测试构建可在 GitHub Actions 中手动运行“安卓测试构建（无 R8）”。

## 说明

本项目面向 LSPosed/Xposed 环境中的微信版本适配。微信内部类、资源和行为会随版本变化，使用前请自行确认兼容性。服务器插件市场源码和部署配置保留在 server/plugin-market。
