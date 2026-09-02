#!/bin/sh
set -eu

umask 027

SERVICE_NAME="hchat-plugin-market"
SERVICE_USER="hchat-market"
APP_DIR="/opt/hchat-plugin-market"
STATE_DIR="/var/lib/hchat-plugin-market"
DATABASE_PATH="$STATE_DIR/plugin-market.db"
CONFIG_DIR="/etc/hchat-plugin-market"
ADMIN_PASSWORD_PATH="$CONFIG_DIR/admin.password"
BACKUP_DIR="/var/backups/hchat-plugin-market"
NGINX_AVAILABLE="/etc/nginx/sites-available/hchat-plugin-market.conf"
NGINX_ENABLED="/etc/nginx/sites-enabled/hchat-plugin-market.conf"
ACME_ROOT="/var/www/hchat-plugin-market-acme"

DOMAIN=""
EMAIL=""
RESTORE_DB=""
RESTORE_ADMIN_PASSWORD=""
SERVICE_WAS_STOPPED=0
TEMP_DIR=""

log() {
    printf '\n[Hchat 部署] %s\n' "$*"
}

die() {
    printf '\n[Hchat 部署失败] %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
用法：
  sudo sh server/plugin-market/deploy.sh \
    --domain plugins.example.com \
    --email admin@example.com \
    [--restore-db /path/plugin-market.db] \
    [--restore-admin-password /path/admin.password]

参数：
  --domain                 必填。公网域名，不要包含 http://、https:// 或路径
  --email                  必填。Let's Encrypt 到期通知邮箱
  --restore-db             可选。由 SQLite .backup 生成的旧服务器数据库
  --restore-admin-password 可选。旧服务器管理员密码文件；全新部署不传时安全提示输入
  -h, --help               显示帮助
EOF
}

cleanup() {
    status=$?
    trap - EXIT
    if [ -n "$TEMP_DIR" ] && [ -d "$TEMP_DIR" ]; then
        rm -rf "$TEMP_DIR"
    fi
    if [ "$status" -ne 0 ] && [ "$SERVICE_WAS_STOPPED" -eq 1 ]; then
        printf '\n[Hchat 部署] 部署中断，尝试恢复启动原服务...\n' >&2
        systemctl start "$SERVICE_NAME" >/dev/null 2>&1 || true
    fi
    exit "$status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

while [ "$#" -gt 0 ]; do
    case "$1" in
        --domain)
            [ "$#" -ge 2 ] || die "--domain 缺少参数"
            DOMAIN=$2
            shift 2
            ;;
        --email)
            [ "$#" -ge 2 ] || die "--email 缺少参数"
            EMAIL=$2
            shift 2
            ;;
        --restore-db)
            [ "$#" -ge 2 ] || die "--restore-db 缺少参数"
            RESTORE_DB=$2
            shift 2
            ;;
        --restore-admin-password)
            [ "$#" -ge 2 ] || die "--restore-admin-password 缺少参数"
            RESTORE_ADMIN_PASSWORD=$2
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            die "未知参数: $1"
            ;;
    esac
done

[ "$(id -u)" -eq 0 ] || die "请用 root 登录，或在命令前添加 sudo"
[ -n "$DOMAIN" ] || { usage >&2; die "必须提供 --domain"; }
[ -n "$EMAIL" ] || { usage >&2; die "必须提供 --email"; }

case "$DOMAIN" in
    http://*|https://*|*/*|*:*|.*|*..*|*.)
        die "域名格式无效，请只填写类似 plugins.example.com 的主机名"
        ;;
esac
printf '%s\n' "$DOMAIN" | grep -Eq '^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$' \
    || die "域名只能包含字母、数字、点和连字符"
printf '%s\n' "$DOMAIN" | grep -q '\.' || die "域名必须包含至少一个点"

case "$EMAIL" in
    *@*.*) ;;
    *) die "邮箱格式无效: $EMAIL" ;;
esac
printf '%s\n' "$EMAIL" | grep -Eq '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' \
    || die "邮箱格式无效: $EMAIL"

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_SOURCE="$SCRIPT_DIR/app.py"
MIGRATIONS_SOURCE="$SCRIPT_DIR/migrations"
STATIC_SOURCE="$SCRIPT_DIR/static"
SERVICE_SOURCE="$SCRIPT_DIR/examples/hchat-plugin-market.service"
HELPER_SOURCE="$SCRIPT_DIR/examples/hchat-plugin-delete"

