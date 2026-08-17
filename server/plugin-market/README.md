# Hchat 在线插件市场后端

基于 Python 标准库 `http.server` 和 `sqlite3` 的轻量插件市场服务，不依赖 FastAPI、Node.js 或第三方 Python 包。

当前生产地址为 `https://hchat.103.97.179.142.sslip.io`。服务只在服务器本机监听 `127.0.0.1:8765`，公网请求由独立 Nginx 虚拟主机转发；不要把 Python 端口直接开放到公网。

## 功能

- `GET /health`
- `GET /v1/plugins?q=&sort=latest|downloads&limit=20`
- `GET /v1/plugins/{pluginId}`
- `POST /v1/plugins/{pluginId}/downloads`
- `POST|DELETE /v1/plugins/{pluginId}/likes`
- `GET|POST /v1/plugins/{pluginId}/comments`
- `POST /v1/plugins/{pluginId}/comments/{commentId}/replies`
- `DELETE /v1/plugins/{pluginId}/comments/{commentId}`
- `GET /v1/notifications`
- `POST /v1/notifications/read`
- `GET /v1/plugins/{pluginId}/snapshots`
- `GET /v1/plugins/{pluginId}/snapshots/{versionId}`
- `POST /v1/plugins`
- `DELETE /v1/plugins/{pluginId}`
- `GET /admin/`
- `POST|GET|DELETE /v1/admin/session`
- `POST /v1/admin/password`
- `GET|POST /v1/admin/settings`
- `GET /v1/admin/plugins`
- `POST /v1/admin/plugins/batch-delete`
- `GET /v1/admin/plugins/{pluginId}/versions/{versionId}`
- `POST /v1/admin/plugins/{pluginId}/versions/{versionId}/approve`
- `DELETE /v1/admin/plugins/{pluginId}/versions/{versionId}`
- `GET|POST /v1/admin/blacklist`
- `DELETE /v1/admin/blacklist/{URL编码wxid}`
- SQLite 自动迁移、WAL 和外键约束
- 按安装标识与来源 IP 组合限频
- 插件所有权令牌校验
- 管理员密码登录、在线改密和删除
- 带 HttpOnly 签名会话的网页管理端
- 文件大小、文件名和 SHA-256 校验

列表与详情接口返回持久化的 `likeCount` 和 `commentCount`。详情还返回最新版本的文件内容和哈希，浏览详情不会增加下载数。响应中的四个默认文件保持原有顺序和字段；额外文件按 basename 的 UTF-8 字节序追加，文本文件返回 `content`，Base64 文件同时返回 `encoding: "base64"`。
历史版本列表将当前使用版本置顶，其余按最新写入在前返回元数据；读取指定历史版本会返回该版本的文件内容和 SHA-256，同样不会增加下载数。客户端只有在插件文件校验并安装成功后才调用 `POST /v1/plugins/{pluginId}/downloads` 记录一次下载，请求体为 `{"versionId":"v_...","eventId":"32 位小写十六进制"}`。同一 `eventId` 的网络重试幂等，不会重复计数。迁移 `006_download_events.sql` 会把旧版按详情浏览累计的错误下载量一次性清零，插件和历史版本内容保持不变。

## 环境

- Python 3.10 或更高版本
- Linux systemd 可选
- Nginx 和 HTTPS 建议用于公网部署

不需要安装 `requirements.txt`。

新服务器安装、旧服备份、停机迁移、回滚和可直接交给 AI 的迁移提示词见 [`DEPLOYMENT.md`](DEPLOYMENT.md)。仓库提供的 `deploy.sh` 可在 Debian/Ubuntu 上完成依赖、systemd、Nginx、HTTPS、数据库恢复和验收。

## 直接启动

```sh
python3 app.py \
  --host 127.0.0.1 \
  --port 8765 \
  --database ./data/plugin-market.db \
  --admin-password-file ./admin.password
```

