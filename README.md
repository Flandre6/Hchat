# Hchat alt-entry

Hchat 是面向已解锁 Android 设备、LSPosed 和微信的功能扩展模块。本仓库是只保留 `alt-entry` 频道的公开源码快照，适合社区继续修复功能、开发新功能和维护脚本插件运行时。

## 给新贡献者

不熟悉 Android、Hook 或 GitHub 也可以从这份教程开始：

**[AI Coding 完整教程：Termux + Codex + DexClub MCP + Hchat](docs/AI_CODING_TUTORIAL.md)**

贡献流程和 PR 规范见 **[CONTRIBUTING.md](CONTRIBUTING.md)**。

## 目录速览

```text
app/src/main/java/h/Hchat/
├── hooks/api/       可复用的微信 API
├── hooks/items/     各个独立功能
├── hooks/core/      功能注册、Hook 注册和调度
├── dexkit/          DexKit 定位与缓存
├── event/           模块内部事件
├── ui/              设置页面和 Miuix UI
└── utils/           反射、日志和通用工具

app/src/main/java/bsh/       BeanShell 脚本引擎
app/src/main/jniLibs/        ARM 32/64 位 native 库
app/libs/                    项目需要的本地 AAR
docs/                        功能、API、Agent 和开发文档
skills/                      Codex skill 源码
.github/workflows/           GitHub Actions 构建流程
server/plugin-market/        在线插件市场后端
```

## 快速开始

```sh
git clone --branch alt-entry --single-branch \
  https://github.com/ljh520134/Hchat-alt-entry.git
cd Hchat-alt-entry
git status --short --branch
```

开始改代码前先阅读：

1. `AGENTS.md`
2. `docs/AI_CODING_TUTORIAL.md`
3. `docs/FEATURE_FRAMEWORK.md`
4. `docs/WECHAT_APIS.md` 或 `docs/SCRIPT_PLUGIN_API.md`

## 构建

仓库当前使用 Gradle Wrapper 9.5.1、Android Gradle Plugin 9.1.0、JDK 17 编译源码，GitHub Actions 使用 JDK 21；当前 `compileSdk` 和 `targetSdk` 是 37，`minSdk` 是 27。

正式 Release 默认开启 R8 和资源压缩。新贡献者没有项目签名证书时，应先做编译检查或使用自己的测试签名；更换证书生成的 APK 不能保证覆盖安装维护者发布的版本。

构建命令、无 R8 测试、签名和 GitHub Actions 说明全部写在 [AI Coding 完整教程](docs/AI_CODING_TUTORIAL.md) 中。

## 脚本插件

脚本插件运行在微信的 `Hchat/脚本插件/` 目录中。完整公开接口见：

- `docs/SCRIPT_PLUGIN_API.md`
- `docs/SCRIPT_PLUGIN_API_QUICK.md`
- `app/src/main/assets/script_plugin_agent_guide.md`

脚本插件默认不要自动启用；需要 Hook 微信混淆类时，必须先获得当前版本的逆向证据。

## 许可证和第三方代码

当前公开快照没有根目录 `LICENSE` 文件。准备再分发源码、发布二次修改版或合并大规模第三方代码前，请先确认项目作者和各第三方依赖的授权要求。仓库中的部分代码和资源保留各自的版权声明，不能因为仓库公开就默认获得任意再分发权。

## 安全提醒

本项目会加载 Xposed Hook、脚本和部分 native 代码。只安装可信 APK、脚本和 `.so`；不要把微信聊天数据库、账号凭据、服务器密码、签名证书或 API key 发给 AI、贴到 Issue 或提交到 Git。
