# Hchat 在线插件市场部署与迁移

这份文档面向没有服务器部署经验的维护者。仓库内的 `deploy.sh` 会安装依赖、部署服务、恢复数据、配置独立 Nginx 站点、申请 HTTPS 证书，并验证本机和公网健康状态。重复执行同一条命令不会新建重复服务，也不会清空现有数据库或管理员密码。

## 当前架构

公网请求先到 Nginx，再反向代理到仅监听 `127.0.0.1:8765` 的 Python 服务。管理页模板随仓库 `static/` 目录安装，由 Python 服务在 `/admin/` 输出；Python 服务使用 SQLite，并在启动时自动按顺序执行尚未应用的数据库迁移。

默认路径：

| 内容 | 路径 |
| --- | --- |
| 应用与迁移 | `/opt/hchat-plugin-market` |
| 静态资源 | `/opt/hchat-plugin-market/static` |
| SQLite 数据库 | `/var/lib/hchat-plugin-market/plugin-market.db` |
| 管理员密码 | `/etc/hchat-plugin-market/admin.password` |
| systemd 服务 | `/etc/systemd/system/hchat-plugin-market.service` |
| Nginx 站点 | `/etc/nginx/sites-available/hchat-plugin-market.conf` |
| Nginx 启用链接 | `/etc/nginx/sites-enabled/hchat-plugin-market.conf` |
| 证书 | `/etc/letsencrypt/live/<域名>/` |
| 部署前自动备份 | `/var/backups/hchat-plugin-market/` |
| 管理页面 | `https://<域名>/admin/` |

生产环境不应开放 `8765` 端口，只需在云服务器安全组和系统防火墙中放行 TCP `22`、`80`、`443`。Nginx 不单独处理管理页，所有应用请求统一代理给后端。管理员密码不会嵌入网页或保存在浏览器存储中；登录时页面只把密码提交给后端，后端验证后签发有效期 8 小时的 HttpOnly 签名会话 Cookie。后续管理请求使用该会话，前端 JavaScript 无法读取 Cookie，管理员密码也不能作为 Bearer 令牌使用。后台改密成功后会原子更新密码文件并立即使全部旧会话失效。

## 新服务器要求

- Debian 12 或 Ubuntu 22.04 及以上版本。
- root 权限，或者普通用户可以使用 `sudo`。
- 至少有一个域名已经解析到新服务器公网 IP。
- 云安全组和防火墙已放行 TCP `80`、`443`。
- 仓库已下载到新服务器，执行命令时位于仓库目录。

推荐使用自己长期持有的稳定域名，例如 `plugins.example.com`。`sslip.io` 的域名包含服务器 IP；服务器 IP 改变后，域名也必须从包含旧 IP 的域名改为包含新 IP 的新域名。域名一旦变化，Android 客户端中的在线插件市场默认 URL 也必须更新并重新发布，否则旧客户端仍会访问到期服务器。

## 一键部署

全新安装：

```sh
sudo sh server/plugin-market/deploy.sh \
  --domain plugins.example.com \
  --email admin@example.com
```

从旧服务器迁移数据库和管理员密码：

```sh
sudo sh server/plugin-market/deploy.sh \
  --domain plugins.example.com \
  --email admin@example.com \
  --restore-db /root/hchat-market-migration/plugin-market.db \
  --restore-admin-password /root/hchat-market-migration/admin.password
```

`--domain` 只填域名，不要带 `https://`、端口或路径。全新部署且未传 `--restore-admin-password` 时，脚本会在终端安全提示输入两次管理员密码；不会把密码写入命令行、日志或仓库。目标服务器已有数据库或管理员密码时会保留现有文件；数据库存在时还会先生成一份部署前备份。

脚本会安装 Python、Nginx、Certbot、curl、SQLite、CA 证书和 OpenSSL，创建低权限的 `hchat-market` 系统用户，安装应用、完整 `static` 目录、迁移、服务与删除命令。它只管理名为 `hchat-plugin-market.conf` 的 Nginx 站点，不删除默认站点，也不修改其它站点。Nginx 上传请求体上限固定为 `48m`，与后端 48 MiB JSON 请求上限对应。

## 旧服务器备份