首次启动会自动执行 `migrations/` 中尚未应用的 SQL。不要手工修改已经应用的迁移文件，数据库结构变化时应新增下一编号迁移。

可用参数：

```text
--host                  监听地址，默认 127.0.0.1
--port                  监听端口，默认 8765
--database              SQLite 数据库路径
--migrations            SQL 迁移目录
--rate-limit-count      单个窗口允许的上传请求数，默认 10
--rate-limit-window     上传限频窗口秒数，默认 3600
--admin-password-file   管理员密码文件；不配置则关闭管理员登录和删除
--trust-proxy           信任反向代理传入的客户端 IP
--log-level             DEBUG、INFO、WARNING 或 ERROR
```

直接将服务暴露到公网时不要开启 `--trust-proxy`。只有前方代理会覆盖 `X-Forwarded-For` 和 `X-Real-IP` 时才开启。

## 请求格式

所有响应格式：

```json
{
  "ok": true,
  "data": {},
  "requestId": "req_..."
}
```

错误响应：

```json
{
  "ok": false,
  "error": {
    "code": "MAIN_FILE_REQUIRED",
    "message": "main.java 必须存在且内容非空"
  },
  "requestId": "req_..."
}
```

### 创建插件

```http
POST /v1/plugins
Content-Type: application/json
X-Hchat-Install-Id: 550e8400-e29b-41d4-a716-446655440000
```

```json
{
  "displayName": "防撤回",
  "sourcePluginId": "anti_revoke",
  "versionName": "1.0.0",
  "releaseNotes": "首次发布",
  "author": "作者",
  "uploaderWxId": "wxid_example",
  "uploaderWeChatId": "example_wechat_id",
  "uploaderNickname": "上传者昵称",
  "files": [
    {"name": "main.java", "content": "void onLoad() {}"},
    {"name": "main.java.bshs", "encoding": "base64", "content": "QlNIUwABAgP/"},
    {"name": "README.md", "content": "# 防撤回"},
    {"name": "info.prop", "content": "name=防撤回"},
    {"name": "config.json", "encoding": "utf8", "content": "{\"enabled\":true}"},
    {"name": "helper.dex", "encoding": "base64", "content": "AAEC"}
  ]
}
```

创建成功返回 `pluginId`、`versionId` 和 `ownerToken`。服务端只保存所有权令牌的 SHA-256，客户端必须妥善保存原始令牌。可选的 `releaseNotes` 是该历史版本的更新说明，必须是 UTF-8 文本，最多 500 个字符。

每次创建或更新都必须传 `uploaderWxId`，它是上传者黑名单的稳定主键，只允许 ASCII 字母、数字、`_`、`@`、`.` 和 `-`，最长 128 个字符。`uploaderWeChatId` 和 `uploaderNickname` 可省略、传空字符串或传当前快照，分别最多 128 和 100 个字符。新版本会持久化三项身份；从旧数据库迁移的历史版本保持空值，管理页显示为“未知”。上传者身份只由管理员接口返回，公开插件接口不会返回这些字段。

当前协议没有微信账号登录或服务端签名证明，服务端只能持久化并按客户端上报的 `uploaderWxId` 执行黑名单。客户端必须从当前微信账号读取真实 wxid，不能允许用户手工替换该字段。

### 点赞与评论

点赞、取消点赞、发表评论和作者删除评论统一使用当前微信账号身份：

```json
{
  "userWxId": "wxid_example",
  "userWeChatId": "example_wechat_id",
  "userNickname": "用户昵称"
}
```

`userWxId` 必填，格式和长度规则与上传者 wxid 相同；微信号和昵称可省略，分别最多 128 和 100 个字符。点赞：

```http
POST /v1/plugins/p_.../likes
Content-Type: application/json
```

同一个插件和 `userWxId` 只能保存一条点赞。首次点赞返回 `201`、`created: true`，重复请求返回 `200`、`created: false` 并更新微信号和昵称快照，不会重复增加 `likeCount`。取消点赞使用相同身份请求：

