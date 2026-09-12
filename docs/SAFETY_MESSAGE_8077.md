# 内置安全消息 8.0.77 适配

本记录针对 APK 内置 `SpecialMessageFeature`，不涉及外部 `发送XML` 脚本。
旧实现仅允许微信 8.0.76 / 3140，8.0.77 会在注册发送监听前直接返回。
现按精确版本名和完整 versionCode 选择已确认的配置；未知版本仍不安装，
不能将本次修改解释为全部微信版本兼容。

## APK 静态证据

通过 DexClub MCP 检查用户提供的 8076.apk、8077.apk。8077 Manifest 为
`com.tencent.mm / 8.0.77 / 3160`，内容指纹为
`b193a84f770dca5e5ea20ba0be81ce1f6b336aa8e824d1abbe68cc5b925a710e`。

| 项目 | 8.0.76 / 3140 | 8.0.77 / 3160 |
| --- | --- | --- |
| 文本 MsgSource 组装 | `lq1.u0.r(a65.en4, com.tencent.mm.storage.e9): void` | `ft1.u0.p(y85.gq4, com.tencent.mm.storage.e9): void` |
| 表情 MsgSource 提供者 | `lq1.d1.a(e9): String` | `ft1.d1.a(e9): String` |
| 表情请求分发 | `o22.y.doScene(network.s, modelbase.u0): int` | `k52.y.doScene(network.s, modelbase.u0): int` |
| 消息会话读取 | `N0(): String` | `Q0(): String` |

8077 通过 `MicroMsg.NetSceneSendMsg` 定位 `o41.r0.doScene`，其调用的
`nl3.f4.p(gq4,e9)` 为接口。检查五个同签名候选后确认 `ft1.u0.p` 的实现
读取消息 `G` 写入请求 `gq4.i`；功能 Hook 安装在该具体实现上，不 Hook 接口。
`e9.s3(String)` 写入 `G`、标记更新并清理解析缓存，因此继续使用该方法写回。

`/cgi-bin/micromsg-bin/sendemoji` 唯一命中 `k52.y` 的构造方法，cmdId 为 175。
构造方法将目标会话保存至 `m`，请求经 `d.a.a.e` 取得首项 `y85.ok0`，
其 `p` 是 MsgSource。`doScene` 首发直接分发，上传阶段可能通过 `ft1.d1.a`
重建 `p`，因此保留提供者和分发前两条 Hook。

## 行为和验证边界

- 文本和链接仍分别受开关与生效名单控制；表情仍受表情开关和同一名单控制。
- 图片安全消息仍未实现。
- 方法、字段按当前 ClassLoader 解析并检查类型；错误日志包含微信版本、阶段和目标方法。
- 没有新增运行时 DexKit 查询或持久化定位缓存；精确版本配置直接走现有反射和安装调度器。
- `node scripts/run_safety_message_tests.cjs` 检查版本选择和未知版本拒绝，不替代真机 Hook 验证。
- 仍需在新 APK 上分别测试名单内文本、链接、表情和名单外普通消息。此前旧 APK 的实测失败不能视为本次修复已验证。
- 已知独立限制：同时启用正文前后缀时，原有 pending 正文匹配可能失败；本次先处理 8077 入口适配，未改变公共发送 API。

## 安全消息功能复用

`securemessage/SendSecureMessageFeature` 复用上表已确认的两条表情链路，作为通用入库和 MsgSource setter
之外的发送请求补偿。该功能使用自身的 `Hchat_secure_message` 总开关，不读取 `special_message` 的表情开关
或生效名单，因此开启“安全消息”后会对所有发送目标的表情写入标记。未知微信版本不安装精确表情 Hook，
仍保留原有通用兜底；这项复用尚未完成真机发送验证。

## 本地构建

用户已授权 Release 构建。使用原签名，保留 R8；跳过 `copyToDist`，避免扫描
原 `dist/备份` 中不可读文件，再将最终 APK 复制为独立文件名，保留旧安装包。
工作区只有 `alt-entry` 分支，本次未创建或推送 PR，后续合并时需按仓库维护流程同步主线。

2026-09-12 验证结果：版本配置回归 194 项通过；Release 构建成功，保留 R8。
产物 `dist/Hchat-alt-entry-8077-safety-fix-20260912.apk` 的 SHA-256 为
`91ba5a0c323abb17e7f2e786846d57a2c7759533ba66414e45e5a9f6beff55ba`。
apksigner 验证成功，证书与旧包一致。最终 DEX 中 `Lyp4;->g(Ll71;)V`
保留 `8.0.77` 与 `0xc58`（3160）的组合判断，构造包含 `ft1.u0/p/y85.gq4/ft1.d1/k52.y/Q0`
的配置，并继续注册发送监听；这是产物静态验证，尚无修复后真机发送结果。