[ -f "$APP_SOURCE" ] || die "找不到 $APP_SOURCE，请从仓库内执行本脚本"
[ -d "$MIGRATIONS_SOURCE" ] || die "找不到 $MIGRATIONS_SOURCE"
[ -d "$STATIC_SOURCE" ] || die "找不到 $STATIC_SOURCE"
[ -f "$STATIC_SOURCE/admin.html" ] || die "找不到管理页模板 $STATIC_SOURCE/admin.html"
[ -f "$SERVICE_SOURCE" ] || die "找不到 $SERVICE_SOURCE"
[ -f "$HELPER_SOURCE" ] || die "找不到 $HELPER_SOURCE"

if [ -n "$RESTORE_DB" ]; then
    [ -r "$RESTORE_DB" ] || die "无法读取待恢复数据库: $RESTORE_DB"
fi
if [ -n "$RESTORE_ADMIN_PASSWORD" ]; then
    [ -r "$RESTORE_ADMIN_PASSWORD" ] || die "无法读取待恢复管理员密码: $RESTORE_ADMIN_PASSWORD"
fi

TEMP_DIR=$(mktemp -d /tmp/hchat-market-deploy.XXXXXX) \
    || die "无法创建部署临时目录"

log "安装系统依赖"
export DEBIAN_FRONTEND=noninteractive
if ! apt-get update; then
    die "apt-get update 失败，请检查软件源和网络"
fi
if ! apt-get install -y python3 nginx certbot curl sqlite3 ca-certificates openssl; then
    die "依赖安装失败，请检查 apt 输出后重试"
fi

python3 - <<'PY' || die "需要 Python 3.10 或更高版本；建议使用 Debian 12 或 Ubuntu 22.04 及以上版本"
import sys
raise SystemExit(0 if sys.version_info >= (3, 10) else 1)
PY
command -v systemctl >/dev/null 2>&1 || die "当前系统没有 systemd，无法安装服务"
command -v nginx >/dev/null 2>&1 || die "Nginx 安装后仍不可用"
command -v certbot >/dev/null 2>&1 || die "Certbot 安装后仍不可用"

if [ -n "$RESTORE_DB" ]; then
    DB_CHECK=$(sqlite3 "$RESTORE_DB" 'PRAGMA quick_check;' 2>&1) \
        || die "待恢复数据库无法打开: $DB_CHECK"
    [ "$DB_CHECK" = "ok" ] || die "待恢复数据库完整性检查失败: $DB_CHECK"
fi

if [ -n "$RESTORE_ADMIN_PASSWORD" ]; then
    python3 - "$RESTORE_ADMIN_PASSWORD" <<'PY' \
        || die "待恢复管理员密码无效：必须是 1 至 256 个字符的单行非空文本"
import pathlib
import sys

value = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").strip()
valid = bool(value) and len(value) <= 256 and not any(character.isspace() for character in value)
raise SystemExit(0 if valid else 1)
PY
fi

log "创建专用用户和目录"
if ! getent passwd "$SERVICE_USER" >/dev/null 2>&1; then
    useradd --system --home /nonexistent --shell /usr/sbin/nologin "$SERVICE_USER" \
        || die "创建系统用户 $SERVICE_USER 失败"
fi
install -d -o root -g root -m 0755 "$APP_DIR" "$APP_DIR/migrations"
install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0750 "$STATE_DIR"
install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0700 "$CONFIG_DIR"
install -d -o root -g root -m 0750 "$BACKUP_DIR"
install -d -o www-data -g www-data -m 0755 "$ACME_ROOT/.well-known/acme-challenge"