客户端可读取当前安装身份的点赞状态：

```http
GET /v1/plugins/p_.../likes?userWxId=wxid_example
X-Hchat-Install-Id: <模块生成的随机安装标识>
```

响应返回 `liked` 和当前 `likeCount`。`liked` 只有在 wxid 与首次点赞的安装身份同时匹配时才为 `true`。

```http
DELETE /v1/plugins/p_.../likes
Content-Type: application/json
```

已删除时返回 `removed: true`；重复取消返回 `removed: false`，两种情况都会返回当前 `likeCount`。

发表评论时在身份字段外增加 `content`：

```http
POST /v1/plugins/p_.../comments
Content-Type: application/json
```

```json
{
  "userWxId": "wxid_example",
  "userWeChatId": "example_wechat_id",
  "userNickname": "用户昵称",
  "content": "这个插件很好用"
}
```

评论会去除首尾空白，不能为空，必须是有效 UTF-8，最多 1000 个字符。成功返回 `201`、评论对象和当前 `commentCount`。评论列表按最新在前返回，`limit` 默认为 50，允许 1 到 100：

```http
GET /v1/plugins/p_.../comments?limit=50
```

响应包含 `items`、本次返回的 `count`、总数 `total` 和 `limit`。`limit` 限制最新评论的基础窗口；窗口中存在回复时，服务端会额外返回构成完整回复链所需的祖先评论，因此 `count` 可能略大于 `limit`。公开评论对象只返回评论 ID、插件 ID、昵称、正文、时间、`parentCommentId`、`replyToNickname` 和服务端计算的 `canDelete`，不返回 wxid 或微信号。根评论的两个回复字段为空；回复通过 `parentCommentId` 关联到被回复的具体评论，客户端不能通过解析昵称或正文推断关系。未提供查看者身份时所有评论的 `canDelete` 都是 `false`；模块端读取当前安装身份的删除权限时使用：

```http
GET /v1/plugins/p_.../comments?limit=50&userWxId=wxid_example
X-Hchat-Install-Id: <模块生成的随机安装标识>
```

只有评论保存的安装身份哈希与当前 `安装标识 + userWxId` 完全一致时，服务端才返回 `canDelete: true`。评论作者删除自己的评论时提交同一身份 JSON：

```http
DELETE /v1/plugins/p_.../comments/c_...
Content-Type: application/json
```

回复评论使用同一身份 JSON 和 `content`，回复目标必须属于 URL 中的同一个公开插件：

```http
POST /v1/plugins/p_.../comments/c_.../replies
Content-Type: application/json
```

成功返回 `201`、带父评论 ID 和被回复昵称的新评论对象，以及包含回复后的 `commentCount`。可以回复根评论或已有回复；删除根评论会级联删除其全部后代回复。回复他人的评论会为被回复者创建一条通知，回复自己的评论不创建通知。

通知列表和已读接口继续使用当前账号身份与安装标识：

```http
GET /v1/notifications?userWxId=wxid_example&limit=100
X-Hchat-Install-Id: <模块生成的随机安装标识>
```

列表返回 `items`、`total`、`unreadCount` 和 `limit`。每条通知包含回复者昵称、插件名称、回复正文、原评论摘要和时间，不返回双方 wxid、微信号或身份哈希。标记全部通知已读时提交身份 JSON；只标记指定通知时额外传最多 100 个 `notificationIds`：

```http
POST /v1/notifications/read
Content-Type: application/json
```

服务端要求全部互动请求携带模块私有配置中已有的 `X-Hchat-Install-Id`，并保存 `安装标识 + wxid` 的 SHA-256 身份哈希，不保存原始安装标识。取消点赞、删除评论、读取通知和标记通知已读都必须同时匹配 wxid 与安装身份；只伪造 wxid 无法访问原安装身份的通知。管理员登录后可携带现有管理员会话 Cookie 删除任意评论，不需要请求体，但管理员会话不授予读取普通用户通知的权限。删除评论或插件会通过 SQLite 外键级联删除关联的回复和通知。