SQLite 使用 WAL，不能在服务运行时只复制 `plugin-market.db`。先制作在线预备备份：

```sh
sudo install -d -m 0700 /root/hchat-market-migration
sudo sqlite3 /var/lib/hchat-plugin-market/plugin-market.db \
  ".backup '/root/hchat-market-migration/plugin-market.db'"
sudo install -m 0600 /etc/hchat-plugin-market/admin.password \
  /root/hchat-market-migration/admin.password
sudo sha256sum \
  /root/hchat-market-migration/plugin-market.db \
  /root/hchat-market-migration/admin.password \
  | sudo tee /root/hchat-market-migration/SHA256SUMS
```

检查数据库：

```sh
sudo sqlite3 /root/hchat-market-migration/plugin-market.db "PRAGMA quick_check;"
```

输出必须为 `ok`。通过 `scp`、SFTP 或云厂商文件传输功能把整个 `/root/hchat-market-migration/` 安全传到新服务器；不要把数据库、管理员密码、服务器密码提交到 Git。

## 停机迁移顺序

为了避免旧服在最终备份后仍收到新上传，正式切换按以下顺序操作：

1. 提前在旧服务器完成一次预备备份，并先在新服务器准备好仓库和系统环境。
2. 如果使用自有域名，提前把 DNS TTL 调低；正式切换时把域名解析改到新服务器 IP。
3. 在旧服务器执行 `sudo systemctl stop hchat-plugin-market`，停止写入。
4. 再执行一次上面的 SQLite `.backup`，覆盖迁移目录中的预备数据库，并重新计算 `SHA256SUMS`。
5. 把最终数据库和管理员密码传到新服务器。
6. 在新服务器执行带 `--restore-db` 和 `--restore-admin-password` 的部署命令。
7. 等脚本明确显示本机健康检查、公网 HTTPS 健康检查和管理页检查全部通过。
8. 如果域名发生变化，同步修改客户端默认市场 URL，构建并发布新版客户端。
9. 观察新服务正常后再释放旧服务器；切换期间不要同时开放两台可写服务器。

使用 `sslip.io` 时不需要等待传统 DNS 配置，但新 IP 对应的是一个全新的域名。建议迁移前改用稳定自有域名，以后只更新 DNS 记录，客户端 URL 无需跟着服务器 IP 改动。

## 管理页面

部署完成后访问：

```text
https://你的域名/admin/
```

页面支持管理员登录；登录后可从右上角“账户”折叠菜单修改登录密码或退出登录，修改密码表单在菜单内二次展开。页面还支持按插件或上传者身份搜索、刷新、审核插件上传、在插件主行查看最近一次提交者、查看每个历史版本的上传者 wxid/微信号/昵称、拉黑或解除拉黑上传者、多选和全选当前筛选结果、批量删除整插件，以及删除单个历史版本。管理员密码位于新服务器：

```text
/etc/hchat-plugin-market/admin.password
```

在管理页直接输入部署时设置的管理员密码。登录请求由后端校验，输入框随后会被清空；后端返回有效期 8 小时、带 `HttpOnly`、`Secure` 和 `SameSite=Strict` 属性的签名会话 Cookie。页面脚本不能读取该 Cookie，管理员密码不会作为 Bearer 令牌发送。单个 IP 在 15 分钟内连续登录或当前密码校验失败 5 次后会被临时限制。修改密码成功后当前页面自动退出，其它设备上的旧会话也立即失效，之后只能使用新密码登录。打开“插件上传审核”后，新上传版本会在这里显示待审核，可预览文本文件、查看加密文件哈希并通过或驳回；关闭审核会自动发布已有待审核版本。退出登录或会话到期后需要重新输入密码。批量删除按当前筛选结果选择，服务端会逐项返回成功或失败；单个历史版本可单独删除，但当前公开版本不能直接删除。

升级现有部署时，启动服务会依次执行尚未登记的迁移。`007_uploader_identity_blacklist.sql` 为历史版本增加上传者身份字段并创建黑名单表；旧版本身份保持空值并在管理页显示“未知”。`008_plugin_social.sql` 新建插件点赞和评论表；`009_comment_replies_notifications.sql` 为评论增加父评论关系并创建回复通知表，已有评论保持根评论状态，现有插件、历史版本、所有者令牌、黑名单、下载统计和互动数据都不会被重写。迁移由 `schema_migrations` 保证只执行一次，重复部署不会重复建表或清空互动数据。部署前仍会按现有流程自动备份数据库。

