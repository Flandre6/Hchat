# 安全消息表情链路 8.0.77 适配

本记录针对 `securemessage/SendSecureMessageFeature` 的表情发送链路。该功能按精确版本名和完整
versionCode 选择已确认的配置；未知版本不安装精确表情 Hook，并继续使用通用入库和 MsgSource setter 兜底。

## APK 静态证据

通过 DexClub MCP 检查用户提供的 8076.apk、8077.apk。8077 Manifest 为
`com.tencent.mm / 8.0.77 / 3160`，内容指纹为
`b193a84f770dca5e5ea20ba0be81ce1f6b336aa8e824d1abbe68cc5b925a710e`。

| 项目 | 8.0.76 / 3140 | 8.0.77 / 3160 |
| --- | --- | --- |
| 表情 MsgSource 提供者 | `lq1.d1.a(e9): String` | `ft1.d1.a(e9): String` |
| 表情请求分发 | `o22.y.doScene(network.s, modelbase.u0): int` | `k52.y.doScene(network.s, modelbase.u0): int` |

`/cgi-bin/micromsg-bin/sendemoji` 唯一命中 8.0.77 的 `k52.y` 构造方法，cmdId 为 175。
请求经 `d.a.a.e` 取得首项 `y85.ok0`，其 `p` 是 MsgSource。`doScene` 首发直接分发，上传阶段可能通过
`ft1.d1.a` 重建 `p`，因此同时保留 MsgSource 提供者和分发前两条 Hook。

## 行为和验证边界

- 表情链路只受 `Hchat_secure_message` 总开关控制，对所有发送目标生效。
- 方法和字段按当前 ClassLoader 解析并检查类型；错误日志包含微信版本和目标方法。
- 没有新增运行时 DexKit 查询或持久化定位缓存，精确版本配置直接走现有反射和安装调度器。
- `node scripts/run_safety_message_tests.cjs` 检查版本选择和未知版本拒绝，不替代真机 Hook 验证。
- 当前仅有 8.0.76、8.0.77 的静态逆向证据，仍需在目标版本真机验证表情发送。

2026-09-12 已完成版本配置回归与 Release 产物静态检查；这些结果不能视为真机发送验证。