当前协议仍没有微信账号登录或服务端签名证明，无法证明首次声明某个 wxid 的客户端一定是该微信账号本人；安装身份校验用于阻止只伪造 wxid 的客户端删除已经存在的互动。模块端必须从当前微信账号读取身份并直接提交，不能提供手工修改 wxid 或安装标识的入口；若需要强账号身份保证，必须另行引入服务端可验证的账号认证协议。

### 更新插件

仍然请求 `POST /v1/plugins`，在创建请求基础上增加：

```json
{
  "pluginId": "p_...",
  "ownerToken": "创建时返回的令牌"
}
```

也可以通过 `Authorization: Bearer <ownerToken>` 传递所有权令牌。更新不能改变 `sourcePluginId`；相同 `versionName` 和 `contentHash` 视为同一个历史版本，不会新增版本。此时显式提供 `releaseNotes` 会更新该版本的更新说明，省略则保留原说明，显式传空字符串可清空。

### 删除插件

推荐使用请求头传递令牌：

```http
DELETE /v1/plugins/p_...
Authorization: Bearer <ownerToken>
```

也兼容 JSON 请求体：

```json
{"ownerToken": "创建时返回的令牌"}
```

管理员先使用 `--admin-password-file` 指定的密码登录，再通过 8 小时会话管理插件。服务启动时读取密码并只在内存中保留 SHA-256，鉴权使用恒定时间比较；未配置该参数时管理员登录、改密和删除关闭，普通所有者删除行为不变。管理员密码不能作为 Bearer 令牌使用。

部署后可访问 `https://你的域名/admin/` 使用网页管理端。登录时管理员密码只提交给后端，成功后输入框立即清空；后端签发有效期 8 小时、带 `HttpOnly`、`Secure`、`SameSite=Strict` 的签名会话 Cookie。网页脚本不能读取 Cookie，也不会把管理员密码写进源码、URL 或浏览器存储。单个 IP 在 15 分钟内连续登录或当前密码校验失败 5 次后会被临时限制。管理页右上角的“账户”折叠菜单统一提供修改密码和退出登录，改密表单在菜单内二次展开，不再占用管理内容区域；页面仍支持按插件和上传者身份搜索、在插件主行查看最近一次提交者、查看各历史版本上传者、直接拉黑、查看和解除黑名单、多选、全选当前筛选结果、批量删除，以及保留的单插件删除。改密会原子更新密码文件并立即注销全部管理会话，删除插件会同时删除全部历史版本。

管理员密码存放在仅 root 和专用服务账号可访问的目录中。服务账号需要替换该文件，以便后台改密能够持久化：

```sh
sudo install -d -o hchat-market -g hchat-market -m 0700 /etc/hchat-plugin-market
sudo python3 - <<'PY'
import getpass
from pathlib import Path
Path("/etc/hchat-plugin-market/admin.password").write_text(
    getpass.getpass("管理员密码: ") + "\n",
    encoding="utf-8",
)
PY
sudo chown hchat-market:hchat-market /etc/hchat-plugin-market/admin.password
sudo chmod 0600 /etc/hchat-plugin-market/admin.password
```

服务器安装管理脚本后，root 可直接删除任意用户上传的插件：

```sh
hchat-plugin-delete p_替换为实际插件ID
```

脚本默认读取 `/etc/hchat-plugin-market/admin.password`，先登录获取临时 Cookie，再执行删除，不会把密码作为 Bearer 令牌发送。需要管理其它部署时可通过 `HCHAT_PLUGIN_MARKET_URL` 和 `HCHAT_PLUGIN_MARKET_ADMIN_PASSWORD_FILE` 覆盖地址与密码路径。