命令行删除仍可作为管理页不可用时的备用方式：

```sh
sudo hchat-plugin-delete p_替换为实际插件ID
```

## 验收清单

部署脚本已经自动检查关键路径，仍建议人工确认：

```sh
sudo systemctl --no-pager -l status hchat-plugin-market
sudo nginx -t
curl --fail http://127.0.0.1:8765/health
curl --fail https://plugins.example.com/health
curl --fail https://plugins.example.com/admin/
curl --fail https://plugins.example.com/v1/admin/settings
sudo sqlite3 /var/lib/hchat-plugin-market/plugin-market.db "PRAGMA quick_check;"
sudo sqlite3 /var/lib/hchat-plugin-market/plugin-market.db \
  "SELECT version, name FROM schema_migrations WHERE version IN (7, 8, 9) ORDER BY version;"
sudo sqlite3 /var/lib/hchat-plugin-market/plugin-market.db \
  "SELECT COUNT(*) FROM uploader_blacklist;"
sudo sqlite3 /var/lib/hchat-plugin-market/plugin-market.db \
  "SELECT COUNT(*) AS likes FROM plugin_likes; SELECT COUNT(*) AS comments FROM plugin_comments; SELECT COUNT(*) AS notifications FROM plugin_notifications;"
sudo systemctl status certbot.timer
```

然后在客户端完成一次插件列表加载、带三项上传者身份的新建和更新、历史版本查看及下载。再用两个测试账号验证重复点赞不重复计数、取消点赞幂等、评论与回复串、回复通知和已读角标、作者只能删除自己的评论、删除根评论级联删除回复、伪造同一 wxid 但使用不同安装标识仍无法删除或读取通知，以及管理员会话可以删除任意评论但不能读取普通用户通知。在管理页使用测试 wxid 验证拉黑后新建/更新返回 `UPLOADER_BLACKLISTED`，解除后恢复上传，并用专门创建的测试插件验证多选批量删除；不要拿真实插件验证删除。

## 回滚

每次部署前，脚本会把现有数据库备份到：

```text
/var/backups/hchat-plugin-market/plugin-market.pre-deploy.<UTC时间>.db
```

数据库回滚：

```sh
sudo systemctl stop hchat-plugin-market
sudo rm -f \
  /var/lib/hchat-plugin-market/plugin-market.db-wal \
  /var/lib/hchat-plugin-market/plugin-market.db-shm
sudo install -o hchat-market -g hchat-market -m 0640 \
  /var/backups/hchat-plugin-market/plugin-market.pre-deploy.替换时间.db \
  /var/lib/hchat-plugin-market/plugin-market.db
sudo systemctl start hchat-plugin-market
curl --fail http://127.0.0.1:8765/health
```

代码回滚应在仓库中切换到已知正常的提交，再用相同域名和邮箱重新运行 `deploy.sh`。不要修改或删除已经在数据库中登记为执行过的迁移文件；需要修正结构时应新增下一编号迁移。回滚前保留当前数据库副本，避免旧代码无法识别新结构时丢失数据。

## 常见故障

### 证书申请失败

确认域名 A/AAAA 记录指向新服务器，TCP `80` 和 `443` 已同时在云安全组与系统防火墙放行，并确认没有 CDN 或代理把 ACME 请求转到其它服务器：

```sh
getent ahosts plugins.example.com
sudo nginx -t
sudo journalctl -u nginx -n 100 --no-pager
```

修复后原样重跑部署脚本即可。

### 本机健康检查失败

```sh
sudo systemctl --no-pager -l status hchat-plugin-market
sudo journalctl -u hchat-plugin-market -n 100 --no-pager
sudo -u hchat-market test -r /etc/hchat-plugin-market/admin.password
sudo -u hchat-market test -w /etc/hchat-plugin-market
```

常见原因是数据库损坏、恢复文件权限错误、迁移文件缺失或 Python 版本低于 3.10。