log "安装应用、静态资源、数据库迁移、systemd 服务和管理命令"
install -o root -g root -m 0755 "$APP_SOURCE" "$APP_DIR/app.py"
rm -rf "$APP_DIR/static"
install -d -o root -g root -m 0755 "$APP_DIR/static"
cp -a "$STATIC_SOURCE/." "$APP_DIR/static/"
chown -R root:root "$APP_DIR/static"
find "$APP_DIR/static" -type d -exec chmod 0755 {} +
find "$APP_DIR/static" -type f -exec chmod 0644 {} +
find "$APP_DIR/migrations" -maxdepth 1 -type f -name '*.sql' -delete
MIGRATION_COUNT=0
for migration in "$MIGRATIONS_SOURCE"/*.sql; do
    [ -f "$migration" ] || continue
    install -o root -g root -m 0644 "$migration" "$APP_DIR/migrations/$(basename "$migration")"
    MIGRATION_COUNT=$((MIGRATION_COUNT + 1))
done
[ "$MIGRATION_COUNT" -gt 0 ] || die "仓库中没有可安装的数据库迁移"
install -o root -g root -m 0644 "$SERVICE_SOURCE" \
    "/etc/systemd/system/$SERVICE_NAME.service"
sed "s#^SERVICE_URL=.*#SERVICE_URL=\${HCHAT_PLUGIN_MARKET_URL:-https://$DOMAIN}#" \
    "$HELPER_SOURCE" > "$TEMP_DIR/hchat-plugin-delete"
install -o root -g root -m 0755 "$TEMP_DIR/hchat-plugin-delete" \
    /usr/local/bin/hchat-plugin-delete


log "配置管理员密码"
if [ -n "$RESTORE_ADMIN_PASSWORD" ]; then
    cp "$RESTORE_ADMIN_PASSWORD" "$TEMP_DIR/admin.password"
    install -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0600 "$TEMP_DIR/admin.password" "$ADMIN_PASSWORD_PATH"
elif [ ! -s "$ADMIN_PASSWORD_PATH" ]; then
    python3 - "$TEMP_DIR/admin.password" <<'PY' \
        || die "无法读取管理员密码；非交互部署请使用 --restore-admin-password 指定密码文件"
import getpass
import pathlib
import sys

password = getpass.getpass("请输入管理员密码: ")
confirmation = getpass.getpass("请再次输入管理员密码: ")
if password != confirmation:
    raise SystemExit("两次输入的管理员密码不一致")
if not password or len(password) > 256 or any(character.isspace() for character in password):
    raise SystemExit("管理员密码必须是 1 至 256 个字符且不能包含空白字符")
pathlib.Path(sys.argv[1]).write_text(password + "\n", encoding="utf-8")
PY
    install -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0600 "$TEMP_DIR/admin.password" "$ADMIN_PASSWORD_PATH"
else
    chown "$SERVICE_USER":"$SERVICE_USER" "$ADMIN_PASSWORD_PATH"
    chmod 0600 "$ADMIN_PASSWORD_PATH"
    log "保留现有管理员密码"
fi

systemctl daemon-reload
if systemctl is-active --quiet "$SERVICE_NAME"; then
    systemctl stop "$SERVICE_NAME" || die "无法停止现有服务"
    SERVICE_WAS_STOPPED=1
fi

if [ -s "$DATABASE_PATH" ]; then
    TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
    PRE_DEPLOY_BACKUP="$BACKUP_DIR/plugin-market.pre-deploy.$TIMESTAMP.db"
    log "备份当前数据库到 $PRE_DEPLOY_BACKUP"
    sqlite3 "$DATABASE_PATH" ".timeout 10000" ".backup '$PRE_DEPLOY_BACKUP'" \
        || die "部署前数据库备份失败"
    chmod 0600 "$PRE_DEPLOY_BACKUP"
fi

if [ -n "$RESTORE_DB" ]; then
    log "恢复数据库 $RESTORE_DB"
    cp "$RESTORE_DB" "$TEMP_DIR/plugin-market.db"
    rm -f "$DATABASE_PATH-wal" "$DATABASE_PATH-shm"
    install -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0640 \
        "$TEMP_DIR/plugin-market.db" "$DATABASE_PATH"
fi

log "启动后端并执行数据库迁移"
if ! systemctl enable "$SERVICE_NAME" >/dev/null; then
    die "无法启用 $SERVICE_NAME"
fi
if ! systemctl restart "$SERVICE_NAME"; then
    systemctl --no-pager -l status "$SERVICE_NAME" >&2 || true
    journalctl -u "$SERVICE_NAME" -n 80 --no-pager >&2 || true
    die "后端服务启动失败"
fi
SERVICE_WAS_STOPPED=0

wait_for_health() {
    url=$1
    label=$2
    attempts=0
    while [ "$attempts" -lt 30 ]; do
        if curl --fail --silent --show-error --max-time 8 "$url" > "$TEMP_DIR/health.json" 2>/dev/null \
            && grep -Eq '"ok"[[:space:]]*:[[:space:]]*true' "$TEMP_DIR/health.json"; then
            log "$label 健康检查通过: $url"
            return 0
        fi
        attempts=$((attempts + 1))
        sleep 2
    done
    return 1
}

if ! wait_for_health "http://127.0.0.1:8765/health" "本机后端"; then
    systemctl --no-pager -l status "$SERVICE_NAME" >&2 || true
    journalctl -u "$SERVICE_NAME" -n 80 --no-pager >&2 || true
    die "本机健康检查失败，请根据上方服务日志排查"
fi

if [ -s "$DATABASE_PATH" ]; then
    LIVE_DB_CHECK=$(sqlite3 "$DATABASE_PATH" 'PRAGMA quick_check;' 2>&1) \
        || die "运行中数据库完整性检查失败: $LIVE_DB_CHECK"
    [ "$LIVE_DB_CHECK" = "ok" ] || die "运行中数据库不完整: $LIVE_DB_CHECK"
fi

enable_nginx_site() {
    install -o root -g root -m 0644 "$TEMP_DIR/nginx.conf" "$NGINX_AVAILABLE"
    if [ -L "$NGINX_ENABLED" ]; then
        ln -sfn "$NGINX_AVAILABLE" "$NGINX_ENABLED"
    elif [ -e "$NGINX_ENABLED" ]; then
        install -o root -g root -m 0644 "$TEMP_DIR/nginx.conf" "$NGINX_ENABLED"
    else
        ln -s "$NGINX_AVAILABLE" "$NGINX_ENABLED"
    fi
    nginx -t || die "Nginx 配置检查失败；未重载配置"
    systemctl enable nginx >/dev/null || die "无法启用 Nginx"
    systemctl reload nginx 2>/dev/null || systemctl restart nginx \
        || die "Nginx 重载和重启均失败"
}

write_proxy_locations() {
    cat <<EOF
    client_max_body_size 48m;

    location / {
        proxy_pass http://127.0.0.1:8765;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_connect_timeout 10s;
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
    }
EOF
}

log "配置独立的 Nginx HTTP 站点"
{
    cat <<EOF
# Managed by Hchat plugin-market deploy.sh
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;

    location ^~ /.well-known/acme-challenge/ {
        root $ACME_ROOT;
        try_files \$uri =404;
    }

EOF
    write_proxy_locations
    printf '}\n'
} > "$TEMP_DIR/nginx.conf"
enable_nginx_site

log "申请或复用 Let's Encrypt 证书"
printf '[Hchat 部署] 当前域名解析结果:\n'
getent ahostsv4 "$DOMAIN" 2>/dev/null | awk '{print "  " $1}' | sort -u || true
if ! certbot certonly \
    --webroot \
    --webroot-path "$ACME_ROOT" \
    --cert-name "$DOMAIN" \
    --domain "$DOMAIN" \
    --email "$EMAIL" \
    --agree-tos \
    --non-interactive \
    --keep-until-expiring; then
    die "证书申请失败。请确认域名已解析到本机公网 IP，且安全组/防火墙已放行 TCP 80 和 443"
fi

log "启用 HTTPS 和 HTTP 自动跳转"
{
    cat <<EOF
# Managed by Hchat plugin-market deploy.sh
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;

    location ^~ /.well-known/acme-challenge/ {
        root $ACME_ROOT;
        try_files \$uri =404;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name $DOMAIN;

    ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:HchatMarketTLS:10m;
    ssl_session_timeout 1d;

EOF
    write_proxy_locations
    printf '}\n'
} > "$TEMP_DIR/nginx.conf"
enable_nginx_site

install -d -o root -g root -m 0755 /etc/letsencrypt/renewal-hooks/deploy
cat > "$TEMP_DIR/reload-nginx" <<'EOF_RENEW'
#!/bin/sh
set -eu
nginx -t
systemctl reload nginx
EOF_RENEW
install -o root -g root -m 0755 "$TEMP_DIR/reload-nginx" \
    /etc/letsencrypt/renewal-hooks/deploy/reload-nginx

if ! wait_for_health "https://$DOMAIN/health" "公网 HTTPS"; then
    die "公网健康检查失败。后端本机检查已通过，请检查 DNS、云安全组、防火墙和 Nginx 日志"
fi

if ! curl --fail --silent --show-error --max-time 10 "https://$DOMAIN/admin/" \
    | grep -q 'Hchat 插件管理'; then
    die "管理页检查失败: https://$DOMAIN/admin/"
fi

log "部署完成"
printf '%s\n' \
    "服务地址: https://$DOMAIN" \
    "管理页面: https://$DOMAIN/admin/" \
    "管理员密码文件: $ADMIN_PASSWORD_PATH（部署脚本不会打印密码）" \
    "数据库: $DATABASE_PATH" \
    "部署前备份: $BACKUP_DIR"