管理员网页还可以在 `/v1/admin/settings` 持久化切换插件上传审核。审核开启后，新上传版本返回 `reviewStatus: "pending"`，新插件不会出现在公开列表；已有插件继续提供上一个已通过版本，待审核版本不会替换它。管理员可在 `/v1/admin/plugins` 查看全部插件和版本，通过 `POST .../approve` 发布版本，或用 `DELETE .../versions/{versionId}` 驳回/删除单个未发布版本。已发布的当前版本不能单独删除，必须先通过其它版本替代；关闭审核时会自动发布现有待审核版本。

### 管理员批量删除

```http
POST /v1/admin/plugins/batch-delete
Content-Type: application/json
Cookie: __Host-hchat_admin_session=...
```

```json
{"pluginIds":["p_...","p_..."]}
```

一次最多提交 100 个不重复且格式有效的插件 ID。接口在一个 SQLite 事务中处理全部目标，并按请求顺序返回 `items`；存在的项目返回 `deleted: true`，不存在的项目返回 `deleted: false` 和 `PLUGIN_NOT_FOUND`，同时返回 `requestedCount`、`deletedCount`、`failedCount`。原有 `DELETE /v1/plugins/{pluginId}` 单删接口保持不变。

### 上传者黑名单

管理员添加或更新黑名单身份快照：

```http
POST /v1/admin/blacklist
Content-Type: application/json
Cookie: __Host-hchat_admin_session=...
```

```json
{
  "uploaderWxId": "wxid_example",
  "uploaderWeChatId": "example_wechat_id",
  "uploaderNickname": "上传者昵称"
}
```

首次拉黑返回 `201` 和 `created: true`；重复拉黑同一 wxid 会更新微信号和昵称快照、保留原拉黑时间，并返回 `200` 和 `created: false`。`GET /v1/admin/blacklist` 返回全部黑名单；`DELETE /v1/admin/blacklist/{URL编码wxid}` 解除拉黑。黑名单检查在上传写事务内执行，被拉黑 wxid 的新建和更新都返回 `403`，稳定错误码为 `UPLOADER_BLACKLISTED`。

`main.java.bshs` 是可选的 BeanShell 加密二进制文件。服务端不执行加密或解密，只校验 `BSHS` 文件头、严格解码标准 Base64，并将原始字节存入 SQLite BLOB。请求中的 `encoding` 必须为 `base64`；可选的 `size`、`sha256` 一旦提供，必须与解码后的原始字节一致。响应始终包含按原字节计算的 `size` 和 SHA-256，并以规范 Base64 返回内容。`main.java` 仍然必须存在且非空。

除四个默认文件外，还可以上传依赖文件。额外文件名必须是安全 basename：不能包含 `/`、`\\`、NUL、控制字符、`.` 或 `..`，且不能与默认文件或其它额外文件发生大小写不敏感的重名。额外文件省略 `encoding` 或使用 `encoding: "utf8"`/`"utf-8"` 时按 UTF-8 文本校验和保存；使用 `encoding: "base64"` 时按严格、规范 Base64 校验和保存。额外文件同样支持可选的 `size` 和 `sha256`，响应和公开历史版本下载会原样返回这些文件。

### 历史版本

获取插件的历史版本列表，当前使用版本置顶，其余版本按最新写入在前排列。API 路径继续使用 `snapshots`：

```http
GET /v1/plugins/p_.../snapshots
```

每项包含 `versionId`、`versionName`、`releaseNotes`、`contentHash`、`totalSize` 和 `createdAt`，当前使用的版本始终排在最前。重复上传与任一已有历史版本相同的 `versionName` 和 `contentHash` 时会复用原 `versionId`，不会新增历史版本，并将该版本重新设为当前版本。

获取指定历史版本及其文件内容和 SHA-256：

```http
GET /v1/plugins/p_.../snapshots/v_...
```

`versionId` 必须属于路径中的 `pluginId`；使用其它插件的版本 ID 会返回 `SNAPSHOT_NOT_FOUND`。

### 文件限制