### 公网健康检查失败，但本机正常

检查域名、证书、Nginx 和防火墙：

```sh
curl -v https://plugins.example.com/health
sudo nginx -t
sudo tail -n 100 /var/log/nginx/error.log
```

### 上传返回 413

确认当前 Nginx 站点包含 `client_max_body_size 48m;`，并确认请求确实进入本脚本创建的域名站点。后端 JSON 请求体上限也是 48 MiB，单个附加文件上限为 16 MiB，插件解码后的总文件上限为 32 MiB。

### 管理页打不开或提示密码错误

```sh
curl -I https://plugins.example.com/admin/
sudo test -r /etc/hchat-plugin-market/admin.password
sudo ls -l /etc/hchat-plugin-market/admin.password
sudo -u hchat-market test -w /etc/hchat-plugin-market
```

确认访问路径为 `/admin/`，并检查 `/opt/hchat-plugin-market/static/admin.html` 已安装。全新部署会要求设置管理员密码，迁移时则应恢复旧密码文件；这不影响插件所有者令牌。登录成功后由后端维持 8 小时 HttpOnly 会话；浏览器禁用 Cookie、HTTPS 异常、会话到期或修改管理员密码都会要求重新登录。

### 数据库恢复后插件为空

先停止新服务，不要继续上传。对比旧服备份与新服数据库的 SHA-256，并分别执行 `PRAGMA quick_check;`。确认部署时传入的是 `.backup` 生成的最终数据库，而不是旧服运行期间直接复制的不完整主文件。

## 可直接发给 AI 的迁移提示词

把尖括号内容替换成真实信息后，可将下面整段发送给能登录服务器的 AI：

```text
请帮我把 Hchat 在线插件市场从旧服务器完整迁移到一台 Debian/Ubuntu 新服务器，并实际执行、验证，不要只给步骤。

仓库目录：<新服务器上的仓库绝对路径>
新域名：<例如 plugins.example.com，只写域名>
Let's Encrypt 邮箱：<邮箱>
旧服务器 SSH 地址：<旧服务器地址>
新服务器 SSH 地址：<新服务器地址>

要求：
1. 先阅读 server/plugin-market/DEPLOYMENT.md 和 deploy.sh，核对当前 app.py、migrations、systemd service 与 helper，不要猜路径。
2. 不要把 SSH 密码、管理员密码、数据库或证书提交到 Git，也不要在聊天输出管理员密码正文。
3. 旧服务器使用 SQLite .backup 生成 /root/hchat-market-migration/plugin-market.db，复制 /etc/hchat-plugin-market/admin.password，并校验 SHA-256 与 PRAGMA quick_check；不要直接复制运行中的 SQLite 主文件。
4. 最终切换时停止旧服，重新生成最终备份，再传到新服；不要让两台服务器同时对外写入。
5. 确认域名已指向新服务器、TCP 80/443 已放行。使用 sslip.io 时按新 IP 更换域名，并明确指出客户端默认市场 URL 也必须更新；优先建议稳定自有域名。
6. 在新服务器仓库目录执行：sudo sh server/plugin-market/deploy.sh --domain <新域名> --email <邮箱> --restore-db <新服务器上的数据库路径> --restore-admin-password <新服务器上的密码路径>。
7. 不要删除或覆盖其它 Nginx 站点、其它 systemd 服务、数据库、Redis 或网站。只管理 hchat-plugin-market 对应路径。
8. 部署失败时读取 systemctl、journalctl、nginx -t、Nginx 日志和 certbot 输出，修复后继续，不能跳过错误。
9. 验证 systemd 服务、SQLite quick_check、本机 /health、公网 HTTPS /health、https://<新域名>/admin/、管理员登录与 8 小时 HttpOnly 会话、证书续期计时器，以及客户端列表/上传/审核待审核状态/历史版本/下载流程。
10. 若域名变化，定位并修改 Android 客户端的默认在线市场 URL，说明需要重新构建发布；不要擅自提交或推送，除非我另行授权。
11. 最后汇报新服务 URL、管理页 URL、数据库和备份路径、证书状态、各项验收结果，以及仍需人工完成的 DNS 或客户端发布事项。不要汇报任何密钥正文。
```
