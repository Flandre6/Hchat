# Hchat 社区开发规则

这份文件会被 Codex 自动读取。开始任何任务前，先读本文件，再按需要读取：

- `docs/AI_CODING_TUTORIAL.md`：Termux、Codex、DexClub MCP、构建、提 PR 的完整教程
- `docs/FEATURE_FRAMEWORK.md`：模块功能架构、生命周期和构建约定
- `docs/WECHAT_APIS.md`：模块公共微信 API
- `docs/SCRIPT_PLUGIN_API.md`：脚本插件完整接口
- `app/src/main/assets/script_plugin_agent_guide.md`：脚本插件 Agent 的运行时能力清单
- `skills/hchat-project/SKILL.md`：长期协作规则

## 开发底线

- 当前公开仓库的上游地址是 `https://github.com/ljh520134/Hchat-alt-entry`，默认基准分支是 `alt-entry`。
- 先读取 `git status` 和相关源码，再修改；不要覆盖别人未提交的工作。
- 不确定微信内部类、方法、字段、数据库或 Intent 时必须先用 DexClub 逆向确认，不能凭记忆猜混淆名称。
- 需要多版本兼容时，尽量横向检查 8.0.49、8.0.58、8.0.66、8.0.68、8.0.72、8.0.74、8.0.76、8.0.77；没有对应 APK 时要明确说明没有验证。
- 修改 DexKit 定位时必须考虑缓存、微信版本、热更新和 ClassLoader；不要每次启动无条件重跑定位。
- 新增反射代码走 `h.Hchat.utils.KavaReflector`；模块错误日志走 `HLog.e(...)`。
- 新增功能、公共 API、脚本 API、设置入口、构建流程或文件路径时，同步更新相关文档。
- 默认不在本地运行 Gradle；只有用户明确要求构建或验证时才构建。低内存机器优先使用 GitHub Actions。
- native 库必须同时覆盖 `arm64-v8a` 和 `armeabi-v7a`。
- 不要提交 keystore、APK、`local.properties`、构建缓存、服务器密码、API key、Token、聊天数据库或个人媒体。
- 提交信息和 PR 标题、说明使用中文。

## 修改完成前

至少执行并汇报：

```sh
git status --short
git diff --check
git diff --stat
```

能运行测试时再运行对应测试。不要把“编译失败”隐藏成“功能已完成”，也不要把未经设备验证的行为写成已验证。