默认文件限制：

```text
main.java        512 KiB，UTF-8 文本，必须存在且非空
main.java.bshs    16 MiB，Base64 传输、BLOB 保存，可选
README.md        256 KiB，UTF-8 文本，可选
info.prop         64 KiB，UTF-8 文本，可选
额外文件          最多 32 个，每个 16 MiB，UTF-8 文本或 Base64 二进制
文件总数          最多 36 个（包含四个默认文件）
总量              32 MiB（按解码后的原始字节计算）
```

HTTP JSON 请求体最大为 48 MiB，用于容纳 Base64 和 JSON 转义产生的额外体积。

## systemd

1. 创建专用用户并安装文件：

```sh
sudo useradd --system --home /nonexistent --shell /usr/sbin/nologin hchat-market
sudo mkdir -p /opt/hchat-plugin-market
sudo cp -a app.py migrations static /opt/hchat-plugin-market/
sudo cp examples/hchat-plugin-market.service /etc/systemd/system/
sudo install -m 0755 examples/hchat-plugin-delete /usr/local/bin/hchat-plugin-delete
sudo install -d -o hchat-market -g hchat-market -m 0700 /etc/hchat-plugin-market
sudo python3 - <<'PY'
import getpass
from pathlib import Path
Path("/etc/hchat-plugin-market/admin.password").write_text(
    getpass.getpass("管理员密码: ") + "\n",
    encoding="utf-8",
)
PY
sudo chown hchat-market:hchat-market /etc/hchat-plugin-market/admin.password
sudo chmod 0600 /etc/hchat-plugin-market/admin.password
```

2. 启动服务：

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now hchat-plugin-market
sudo systemctl status hchat-plugin-market
```

`StateDirectory=hchat-plugin-market` 会创建 `/var/lib/hchat-plugin-market` 并交给服务用户写入。

## Nginx 反向代理

复制并修改示例：

```sh
sudo cp examples/nginx.conf /etc/nginx/sites-available/hchat-plugin-market.conf
sudo ln -s /etc/nginx/sites-available/hchat-plugin-market.conf /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

必须替换 `plugins.example.com` 和证书路径。示例会覆盖客户端传入的代理 IP 请求头，因此后端开启 `--trust-proxy` 后不会直接信任公网用户伪造的 `X-Forwarded-For`。
示例的 `client_max_body_size 48m` 必须与后端请求体上限保持一致，否则较大的 `.bshs` 或附加文件会在到达后端前被 Nginx 拒绝。

## 测试

```sh
python3 -m unittest discover -s tests -v
```

测试使用临时 SQLite 数据库和随机本地端口，不会修改正式数据库。

## 备份

SQLite 使用 WAL。在线备份建议使用 SQLite 自带备份命令，而不是只复制主数据库文件：

```sh
sqlite3 /var/lib/hchat-plugin-market/plugin-market.db \
  ".backup '/var/backups/hchat-plugin-market.db'"
```

## 当前生产部署

```text
程序目录        /opt/hchat-plugin-market
数据库          /var/lib/hchat-plugin-market/plugin-market.db
systemd 单元    hchat-plugin-market.service
Nginx 站点      /etc/nginx/sites-available/hchat-plugin-market.conf
HTTPS 域名      hchat.103.97.179.142.sslip.io
证书            /etc/letsencrypt/live/hchat.103.97.179.142.sslip.io/
```

更新服务端代码后先运行测试，再替换 `/opt/hchat-plugin-market/app.py` 和新增的迁移文件，最后执行：

```sh
sudo systemctl restart hchat-plugin-market
sudo systemctl status hchat-plugin-market
curl --fail https://hchat.103.97.179.142.sslip.io/health
```

证书续期由 `certbot.timer` 管理，可用 `sudo certbot renew --dry-run` 验证。不要重建或覆盖服务器上其它 Nginx 站点、`127.0.0.1:8000` API、PostgreSQL 或 Redis 服务。
