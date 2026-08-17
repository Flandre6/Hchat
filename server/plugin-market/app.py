#!/usr/bin/env python3
"""Hchat online plugin market HTTP service.

This service intentionally uses only Python's standard library so it can run
directly under systemd on a small server.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import hmac
import ipaddress
import json
import logging
import os
import re
import secrets
import signal
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from http import HTTPStatus
from http.cookies import CookieError, SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urlsplit


LOGGER = logging.getLogger("hchat.plugin_market")

ALLOWED_FILES = ("main.java", "main.java.bshs", "info.prop", "README.md")
FILE_LIMITS = {
    "main.java": 512 * 1024,
    "main.java.bshs": 16 * 1024 * 1024,
    "README.md": 256 * 1024,
    "info.prop": 64 * 1024,
}
MAX_EXTRA_FILE_COUNT = 32
MAX_FILE_COUNT = len(ALLOWED_FILES) + MAX_EXTRA_FILE_COUNT
EXTRA_FILE_LIMIT = 16 * 1024 * 1024
TOTAL_FILE_LIMIT = 32 * 1024 * 1024
MAX_REQUEST_BODY = 48 * 1024 * 1024
MAX_LIST_LIMIT = 100
MAX_BATCH_DELETE_COUNT = 100
MAX_COMMENT_LENGTH = 1000
PLUGIN_ID_PATTERN = re.compile(r"^p_[0-9a-f]{32}$")
VERSION_ID_PATTERN = re.compile(r"^v_[0-9a-f]{32}$")
DOWNLOAD_EVENT_ID_PATTERN = re.compile(r"^[0-9a-f]{32}$")
DETAIL_PATH_PATTERN = re.compile(r"^/v1/plugins/(p_[0-9a-f]{32})$")
DOWNLOAD_PATH_PATTERN = re.compile(r"^/v1/plugins/(p_[0-9a-f]{32})/downloads$")
LIKE_PATH_PATTERN = re.compile(r"^/v1/plugins/(p_[0-9a-f]{32})/likes$")
COMMENTS_PATH_PATTERN = re.compile(r"^/v1/plugins/(p_[0-9a-f]{32})/comments$")
COMMENT_DETAIL_PATH_PATTERN = re.compile(
    r"^/v1/plugins/(p_[0-9a-f]{32})/comments/(c_[0-9a-f]{32})$"
)
COMMENT_REPLY_PATH_PATTERN = re.compile(
    r"^/v1/plugins/(p_[0-9a-f]{32})/comments/(c_[0-9a-f]{32})/replies$"
)
NOTIFICATIONS_PATH = "/v1/notifications"
NOTIFICATIONS_READ_PATH = "/v1/notifications/read"
NOTIFICATION_ID_PATTERN = re.compile(r"^n_[0-9a-f]{32}$")
SNAPSHOT_LIST_PATH_PATTERN = re.compile(r"^/v1/plugins/(p_[0-9a-f]{32})/snapshots$")
SNAPSHOT_DETAIL_PATH_PATTERN = re.compile(
    r"^/v1/plugins/(p_[0-9a-f]{32})/snapshots/(v_[0-9a-f]{32})$"
)
ADMIN_PLUGIN_VERSION_PATH_PATTERN = re.compile(
    r"^/v1/admin/plugins/(p_[0-9a-f]{32})/versions/(v_[0-9a-f]{32})$"
)
ADMIN_PLUGIN_APPROVE_PATH_PATTERN = re.compile(
    r"^/v1/admin/plugins/(p_[0-9a-f]{32})/versions/(v_[0-9a-f]{32})/approve$"
)
ADMIN_BLACKLIST_ITEM_PATH_PATTERN = re.compile(r"^/v1/admin/blacklist/([^/]+)$")
ADMIN_SESSION_COOKIE = "__Host-hchat_admin_session"
ADMIN_SESSION_TTL_SECONDS = 8 * 60 * 60
ADMIN_LOGIN_FAILURE_LIMIT = 5
ADMIN_LOGIN_FAILURE_WINDOW_SECONDS = 15 * 60
ADMIN_PAGE_NONCE_PLACEHOLDER = "__HCHAT_CSP_NONCE__"
ADMIN_PAGE_PATH = Path(__file__).resolve().parent / "static" / "admin.html"


class ApiError(Exception):
    def __init__(
        self,
        status: int,
        code: str,
        message: str,
        *,
        details: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.details = details
        self.headers = headers or {}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def sha256_hex(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def token_hash(token: str) -> str:
    return sha256_hex(token.encode("utf-8"))


def normalize_admin_password(value: str) -> str:
    if not value or len(value) > 256 or any(character.isspace() for character in value):
        raise ValueError("管理员密码必须是 1 至 256 个字符且不能包含空白字符")
    return value


def load_admin_password_hash(password_file: Path | None) -> str | None:
    if password_file is None:
        return None
    password = password_file.read_text(encoding="utf-8").strip()
    return token_hash(normalize_admin_password(password))


def replace_admin_password(password_file: Path, password: str) -> None:
    password = normalize_admin_password(password)
    temporary_path = password_file.parent / (
        f".{password_file.name}.{secrets.token_hex(12)}.tmp"
    )
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    descriptor = os.open(temporary_path, flags, 0o600)
    replaced = False
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            output.write(password + "\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary_path, password_file)
        replaced = True
        try:
            directory_flags = os.O_RDONLY
            if hasattr(os, "O_DIRECTORY"):
                directory_flags |= os.O_DIRECTORY
            directory_descriptor = os.open(password_file.parent, directory_flags)
            try:
                os.fsync(directory_descriptor)
            finally:
                os.close(directory_descriptor)
        except OSError as error:
            LOGGER.warning("管理员密码目录同步失败 path=%s error=%s", password_file.parent, error)
    finally:
        if not replaced:
            try:
                temporary_path.unlink()
            except FileNotFoundError:
                pass


def _admin_session_key(admin_password_hash: str) -> bytes:
    return hmac.new(
        bytes.fromhex(admin_password_hash),
        b"hchat-plugin-market-admin-session-v1",
        hashlib.sha256,
    ).digest()


def _base64url_encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _base64url_decode(value: str) -> bytes:
    if not value or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("无效的 Base64URL")
    padding = "=" * (-len(value) % 4)
    decoded = base64.b64decode(value + padding, altchars=b"-_", validate=True)
    if not hmac.compare_digest(_base64url_encode(decoded), value):
        raise ValueError("非规范的 Base64URL")
    return decoded


def create_admin_session(
    admin_password_hash: str,
    *,
    now: int | None = None,
    ttl_seconds: int = ADMIN_SESSION_TTL_SECONDS,
) -> tuple[str, int]:
    issued_at = int(time.time()) if now is None else int(now)
    ttl_seconds = max(1, min(int(ttl_seconds), ADMIN_SESSION_TTL_SECONDS))
    expires_at = issued_at + ttl_seconds
    payload = json.dumps(
        {
            "v": 1,
            "iat": issued_at,
            "exp": expires_at,
            "nonce": secrets.token_urlsafe(16),
        },
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    encoded_payload = _base64url_encode(payload)
    signature = hmac.new(
        _admin_session_key(admin_password_hash),
        encoded_payload.encode("ascii"),
        hashlib.sha256,
    ).digest()
    return f"{encoded_payload}.{_base64url_encode(signature)}", expires_at


def validate_admin_session(
    session_value: str,
    admin_password_hash: str | None,
    *,
    now: int | None = None,
) -> int | None:
    if admin_password_hash is None or not session_value or len(session_value) > 1024:
        return None
    try:
        encoded_payload, encoded_signature = session_value.split(".", 1)
        signature = _base64url_decode(encoded_signature)
        expected_signature = hmac.new(
            _admin_session_key(admin_password_hash),
            encoded_payload.encode("ascii"),
            hashlib.sha256,
        ).digest()
        if not hmac.compare_digest(signature, expected_signature):
            return None
        payload = json.loads(_base64url_decode(encoded_payload).decode("utf-8"))
    except (ValueError, UnicodeError, json.JSONDecodeError, binascii.Error):
        return None
    if not isinstance(payload, dict) or payload.get("v") != 1:
        return None
    issued_at = payload.get("iat")
    expires_at = payload.get("exp")
    nonce = payload.get("nonce")
    if (
        isinstance(issued_at, bool)
        or not isinstance(issued_at, int)
        or isinstance(expires_at, bool)
        or not isinstance(expires_at, int)
        or not isinstance(nonce, str)
        or not nonce
    ):
        return None
    current_time = int(time.time()) if now is None else int(now)
    lifetime = expires_at - issued_at
    if lifetime < 1 or lifetime > ADMIN_SESSION_TTL_SECONDS:
        return None
    if issued_at > current_time + 60 or expires_at <= current_time:
        return None
    return expires_at


def format_utc_timestamp(timestamp: int) -> str:
    return datetime.fromtimestamp(timestamp, timezone.utc).isoformat(timespec="seconds").replace(
        "+00:00", "Z"
    )


def metadata_text(payload: dict[str, Any], name: str, maximum: int, *, required: bool) -> str:
    value = payload.get(name)
    if value is None and not required:
        return ""
    if not isinstance(value, str):
        raise ApiError(400, "INVALID_FIELD", f"{name} 必须是字符串")
    value = value.strip()
    if required and not value:
        raise ApiError(400, "INVALID_FIELD", f"{name} 不能为空")
    if len(value) > maximum:
        raise ApiError(400, "INVALID_FIELD", f"{name} 长度不能超过 {maximum} 个字符")
    if any(ord(character) < 32 for character in value):
        raise ApiError(400, "INVALID_FIELD", f"{name} 包含不允许的控制字符")
    return value


def release_notes_text(payload: dict[str, Any]) -> tuple[str, bool]:
    if "releaseNotes" not in payload:
        return "", False
    value = payload["releaseNotes"]
    if not isinstance(value, str):
        raise ApiError(400, "INVALID_FIELD", "releaseNotes 必须是字符串")
    value = value.strip()
    if len(value) > 500:
        raise ApiError(400, "INVALID_FIELD", "releaseNotes 长度不能超过 500 个字符")
    if any(ord(character) < 32 and character not in "\r\n\t" for character in value):
        raise ApiError(400, "INVALID_FIELD", "releaseNotes 包含不允许的控制字符")
    try:
        value.encode("utf-8")
    except UnicodeEncodeError as error:
        raise ApiError(400, "INVALID_FIELD", "releaseNotes 不是有效的 UTF-8 文本") from error
    return value, True


def validate_uploader_identity(payload: dict[str, Any]) -> dict[str, str]:
    wxid = metadata_text(payload, "uploaderWxId", 128, required=True)
    if re.fullmatch(r"[A-Za-z0-9_@.-]+", wxid) is None:
        raise ApiError(400, "INVALID_UPLOADER_WXID", "uploaderWxId 格式无效")
    return {
        "wxid": wxid,
        "wechat_id": metadata_text(payload, "uploaderWeChatId", 128, required=False),
        "nickname": metadata_text(payload, "uploaderNickname", 100, required=False),
    }


def validate_user_identity(payload: Any) -> dict[str, str]:
    if not isinstance(payload, dict):
        raise ApiError(400, "INVALID_USER_PAYLOAD", "用户身份必须是 JSON 对象")
    wxid = metadata_text(payload, "userWxId", 128, required=True)
    if re.fullmatch(r"[A-Za-z0-9_@.-]+", wxid) is None:
        raise ApiError(400, "INVALID_USER_WXID", "userWxId 格式无效")
    return {
        "wxid": wxid,
        "wechat_id": metadata_text(payload, "userWeChatId", 128, required=False),
        "nickname": metadata_text(payload, "userNickname", 100, required=False),
    }


def validate_comment_payload(payload: Any) -> tuple[dict[str, str], str]:
    user = validate_user_identity(payload)
    content = payload.get("content")
    if not isinstance(content, str):
        raise ApiError(400, "INVALID_COMMENT_CONTENT", "content 必须是字符串")
    content = content.strip()
    if not content:
        raise ApiError(400, "INVALID_COMMENT_CONTENT", "评论内容不能为空")
    if len(content) > MAX_COMMENT_LENGTH:
        raise ApiError(
            400,
            "INVALID_COMMENT_CONTENT",
            f"评论内容不能超过 {MAX_COMMENT_LENGTH} 个字符",
        )
    if any(ord(character) < 32 and character not in "\r\n\t" for character in content):
        raise ApiError(400, "INVALID_COMMENT_CONTENT", "评论内容包含不允许的控制字符")
    try:
        content.encode("utf-8")
    except UnicodeEncodeError as error:
        raise ApiError(400, "INVALID_COMMENT_CONTENT", "评论内容不是有效的 UTF-8 文本") from error
    return user, content


def validate_notification_read_payload(
    payload: Any,
) -> tuple[dict[str, str], list[str] | None]:
    user = validate_user_identity(payload)
    raw_ids = payload.get("notificationIds")
    if raw_ids is None:
        return user, None
    if not isinstance(raw_ids, list):
        raise ApiError(400, "INVALID_NOTIFICATION_IDS", "notificationIds 必须是数组")
    if len(raw_ids) > MAX_LIST_LIMIT:
        raise ApiError(
            400,
            "INVALID_NOTIFICATION_IDS",
            f"notificationIds 最多包含 {MAX_LIST_LIMIT} 项",
        )
    notification_ids: list[str] = []
    seen: set[str] = set()
    for value in raw_ids:
        if not isinstance(value, str) or NOTIFICATION_ID_PATTERN.fullmatch(value) is None:
            raise ApiError(400, "INVALID_NOTIFICATION_ID", "notificationId 格式无效")
        if value not in seen:
            seen.add(value)
            notification_ids.append(value)
    return user, notification_ids


def validate_batch_delete_payload(payload: Any) -> list[str]:
    if not isinstance(payload, dict):
        raise ApiError(400, "INVALID_BATCH_DELETE_PAYLOAD", "批量删除请求必须是 JSON 对象")
    plugin_ids = payload.get("pluginIds")
    if not isinstance(plugin_ids, list) or not plugin_ids:
        raise ApiError(400, "INVALID_PLUGIN_IDS", "pluginIds 必须是非空数组")
    if len(plugin_ids) > MAX_BATCH_DELETE_COUNT:
        raise ApiError(
            400,
            "TOO_MANY_PLUGIN_IDS",
            f"pluginIds 最多包含 {MAX_BATCH_DELETE_COUNT} 项",
        )
    validated: list[str] = []
    seen: set[str] = set()
    for plugin_id in plugin_ids:
        if not isinstance(plugin_id, str) or PLUGIN_ID_PATTERN.fullmatch(plugin_id) is None:
            raise ApiError(400, "INVALID_PLUGIN_ID", "pluginIds 包含格式无效的插件 ID")
        if plugin_id in seen:
            raise ApiError(400, "DUPLICATE_PLUGIN_ID", "pluginIds 不能包含重复项")
        seen.add(plugin_id)
        validated.append(plugin_id)
    return validated


def validate_source_plugin_id(value: str) -> str:
    if value in {".", ".."} or "/" in value or "\\" in value:
        raise ApiError(400, "INVALID_SOURCE_PLUGIN_ID", "sourcePluginId 包含不允许的路径字符")
    return value


def validate_extra_file_name(value: Any) -> str:
    if not isinstance(value, str) or not value:
        raise ApiError(400, "INVALID_FILE_NAME", "额外文件名必须是非空字符串")
    try:
        encoded_value = value.encode("utf-8")
    except UnicodeEncodeError as error:
        raise ApiError(400, "INVALID_FILE_NAME", "额外文件名不是有效的 UTF-8 文本") from error
    if len(value) > 128 or len(encoded_value) > 512:
        raise ApiError(400, "INVALID_FILE_NAME", "额外文件名过长")
    if value in {".", ".."} or "/" in value or "\\" in value or "\x00" in value:
        raise ApiError(400, "INVALID_FILE_NAME", f"额外文件名不是安全的 basename: {value}")
    if any(ord(character) < 32 or ord(character) == 127 for character in value):
        raise ApiError(400, "INVALID_FILE_NAME", f"额外文件名包含控制字符: {value}")
    return value


def validate_declared_file_integrity(
    raw_file: dict[str, Any],
    name: str,
    content_bytes: bytes,
    content_sha256: str,
) -> None:
    if "size" in raw_file:
        declared_size = raw_file["size"]
        if isinstance(declared_size, bool) or not isinstance(declared_size, int):
            raise ApiError(400, "INVALID_FILE_SIZE", f"{name} 的 size 必须是整数")
        if declared_size != len(content_bytes):
            raise ApiError(
                400,
                "FILE_SIZE_MISMATCH",
                f"{name} 的 size 与文件内容不一致",
            )
    if "sha256" in raw_file:
        declared_sha256 = raw_file["sha256"]
        if not isinstance(declared_sha256, str) or not re.fullmatch(
            r"[0-9a-fA-F]{64}", declared_sha256
        ):
            raise ApiError(400, "INVALID_FILE_SHA256", f"{name} 的 sha256 格式无效")
        if not hmac.compare_digest(declared_sha256.lower(), content_sha256):
            raise ApiError(
                400,
                "FILE_SHA256_MISMATCH",
                f"{name} 的 sha256 与文件内容不一致",
            )


def validate_upload_payload(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise ApiError(400, "INVALID_JSON", "请求 JSON 必须是对象")

    display_name = metadata_text(payload, "displayName", 100, required=True)
    source_plugin_id = validate_source_plugin_id(
        metadata_text(payload, "sourcePluginId", 128, required=True)
    )
    version_name = metadata_text(payload, "versionName", 64, required=True)
    author = metadata_text(payload, "author", 100, required=False)
    release_notes, release_notes_provided = release_notes_text(payload)
    uploader = validate_uploader_identity(payload)

    plugin_id = payload.get("pluginId")
    if plugin_id is not None:
        if not isinstance(plugin_id, str) or not PLUGIN_ID_PATTERN.fullmatch(plugin_id):
            raise ApiError(400, "INVALID_PLUGIN_ID", "pluginId 格式无效")

    owner_token = payload.get("ownerToken")
    if owner_token is not None:
        if not isinstance(owner_token, str) or not owner_token.strip():
            raise ApiError(400, "INVALID_OWNER_TOKEN", "ownerToken 格式无效")
        if len(owner_token) > 256:
            raise ApiError(400, "INVALID_OWNER_TOKEN", "ownerToken 过长")
        owner_token = owner_token.strip()

    raw_files = payload.get("files")
    if not isinstance(raw_files, list) or not raw_files:
        raise ApiError(400, "INVALID_FILES", "files 必须是非空数组")
    if len(raw_files) > MAX_FILE_COUNT:
        raise ApiError(400, "INVALID_FILES", f"files 最多包含 {MAX_FILE_COUNT} 个文件")

    files: dict[str, dict[str, Any]] = {}
    file_keys: dict[str, str] = {}
    default_file_keys = {name.casefold() for name in ALLOWED_FILES}
    extra_file_names: list[str] = []
    total_size = 0
    for raw_file in raw_files:
        if not isinstance(raw_file, dict):
            raise ApiError(400, "INVALID_FILE", "files 中的每一项必须是对象")
        name = raw_file.get("name")
        content = raw_file.get("content")
        if name not in ALLOWED_FILES:
            name = validate_extra_file_name(name)
            if name.casefold() in default_file_keys:
                raise ApiError(400, "DUPLICATE_FILE", f"文件重复: {name}")
            if len(extra_file_names) >= MAX_EXTRA_FILE_COUNT:
                raise ApiError(
                    400,
                    "INVALID_FILES",
                    f"额外文件最多包含 {MAX_EXTRA_FILE_COUNT} 个",
                )
        file_key = name.casefold()
        if file_key in file_keys:
            raise ApiError(400, "DUPLICATE_FILE", f"文件重复: {name}")
        file_keys[file_key] = name
        if not isinstance(content, str):
            raise ApiError(400, "INVALID_FILE_CONTENT", f"{name} 的 content 必须是字符串")
        is_bshs = name == "main.java.bshs"
        is_extra = name not in ALLOWED_FILES
        if is_bshs:
            if raw_file.get("encoding") != "base64":
                raise ApiError(
                    400,
                    "INVALID_FILE_ENCODING",
                    "main.java.bshs 的 encoding 必须是 base64",
                )
            try:
                content_bytes = base64.b64decode(content, validate=True)
            except (binascii.Error, ValueError) as error:
                raise ApiError(
                    400,
                    "INVALID_FILE_CONTENT",
                    "main.java.bshs 的 content 不是严格 Base64",
                ) from error
            canonical_content = base64.b64encode(content_bytes).decode("ascii")
            if not hmac.compare_digest(content, canonical_content):
                raise ApiError(
                    400,
                    "INVALID_FILE_CONTENT",
                    "main.java.bshs 的 content 不是规范 Base64",
                )
            if not content_bytes.startswith(b"BSHS"):
                raise ApiError(
                    400,
                    "INVALID_FILE_CONTENT",
                    "main.java.bshs 不是有效的 BeanShell 加密文件",
                )
            stored_content: str | bytes = content_bytes
        elif is_extra and raw_file.get("encoding") == "base64":
            try:
                content_bytes = base64.b64decode(content, validate=True)
            except (binascii.Error, ValueError) as error:
                raise ApiError(
                    400,
                    "INVALID_FILE_CONTENT",
                    f"{name} 的 content 不是严格 Base64",
                ) from error
            canonical_content = base64.b64encode(content_bytes).decode("ascii")
            if not hmac.compare_digest(content, canonical_content):
                raise ApiError(
                    400,
                    "INVALID_FILE_CONTENT",
                    f"{name} 的 content 不是规范 Base64",
                )
            stored_content = content_bytes
        else:
            if raw_file.get("encoding") not in (None, "utf8", "utf-8"):
                raise ApiError(
                    400,
                    "INVALID_FILE_ENCODING",
                    f"{name} 的 encoding 必须是 utf-8 或 base64",
                )
            if "\x00" in content:
                raise ApiError(400, "INVALID_FILE_CONTENT", f"{name} 包含不允许的空字符")
            try:
                content_bytes = content.encode("utf-8")
            except UnicodeEncodeError as error:
                raise ApiError(
                    400,
                    "INVALID_FILE_ENCODING",
                    f"{name} 不是有效的 UTF-8 文本",
                ) from error
            stored_content = content
        size = len(content_bytes)
        file_limit = FILE_LIMITS.get(name, EXTRA_FILE_LIMIT)
        if size > file_limit:
            raise ApiError(
                413,
                "FILE_TOO_LARGE",
                f"{name} 超过 {file_limit} 字节限制",
                details={"file": name, "size": size, "limit": file_limit},
            )
        total_size += size
        content_sha256 = sha256_hex(content_bytes)
        validate_declared_file_integrity(raw_file, name, content_bytes, content_sha256)
        files[name] = {
            "name": name,
            "content": stored_content,
            "bytes": content_bytes,
            "size": size,
            "sha256": content_sha256,
            "encoding": "base64" if (is_bshs or raw_file.get("encoding") == "base64") else "utf-8",
        }
        if is_extra:
            extra_file_names.append(name)

    main_file = files.get("main.java")
    if main_file is None or not main_file["content"].strip():
        raise ApiError(400, "MAIN_FILE_REQUIRED", "main.java 必须存在且内容非空")
    if total_size > TOTAL_FILE_LIMIT:
        raise ApiError(
            413,
            "PLUGIN_TOO_LARGE",
            f"插件文件总量超过 {TOTAL_FILE_LIMIT // (1024 * 1024)} MiB",
            details={"size": total_size, "limit": TOTAL_FILE_LIMIT},
        )

    content_hasher = hashlib.sha256()
    for name in ALLOWED_FILES:
        file_value = files.get(name)
        if file_value is None:
            continue
        encoded_name = name.encode("ascii")
        encoded_content = file_value["bytes"]
        content_hasher.update(len(encoded_name).to_bytes(2, "big"))
        content_hasher.update(encoded_name)
        content_hasher.update(len(encoded_content).to_bytes(8, "big"))
        content_hasher.update(encoded_content)
    for name in sorted(extra_file_names, key=lambda item: item.encode("utf-8")):
        file_value = files[name]
        encoded_name = name.encode("utf-8")
        encoded_content = file_value["bytes"]
        content_hasher.update(len(encoded_name).to_bytes(2, "big"))
        content_hasher.update(encoded_name)
        content_hasher.update(len(encoded_content).to_bytes(8, "big"))
        content_hasher.update(encoded_content)

    return {
        "plugin_id": plugin_id,
        "owner_token": owner_token,
        "display_name": display_name,
        "source_plugin_id": source_plugin_id,
        "version_name": version_name,
        "author": author,
        "release_notes": release_notes,
        "release_notes_provided": release_notes_provided,
        "uploader": uploader,
        "files": files,
        "extra_file_names": extra_file_names,
        "total_size": total_size,
        "content_hash": content_hasher.hexdigest(),
    }


class MarketDatabase:
    def __init__(self, database_path: Path, migration_dir: Path) -> None:
        self.database_path = database_path
        self.migration_dir = migration_dir
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(str(self.database_path), timeout=10.0)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 10000")
        return connection

    @contextmanager
    def session(self):
        connection = self.connect()
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self.session() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute("PRAGMA synchronous = NORMAL")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    applied_at TEXT NOT NULL
                )
                """
            )
        self._apply_migrations()

    def _apply_migrations(self) -> None:
        migrations = sorted(self.migration_dir.glob("[0-9][0-9][0-9]_*.sql"))
        if not migrations:
            raise RuntimeError(f"未找到数据库迁移: {self.migration_dir}")
        with self.session() as connection:
            applied = {
                int(row["version"])
                for row in connection.execute("SELECT version FROM schema_migrations")
            }
            for migration in migrations:
                version = int(migration.name.split("_", 1)[0])
                if version in applied:
                    continue
                sql = migration.read_text(encoding="utf-8")
                name = migration.name.replace("'", "''")
                applied_at = utc_now().replace("'", "''")
                script = (
                    "BEGIN IMMEDIATE;\n"
                    + sql
                    + "\nINSERT INTO schema_migrations(version, name, applied_at) "
                    + f"VALUES ({version}, '{name}', '{applied_at}');\nCOMMIT;"
                )
                try:
                    connection.executescript(script)
                except Exception:
                    try:
                        connection.execute("ROLLBACK")
                    except sqlite3.Error:
                        pass
                    raise
                LOGGER.info("已应用数据库迁移 %s", migration.name)

    def health(self) -> None:
        with self.session() as connection:
            connection.execute("SELECT 1").fetchone()

    def consume_upload_quota(
        self,
        install_id: str,
        client_ip: str,
        limit: int,
        window_seconds: int,
    ) -> dict[str, int]:
        now = int(time.time())
        window_start = now - (now % window_seconds)
        reset_at = window_start + window_seconds
        quota_key = sha256_hex(f"{install_id}\n{client_ip}".encode("utf-8"))
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                "DELETE FROM upload_rate_limits WHERE window_start < ?",
                (window_start - window_seconds,),
            )
            row = connection.execute(
                "SELECT window_start, request_count FROM upload_rate_limits WHERE quota_key = ?",
                (quota_key,),
            ).fetchone()
            count = 0
            if row is not None and int(row["window_start"]) == window_start:
                count = int(row["request_count"])
            if count >= limit:
                connection.rollback()
                retry_after = max(1, reset_at - now)
                raise ApiError(
                    429,
                    "UPLOAD_RATE_LIMITED",
                    "上传请求过于频繁，请稍后重试",
                    details={"retryAfter": retry_after},
                    headers={"Retry-After": str(retry_after)},
                )
            next_count = count + 1
            connection.execute(
                """
                INSERT INTO upload_rate_limits(quota_key, window_start, request_count, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(quota_key) DO UPDATE SET
                    window_start = excluded.window_start,
                    request_count = excluded.request_count,
                    updated_at = excluded.updated_at
                """,
                (quota_key, window_start, next_count, now),
            )
            connection.commit()
        return {
            "limit": limit,
            "remaining": max(0, limit - next_count),
            "reset": reset_at,
        }

    def get_upload_review_enabled(self) -> bool:
        with self.session() as connection:
            row = connection.execute(
                "SELECT upload_review_enabled FROM market_settings WHERE id = 1"
            ).fetchone()
        if row is None:
            raise RuntimeError("插件市场设置未初始化")
        return bool(row["upload_review_enabled"])

    def set_upload_review_enabled(self, enabled: bool) -> bool:
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT upload_review_enabled FROM market_settings WHERE id = 1"
            ).fetchone()
            if row is None:
                connection.rollback()
                raise RuntimeError("插件市场设置未初始化")
            was_enabled = bool(row["upload_review_enabled"])
            if was_enabled and not enabled:
                pending_plugin_ids = [
                    item["plugin_id"]
                    for item in connection.execute(
                        """
                        SELECT DISTINCT plugin_id
                        FROM plugin_versions
                        WHERE review_status = 'pending'
                        """
                    ).fetchall()
                ]
                connection.execute(
                    "UPDATE plugin_versions SET review_status = 'approved' "
                    "WHERE review_status = 'pending'"
                )
                now = utc_now()
                for plugin_id in pending_plugin_ids:
                    latest = connection.execute(
                        """
                        SELECT id, submitted_display_name, submitted_author
                        FROM plugin_versions
                        WHERE plugin_id = ?
                        ORDER BY created_at DESC, rowid DESC
                        LIMIT 1
                        """,
                        (plugin_id,),
                    ).fetchone()
                    if latest is not None:
                        connection.execute(
                            """
                            UPDATE plugins
                            SET display_name = ?, author = ?, latest_version_id = ?, updated_at = ?
                            WHERE id = ?
                            """,
                            (
                                latest["submitted_display_name"],
                                latest["submitted_author"],
                                latest["id"],
                                now,
                                plugin_id,
                            ),
                        )
            connection.execute(
                "UPDATE market_settings SET upload_review_enabled = ? WHERE id = 1",
                (1 if enabled else 0,),
            )
            connection.commit()
        return enabled

    def list_blacklisted_uploaders(self) -> list[dict[str, Any]]:
        with self.session() as connection:
            rows = connection.execute(
                """
                SELECT wxid, wechat_id, nickname, blacklisted_at
                FROM uploader_blacklist
                ORDER BY blacklisted_at DESC, wxid ASC
                """
            ).fetchall()
        return [self._blacklist_metadata(row) for row in rows]

    def blacklist_uploader(self, uploader: dict[str, str]) -> dict[str, Any]:
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute(
                "SELECT blacklisted_at FROM uploader_blacklist WHERE wxid = ?",
                (uploader["wxid"],),
            ).fetchone()
            if existing is None:
                blacklisted_at = utc_now()
                connection.execute(
                    """
                    INSERT INTO uploader_blacklist(
                        wxid, wechat_id, nickname, blacklisted_at
                    ) VALUES (?, ?, ?, ?)
                    """,
                    (
                        uploader["wxid"],
                        uploader["wechat_id"],
                        uploader["nickname"],
                        blacklisted_at,
                    ),
                )
                created = True
            else:
                blacklisted_at = existing["blacklisted_at"]
                connection.execute(
                    """
                    UPDATE uploader_blacklist
                    SET wechat_id = ?, nickname = ?
                    WHERE wxid = ?
                    """,
                    (uploader["wechat_id"], uploader["nickname"], uploader["wxid"]),
                )
                created = False
            connection.commit()
        return {
            "uploaderWxId": uploader["wxid"],
            "uploaderWeChatId": uploader["wechat_id"],
            "uploaderNickname": uploader["nickname"],
            "blacklistedAt": blacklisted_at,
            "created": created,
        }

    def unblacklist_uploader(self, wxid: str) -> dict[str, Any]:
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            cursor = connection.execute(
                "DELETE FROM uploader_blacklist WHERE wxid = ?",
                (wxid,),
            )
            if cursor.rowcount != 1:
                connection.rollback()
                raise ApiError(404, "BLACKLIST_ENTRY_NOT_FOUND", "未找到该黑名单上传者")
            connection.commit()
        return {"uploaderWxId": wxid, "deleted": True}

    def batch_delete_plugins(self, plugin_ids: list[str]) -> dict[str, Any]:
        results: list[dict[str, Any]] = []
        deleted_count = 0
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            for plugin_id in plugin_ids:
                cursor = connection.execute("DELETE FROM plugins WHERE id = ?", (plugin_id,))
                if cursor.rowcount == 1:
                    deleted_count += 1
                    results.append({"pluginId": plugin_id, "deleted": True})
                else:
                    results.append(
                        {
                            "pluginId": plugin_id,
                            "deleted": False,
                            "error": {
                                "code": "PLUGIN_NOT_FOUND",
                                "message": "未找到插件",
                            },
                        }
                    )
            connection.commit()
        return {
            "items": results,
            "requestedCount": len(plugin_ids),
            "deletedCount": deleted_count,
            "failedCount": len(plugin_ids) - deleted_count,
        }

    def list_admin_plugins(self, query: str, limit: int) -> list[dict[str, Any]]:
        where = ""
        parameters: list[Any] = []
        if query:
            escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            like = f"%{escaped}%"
            where = """
                WHERE p.display_name LIKE ? ESCAPE '\\' COLLATE NOCASE
                   OR p.source_plugin_id LIKE ? ESCAPE '\\' COLLATE NOCASE
                   OR p.author LIKE ? ESCAPE '\\' COLLATE NOCASE
                   OR EXISTS (
                       SELECT 1
                       FROM plugin_versions search_version
                       WHERE search_version.plugin_id = p.id
                         AND (
                             search_version.submitted_display_name LIKE ? ESCAPE '\\' COLLATE NOCASE
                             OR search_version.submitted_author LIKE ? ESCAPE '\\' COLLATE NOCASE
                             OR search_version.version_name LIKE ? ESCAPE '\\' COLLATE NOCASE
                             OR search_version.uploader_wxid LIKE ? ESCAPE '\\' COLLATE NOCASE
                             OR search_version.uploader_wechat_id LIKE ? ESCAPE '\\' COLLATE NOCASE
                             OR search_version.uploader_nickname LIKE ? ESCAPE '\\' COLLATE NOCASE
                         )
                   )
            """
            parameters.extend((like, like, like, like, like, like, like, like, like))
        parameters.append(limit)
        with self.session() as connection:
            plugins = connection.execute(
                f"""
                SELECT
                    p.id, p.source_plugin_id, p.display_name, p.author,
                    p.latest_version_id, p.created_at, p.updated_at, p.download_count,
                    recent.uploader_wxid, recent.uploader_wechat_id, recent.uploader_nickname
                FROM plugins p
                JOIN plugin_versions recent ON recent.id = (
                    SELECT candidate.id
                    FROM plugin_versions candidate
                    WHERE candidate.plugin_id = p.id
                    ORDER BY candidate.created_at DESC, candidate.rowid DESC
                    LIMIT 1
                )
                {where}
                ORDER BY p.updated_at DESC, p.id DESC
                LIMIT ?
                """,
                parameters,
            ).fetchall()
            result: list[dict[str, Any]] = []
            for plugin in plugins:
                versions = connection.execute(
                    """
                    SELECT
                        v.id AS version_id, v.version_name, v.release_notes, v.content_hash,
                        v.total_size, v.created_at AS version_created_at,
                        v.review_status, v.submitted_display_name, v.submitted_author,
                        v.uploader_wxid, v.uploader_wechat_id, v.uploader_nickname
                    FROM plugin_versions v
                    WHERE v.plugin_id = ?
                    ORDER BY (v.id = ?) DESC, v.created_at DESC, v.rowid DESC
                    """,
                    (plugin["id"], plugin["latest_version_id"]),
                ).fetchall()
                result.append(
                    {
                        "pluginId": plugin["id"],
                        "sourcePluginId": plugin["source_plugin_id"],
                        "displayName": plugin["display_name"],
                        "author": plugin["author"],
                        "createdAt": plugin["created_at"],
                        "updatedAt": plugin["updated_at"],
                        "downloadCount": int(plugin["download_count"]),
                        "latestVersionId": plugin["latest_version_id"],
                        "uploaderWxId": plugin["uploader_wxid"],
                        "uploaderWeChatId": plugin["uploader_wechat_id"],
                        "uploaderNickname": plugin["uploader_nickname"],
                        "versions": [
                            self._version_metadata(
                                version,
                                is_published=(
                                    version["version_id"] == plugin["latest_version_id"]
                                    and version["review_status"] == "approved"
                                ),
                            )
                            for version in versions
                        ],
                    }
                )
        return result

    def get_admin_plugin_version(self, plugin_id: str, version_id: str) -> dict[str, Any]:
        with self.session() as connection:
            row = connection.execute(
                """
                SELECT
                    p.id AS plugin_id, p.source_plugin_id, p.latest_version_id,
                    v.id AS version_id, v.version_name, v.release_notes, v.content_hash,
                    v.total_size, v.created_at AS version_created_at,
                    v.review_status, v.submitted_display_name, v.submitted_author,
                    v.uploader_wxid, v.uploader_wechat_id, v.uploader_nickname,
                    v.main_content, v.main_sha256, v.main_size,
                    v.bshs_content, v.bshs_sha256, v.bshs_size,
                    v.info_content, v.info_sha256, v.info_size,
                    v.readme_content, v.readme_sha256, v.readme_size
                FROM plugins p
                JOIN plugin_versions v ON v.plugin_id = p.id
                WHERE p.id = ? AND v.id = ?
                """,
                (plugin_id, version_id),
            ).fetchone()
        if row is None:
            raise ApiError(404, "VERSION_NOT_FOUND", "未找到该插件版本")
        return {
            "pluginId": row["plugin_id"],
            "sourcePluginId": row["source_plugin_id"],
            "version": self._version_metadata(
                row,
                is_published=(
                    row["version_id"] == row["latest_version_id"]
                    and row["review_status"] == "approved"
                ),
            ),
            "files": self._version_files(row, include_bshs_content=False),
        }

    def approve_plugin_version(self, plugin_id: str, version_id: str) -> dict[str, Any]:
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT
                    v.id AS version_id, v.version_name, v.release_notes, v.content_hash,
                    v.total_size, v.created_at AS version_created_at,
                    v.review_status, v.submitted_display_name, v.submitted_author,
                    v.uploader_wxid, v.uploader_wechat_id, v.uploader_nickname,
                    p.latest_version_id
                FROM plugin_versions v
                JOIN plugins p ON p.id = v.plugin_id
                WHERE v.plugin_id = ? AND v.id = ?
                """,
                (plugin_id, version_id),
            ).fetchone()
            if row is None:
                connection.rollback()
                raise ApiError(404, "VERSION_NOT_FOUND", "未找到该插件版本")
            already_approved = row["review_status"] == "approved"
            if not already_approved:
                connection.execute(
                    "UPDATE plugin_versions SET review_status = 'approved' WHERE id = ?",
                    (version_id,),
                )
                connection.execute(
                    """
                    UPDATE plugins
                    SET display_name = ?, author = ?, latest_version_id = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (
                        row["submitted_display_name"],
                        row["submitted_author"],
                        version_id,
                        utc_now(),
                        plugin_id,
                    ),
                )
            connection.commit()
        metadata = dict(self._version_metadata(row, is_published=not already_approved or row["latest_version_id"] == version_id))
        metadata["reviewStatus"] = "approved"
        metadata["isPublished"] = not already_approved or row["latest_version_id"] == version_id
        return {
            "pluginId": plugin_id,
            "versionId": version_id,
            "reviewStatus": "approved",
            "isPublished": metadata["isPublished"],
            "alreadyApproved": already_approved,
            "version": metadata,
        }

    def delete_plugin_version(self, plugin_id: str, version_id: str) -> dict[str, Any]:
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT
                    v.id, v.review_status, p.latest_version_id,
                    (SELECT COUNT(*) FROM plugin_versions count_version
                     WHERE count_version.plugin_id = p.id) AS version_count
                FROM plugins p
                JOIN plugin_versions v ON v.plugin_id = p.id
                WHERE p.id = ? AND v.id = ?
                """,
                (plugin_id, version_id),
            ).fetchone()
            if row is None:
                connection.rollback()
                raise ApiError(404, "VERSION_NOT_FOUND", "未找到该插件版本")
            is_current = row["latest_version_id"] == version_id
            if is_current and row["review_status"] == "approved":
                connection.rollback()
                raise ApiError(409, "PUBLISHED_VERSION_DELETE_FORBIDDEN", "不能单独删除当前已发布版本")
            if row["review_status"] == "pending" and int(row["version_count"]) == 1:
                connection.execute("DELETE FROM plugins WHERE id = ?", (plugin_id,))
                connection.commit()
                return {
                    "pluginId": plugin_id,
                    "versionId": version_id,
                    "deleted": True,
                    "pluginDeleted": True,
                }
            if is_current:
                replacement = connection.execute(
                    """
                    SELECT id, submitted_display_name, submitted_author
                    FROM plugin_versions
                    WHERE plugin_id = ? AND id != ?
                    ORDER BY created_at DESC, rowid DESC
                    LIMIT 1
                    """,
                    (plugin_id, version_id),
                ).fetchone()
                if replacement is None:
                    connection.rollback()
                    raise RuntimeError("插件版本状态不一致")
                connection.execute(
                    """
                    UPDATE plugins
                    SET display_name = ?, author = ?, latest_version_id = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (
                        replacement["submitted_display_name"],
                        replacement["submitted_author"],
                        replacement["id"],
                        utc_now(),
                        plugin_id,
                    ),
                )
            connection.execute(
                "DELETE FROM plugin_versions WHERE plugin_id = ? AND id = ?",
                (plugin_id, version_id),
            )
            connection.commit()
        return {
            "pluginId": plugin_id,
            "versionId": version_id,
            "deleted": True,
            "pluginDeleted": False,
        }

    def list_plugins(self, query: str, sort: str, limit: int) -> list[dict[str, Any]]:
        where = "WHERE v.review_status = 'approved'"
        parameters: list[Any] = []
        if query:
            escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            like = f"%{escaped}%"
            where += (
                " AND (p.display_name LIKE ? ESCAPE '\\' COLLATE NOCASE "
                "OR p.source_plugin_id LIKE ? ESCAPE '\\' COLLATE NOCASE "
                "OR p.author LIKE ? ESCAPE '\\' COLLATE NOCASE)"
            )
            parameters.extend((like, like, like))
        order = (
            "p.download_count DESC, p.updated_at DESC, p.id DESC"
            if sort == "downloads"
            else "p.updated_at DESC, p.id DESC"
        )
        parameters.append(limit)
        sql = f"""
            SELECT
                p.id, p.source_plugin_id, p.display_name, p.author,
                p.created_at, p.updated_at, p.download_count,
                (SELECT COUNT(*) FROM plugin_likes social_like WHERE social_like.plugin_id = p.id)
                    AS like_count,
                (SELECT COUNT(*) FROM plugin_comments social_comment WHERE social_comment.plugin_id = p.id)
                    AS comment_count,
                v.id AS version_id, v.version_name, v.release_notes, v.content_hash,
                v.total_size, v.created_at AS version_created_at,
                v.review_status
            FROM plugins p
            JOIN plugin_versions v ON v.id = p.latest_version_id
            {where}
            ORDER BY {order}
            LIMIT ?
        """
        with self.session() as connection:
            rows = connection.execute(sql, parameters).fetchall()
        return [self._plugin_summary(row) for row in rows]

    def get_plugin_detail(self, plugin_id: str) -> dict[str, Any]:
        with self.session() as connection:
            row = connection.execute(
                """
                SELECT
                    p.id, p.source_plugin_id, p.display_name, p.author,
                    p.created_at, p.updated_at, p.download_count,
                    (SELECT COUNT(*) FROM plugin_likes social_like WHERE social_like.plugin_id = p.id)
                        AS like_count,
                    (SELECT COUNT(*) FROM plugin_comments social_comment WHERE social_comment.plugin_id = p.id)
                        AS comment_count,
                    v.id AS version_id, v.version_name, v.release_notes, v.content_hash,
                    v.total_size, v.created_at AS version_created_at,
                    v.review_status,
                    v.main_content, v.main_sha256, v.main_size,
                    v.bshs_content, v.bshs_sha256, v.bshs_size,
                    v.info_content, v.info_sha256, v.info_size,
                    v.readme_content, v.readme_sha256, v.readme_size
                FROM plugins p
                JOIN plugin_versions v ON v.id = p.latest_version_id
                WHERE p.id = ? AND v.review_status = 'approved'
                """,
                (plugin_id,),
            ).fetchone()
            if row is None:
                raise ApiError(404, "PLUGIN_NOT_FOUND", "未找到插件")
        result = self._plugin_summary(row)
        result["files"] = self._version_files(row)
        return result

    def like_plugin(
        self,
        plugin_id: str,
        user: dict[str, str],
        actor_key_hash: str,
    ) -> dict[str, Any]:
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            self._require_public_plugin(connection, plugin_id)
            existing = connection.execute(
                "SELECT actor_key_hash FROM plugin_likes WHERE plugin_id = ? AND user_wxid = ?",
                (plugin_id, user["wxid"]),
            ).fetchone()
            if existing is None:
                connection.execute(
                    """
                    INSERT INTO plugin_likes(
                        plugin_id, user_wxid, user_wechat_id, user_nickname,
                        actor_key_hash, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (
                        plugin_id,
                        user["wxid"],
                        user["wechat_id"],
                        user["nickname"],
                        actor_key_hash,
                        utc_now(),
                    ),
                )
                created = True
            else:
                if not hmac.compare_digest(existing["actor_key_hash"], actor_key_hash):
                    connection.rollback()
                    raise ApiError(403, "LIKE_OWNER_MISMATCH", "该账号的点赞属于另一安装身份")
                connection.execute(
                    """
                    UPDATE plugin_likes
                    SET user_wechat_id = ?, user_nickname = ?
                    WHERE plugin_id = ? AND user_wxid = ?
                    """,
                    (user["wechat_id"], user["nickname"], plugin_id, user["wxid"]),
                )
                created = False
            like_count = int(
                connection.execute(
                    "SELECT COUNT(*) FROM plugin_likes WHERE plugin_id = ?",
                    (plugin_id,),
                ).fetchone()[0]
            )
            connection.commit()
        return {
            "pluginId": plugin_id,
            "liked": True,
            "created": created,
            "likeCount": like_count,
        }

    def like_status(
        self,
        plugin_id: str,
        user_wxid: str,
        actor_key_hash: str,
    ) -> dict[str, Any]:
        with self.session() as connection:
            self._require_public_plugin(connection, plugin_id)
            row = connection.execute(
                "SELECT actor_key_hash FROM plugin_likes WHERE plugin_id = ? AND user_wxid = ?",
                (plugin_id, user_wxid),
            ).fetchone()
            like_count = int(
                connection.execute(
                    "SELECT COUNT(*) FROM plugin_likes WHERE plugin_id = ?",
                    (plugin_id,),
                ).fetchone()[0]
            )
        return {
            "pluginId": plugin_id,
            "liked": row is not None and hmac.compare_digest(row["actor_key_hash"], actor_key_hash),
            "likeCount": like_count,
        }

    def unlike_plugin(
        self,
        plugin_id: str,
        user_wxid: str,
        actor_key_hash: str,
    ) -> dict[str, Any]:
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            self._require_public_plugin(connection, plugin_id)
            existing = connection.execute(
                "SELECT actor_key_hash FROM plugin_likes WHERE plugin_id = ? AND user_wxid = ?",
                (plugin_id, user_wxid),
            ).fetchone()
            if existing is not None and not hmac.compare_digest(
                existing["actor_key_hash"], actor_key_hash
            ):
                connection.rollback()
                raise ApiError(403, "LIKE_DELETE_FORBIDDEN", "只能取消自己的点赞")
            cursor = connection.execute(
                "DELETE FROM plugin_likes WHERE plugin_id = ? AND user_wxid = ?",
                (plugin_id, user_wxid),
            )
            like_count = int(
                connection.execute(
                    "SELECT COUNT(*) FROM plugin_likes WHERE plugin_id = ?",
                    (plugin_id,),
                ).fetchone()[0]
            )
            connection.commit()
        return {
            "pluginId": plugin_id,
            "liked": False,
            "removed": cursor.rowcount == 1,
            "likeCount": like_count,
        }

    def list_plugin_comments(
        self,
        plugin_id: str,
        limit: int,
        viewer_actor_key_hash: str | None = None,
    ) -> dict[str, Any]:
        with self.session() as connection:
            self._require_public_plugin(connection, plugin_id)
            total = int(
                connection.execute(
                    "SELECT COUNT(*) FROM plugin_comments WHERE plugin_id = ?",
                    (plugin_id,),
                ).fetchone()[0]
            )
            rows = connection.execute(
                """
                WITH RECURSIVE latest_comment(id) AS (
                    SELECT id
                    FROM plugin_comments
                    WHERE plugin_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT ?
                ), included_comment(id) AS (
                    SELECT id FROM latest_comment
                    UNION
                    SELECT child.parent_comment_id
                    FROM plugin_comments child
                    JOIN included_comment included ON included.id = child.id
                    WHERE child.parent_comment_id IS NOT NULL
                )
                SELECT comment.id, comment.plugin_id, comment.user_wxid,
                    comment.user_wechat_id, comment.user_nickname,
                    comment.actor_key_hash, comment.content, comment.created_at,
                    comment.parent_comment_id,
                    parent.user_nickname AS parent_user_nickname
                FROM plugin_comments comment
                JOIN included_comment included ON included.id = comment.id
                LEFT JOIN plugin_comments parent ON parent.id = comment.parent_comment_id
                ORDER BY comment.created_at DESC, comment.id DESC
                """,
                (plugin_id, limit),
            ).fetchall()
        items = [
            self._comment_metadata(
                row,
                can_delete=(
                    viewer_actor_key_hash is not None
                    and hmac.compare_digest(row["actor_key_hash"], viewer_actor_key_hash)
                ),
            )
            for row in rows
        ]
        return {
            "pluginId": plugin_id,
            "items": items,
            "count": len(items),
            "total": total,
            "limit": limit,
        }

    def add_plugin_comment(
        self,
        plugin_id: str,
        user: dict[str, str],
        content: str,
        actor_key_hash: str,
        parent_comment_id: str | None = None,
    ) -> dict[str, Any]:
        comment_id = "c_" + uuid.uuid4().hex
        created_at = utc_now()
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            self._require_public_plugin(connection, plugin_id)
            parent_comment = None
            if parent_comment_id is not None:
                parent_comment = connection.execute(
                    """
                    SELECT id, user_nickname, actor_key_hash, content
                    FROM plugin_comments
                    WHERE plugin_id = ? AND id = ?
                    """,
                    (plugin_id, parent_comment_id),
                ).fetchone()
                if parent_comment is None:
                    connection.rollback()
                    raise ApiError(404, "COMMENT_NOT_FOUND", "未找到要回复的评论")
            connection.execute(
                """
                INSERT INTO plugin_comments(
                    id, plugin_id, user_wxid, user_wechat_id, user_nickname,
                    actor_key_hash, content, created_at, parent_comment_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    comment_id,
                    plugin_id,
                    user["wxid"],
                    user["wechat_id"],
                    user["nickname"],
                    actor_key_hash,
                    content,
                    created_at,
                    parent_comment_id,
                ),
            )
            if parent_comment is not None and not hmac.compare_digest(
                parent_comment["actor_key_hash"], actor_key_hash
            ):
                connection.execute(
                    """
                    INSERT INTO plugin_notifications(
                        id, recipient_actor_key_hash, plugin_id, comment_id,
                        parent_comment_id, actor_nickname, content, created_at, read_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)
                    """,
                    (
                        "n_" + uuid.uuid4().hex,
                        parent_comment["actor_key_hash"],
                        plugin_id,
                        comment_id,
                        parent_comment["id"],
                        user["nickname"],
                        content,
                        created_at,
                    ),
                )
            comment = connection.execute(
                """
                SELECT reply.id, reply.plugin_id, reply.user_wxid,
                    reply.user_wechat_id, reply.user_nickname,
                    reply.actor_key_hash, reply.content, reply.created_at,
                    reply.parent_comment_id,
                    parent.user_nickname AS parent_user_nickname
                FROM plugin_comments reply
                LEFT JOIN plugin_comments parent ON parent.id = reply.parent_comment_id
                WHERE reply.id = ?
                """,
                (comment_id,),
            ).fetchone()
            comment_count = int(
                connection.execute(
                    "SELECT COUNT(*) FROM plugin_comments WHERE plugin_id = ?",
                    (plugin_id,),
                ).fetchone()[0]
            )
            connection.commit()
        return {
            "comment": self._comment_metadata(comment, can_delete=True),
            "commentCount": comment_count,
        }

    def delete_plugin_comment(
        self,
        plugin_id: str,
        comment_id: str,
        actor_key_hash: str,
        *,
        administrator: bool = False,
    ) -> dict[str, Any]:
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            self._require_public_plugin(connection, plugin_id)
            comment = connection.execute(
                """
                SELECT actor_key_hash
                FROM plugin_comments
                WHERE plugin_id = ? AND id = ?
                """,
                (plugin_id, comment_id),
            ).fetchone()
            if comment is None:
                connection.rollback()
                raise ApiError(404, "COMMENT_NOT_FOUND", "未找到评论")
            if not administrator and not hmac.compare_digest(
                comment["actor_key_hash"], actor_key_hash
            ):
                connection.rollback()
                raise ApiError(403, "COMMENT_DELETE_FORBIDDEN", "只能删除自己的评论")
            connection.execute(
                "DELETE FROM plugin_comments WHERE plugin_id = ? AND id = ?",
                (plugin_id, comment_id),
            )
            comment_count = int(
                connection.execute(
                    "SELECT COUNT(*) FROM plugin_comments WHERE plugin_id = ?",
                    (plugin_id,),
                ).fetchone()[0]
            )
            connection.commit()
        return {
            "pluginId": plugin_id,
            "commentId": comment_id,
            "deleted": True,
            "commentCount": comment_count,
        }

    def list_plugin_notifications(
        self,
        recipient_actor_key_hash: str,
        limit: int,
    ) -> dict[str, Any]:
        with self.session() as connection:
            total = int(
                connection.execute(
                    "SELECT COUNT(*) FROM plugin_notifications "
                    "WHERE recipient_actor_key_hash = ?",
                    (recipient_actor_key_hash,),
                ).fetchone()[0]
            )
            unread_count = int(
                connection.execute(
                    "SELECT COUNT(*) FROM plugin_notifications "
                    "WHERE recipient_actor_key_hash = ? AND read_at IS NULL",
                    (recipient_actor_key_hash,),
                ).fetchone()[0]
            )
            rows = connection.execute(
                """
                SELECT notification.id, notification.plugin_id,
                    plugin.display_name AS plugin_name,
                    notification.comment_id, notification.parent_comment_id,
                    notification.actor_nickname, notification.content,
                    parent.content AS original_content,
                    notification.created_at, notification.read_at
                FROM plugin_notifications notification
                JOIN plugins plugin ON plugin.id = notification.plugin_id
                JOIN plugin_comments parent ON parent.id = notification.parent_comment_id
                WHERE notification.recipient_actor_key_hash = ?
                ORDER BY notification.created_at DESC, notification.id DESC
                LIMIT ?
                """,
                (recipient_actor_key_hash, limit),
            ).fetchall()
        return {
            "items": [self._notification_metadata(row) for row in rows],
            "count": len(rows),
            "total": total,
            "unreadCount": unread_count,
            "limit": limit,
        }

    def mark_plugin_notifications_read(
        self,
        recipient_actor_key_hash: str,
        notification_ids: list[str] | None,
    ) -> dict[str, Any]:
        read_at = utc_now()
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            if notification_ids is None:
                cursor = connection.execute(
                    """
                    UPDATE plugin_notifications
                    SET read_at = ?
                    WHERE recipient_actor_key_hash = ? AND read_at IS NULL
                    """,
                    (read_at, recipient_actor_key_hash),
                )
            elif notification_ids:
                placeholders = ",".join("?" for _ in notification_ids)
                cursor = connection.execute(
                    f"""
                    UPDATE plugin_notifications
                    SET read_at = ?
                    WHERE recipient_actor_key_hash = ? AND read_at IS NULL
                        AND id IN ({placeholders})
                    """,
                    (read_at, recipient_actor_key_hash, *notification_ids),
                )
            else:
                cursor = None
            unread_count = int(
                connection.execute(
                    "SELECT COUNT(*) FROM plugin_notifications "
                    "WHERE recipient_actor_key_hash = ? AND read_at IS NULL",
                    (recipient_actor_key_hash,),
                ).fetchone()[0]
            )
            connection.commit()
        return {
            "markedCount": 0 if cursor is None else cursor.rowcount,
            "unreadCount": unread_count,
            "readAt": read_at,
        }

    def list_plugin_snapshots(self, plugin_id: str) -> list[dict[str, Any]]:
        with self.session() as connection:
            plugin = connection.execute(
                """
                SELECT 1
                FROM plugins p
                JOIN plugin_versions latest ON latest.id = p.latest_version_id
                WHERE p.id = ? AND latest.review_status = 'approved'
                """,
                (plugin_id,),
            ).fetchone()
            if plugin is None:
                raise ApiError(404, "PLUGIN_NOT_FOUND", "未找到插件")
            rows = connection.execute(
                """
                SELECT
                    v.id AS version_id, v.version_name, v.release_notes, v.content_hash,
                    v.total_size, v.created_at AS version_created_at,
                    v.review_status
                FROM plugin_versions v
                JOIN plugins p ON p.id = v.plugin_id
                WHERE v.plugin_id = ? AND v.review_status = 'approved'
                ORDER BY (v.id = p.latest_version_id) DESC, v.created_at DESC, v.rowid DESC
                """,
                (plugin_id,),
            ).fetchall()
        return [self._snapshot_metadata(row) for row in rows]

    def get_plugin_snapshot(self, plugin_id: str, version_id: str) -> dict[str, Any]:
        with self.session() as connection:
            plugin = connection.execute(
                """
                SELECT id, source_plugin_id, display_name, author, download_count
                FROM plugins
                WHERE id = ? AND latest_version_id IN (
                    SELECT id FROM plugin_versions WHERE review_status = 'approved'
                )
                """,
                (plugin_id,),
            ).fetchone()
            if plugin is None:
                raise ApiError(404, "PLUGIN_NOT_FOUND", "未找到插件")
            row = connection.execute(
                """
                SELECT
                    v.id AS version_id, v.version_name, v.release_notes, v.content_hash,
                    v.total_size, v.created_at AS version_created_at,
                    v.review_status,
                    v.main_content, v.main_sha256, v.main_size,
                    v.bshs_content, v.bshs_sha256, v.bshs_size,
                    v.info_content, v.info_sha256, v.info_size,
                    v.readme_content, v.readme_sha256, v.readme_size
                FROM plugin_versions v
                WHERE v.plugin_id = ? AND v.id = ? AND v.review_status = 'approved'
                """,
                (plugin_id, version_id),
            ).fetchone()
            if row is None:
                raise ApiError(404, "SNAPSHOT_NOT_FOUND", "未找到该插件历史版本")
        return {
            "pluginId": plugin["id"],
            "sourcePluginId": plugin["source_plugin_id"],
            "displayName": plugin["display_name"],
            "author": plugin["author"],
            "downloadCount": int(plugin["download_count"]),
            "snapshot": self._snapshot_metadata(row),
            "files": self._version_files(row),
        }

    def record_plugin_download(
        self,
        plugin_id: str,
        version_id: str,
        event_id: str,
    ) -> dict[str, Any]:
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            plugin = connection.execute(
                """
                SELECT p.download_count
                FROM plugins p
                JOIN plugin_versions latest ON latest.id = p.latest_version_id
                JOIN plugin_versions requested ON requested.plugin_id = p.id
                WHERE p.id = ? AND requested.id = ?
                    AND latest.review_status = 'approved'
                    AND requested.review_status = 'approved'
                """,
                (plugin_id, version_id),
            ).fetchone()
            if plugin is None:
                connection.rollback()
                raise ApiError(404, "PLUGIN_VERSION_NOT_FOUND", "未找到可下载的插件版本")
            recorded = connection.execute(
                """
                SELECT plugin_id, version_id
                FROM plugin_download_events
                WHERE event_id = ?
                """,
                (event_id,),
            ).fetchone()
            if recorded is not None:
                if recorded["plugin_id"] != plugin_id or recorded["version_id"] != version_id:
                    connection.rollback()
                    raise ApiError(409, "DOWNLOAD_EVENT_CONFLICT", "下载事件已用于其它插件版本")
                connection.rollback()
                return {
                    "pluginId": plugin_id,
                    "versionId": version_id,
                    "downloadCount": int(plugin["download_count"]),
                    "recorded": False,
                }
            download_count = int(plugin["download_count"]) + 1
            connection.execute(
                """
                INSERT INTO plugin_download_events(event_id, plugin_id, version_id, created_at)
                VALUES (?, ?, ?, ?)
                """,
                (event_id, plugin_id, version_id, utc_now()),
            )
            connection.execute(
                "UPDATE plugins SET download_count = ? WHERE id = ?",
                (download_count, plugin_id),
            )
            connection.commit()
        return {
            "pluginId": plugin_id,
            "versionId": version_id,
            "downloadCount": download_count,
            "recorded": True,
        }

    def save_plugin(self, upload: dict[str, Any], uploader_key_hash: str) -> tuple[dict[str, Any], int]:
        requested_id = upload["plugin_id"]
        now = utc_now()
        if requested_id is None:
            plugin_id = "p_" + uuid.uuid4().hex
            version_id = "v_" + uuid.uuid4().hex
            owner_token = secrets.token_urlsafe(32)
            with self.session() as connection:
                connection.execute("BEGIN IMMEDIATE")
                self._require_uploader_allowed(connection, upload["uploader"])
                review_enabled = bool(
                    connection.execute(
                        "SELECT upload_review_enabled FROM market_settings WHERE id = 1"
                    ).fetchone()["upload_review_enabled"]
                )
                review_status = "pending" if review_enabled else "approved"
                connection.execute(
                    """
                    INSERT INTO plugins(
                        id, source_plugin_id, display_name, author,
                        owner_token_hash, uploader_key_hash, latest_version_id,
                        download_count, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    (
                        plugin_id,
                        upload["source_plugin_id"],
                        upload["display_name"],
                        upload["author"],
                        token_hash(owner_token),
                        uploader_key_hash,
                        version_id,
                        now,
                        now,
                    ),
                )
                self._insert_version(
                    connection,
                    plugin_id,
                    version_id,
                    upload,
                    now,
                    review_status,
                )
                connection.commit()
            return (
                {
                    "pluginId": plugin_id,
                    "versionId": version_id,
                    "ownerToken": owner_token,
                    "contentHash": upload["content_hash"],
                    "created": True,
                    "unchanged": False,
                    "reviewStatus": review_status,
                },
                HTTPStatus.CREATED,
            )

        owner_token = upload["owner_token"]
        if not owner_token:
            raise ApiError(401, "OWNER_TOKEN_REQUIRED", "更新插件必须提供 ownerToken")
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            review_enabled = bool(
                connection.execute(
                    "SELECT upload_review_enabled FROM market_settings WHERE id = 1"
                ).fetchone()["upload_review_enabled"]
            )
            plugin = connection.execute(
                """
                SELECT p.*
                FROM plugins p
                WHERE p.id = ?
                """,
                (requested_id,),
            ).fetchone()
            if plugin is None:
                connection.rollback()
                raise ApiError(404, "PLUGIN_NOT_FOUND", "未找到要更新的插件")
            if not hmac.compare_digest(plugin["owner_token_hash"], token_hash(owner_token)):
                connection.rollback()
                raise ApiError(403, "OWNER_TOKEN_INVALID", "ownerToken 无效")
            self._require_uploader_allowed(connection, upload["uploader"])
            if plugin["source_plugin_id"] != upload["source_plugin_id"]:
                connection.rollback()
                raise ApiError(
                    409,
                    "SOURCE_PLUGIN_ID_MISMATCH",
                    "更新时不能修改 sourcePluginId",
                )

            existing_version = connection.execute(
                """
                SELECT
                    id, review_status, submitted_display_name, submitted_author,
                    uploader_wxid
                FROM plugin_versions
                WHERE plugin_id = ? AND version_name = ? AND content_hash = ?
                ORDER BY rowid DESC
                LIMIT 1
                """,
                (requested_id, upload["version_name"], upload["content_hash"]),
            ).fetchone()
            unchanged = existing_version is not None
            if unchanged:
                version_id = existing_version["id"]
                review_status = existing_version["review_status"]
                if not existing_version["uploader_wxid"]:
                    connection.execute(
                        """
                        UPDATE plugin_versions
                        SET uploader_wxid = ?, uploader_wechat_id = ?, uploader_nickname = ?
                        WHERE id = ?
                        """,
                        (
                            upload["uploader"]["wxid"],
                            upload["uploader"]["wechat_id"],
                            upload["uploader"]["nickname"],
                            version_id,
                        ),
                    )
                if review_enabled and review_status == "pending":
                    connection.execute(
                        """
                        UPDATE plugin_versions
                        SET submitted_display_name = ?, submitted_author = ?
                        WHERE id = ?
                        """,
                        (upload["display_name"], upload["author"], version_id),
                    )
                if upload["release_notes_provided"] and (
                    not review_enabled or review_status == "pending"
                ):
                    connection.execute(
                        "UPDATE plugin_versions SET release_notes = ? WHERE id = ?",
                        (upload["release_notes"], version_id),
                    )
                if not review_enabled:
                    connection.execute(
                        """
                        UPDATE plugin_versions
                        SET submitted_display_name = ?, submitted_author = ?
                        WHERE id = ?
                        """,
                        (upload["display_name"], upload["author"], version_id),
                    )
            else:
                version_id = "v_" + uuid.uuid4().hex
                review_status = "pending" if review_enabled else "approved"
                self._insert_version(
                    connection,
                    requested_id,
                    version_id,
                    upload,
                    now,
                    review_status,
                )
            if review_status == "approved":
                if review_enabled and existing_version is not None:
                    published_display_name = existing_version["submitted_display_name"]
                    published_author = existing_version["submitted_author"]
                else:
                    published_display_name = upload["display_name"]
                    published_author = upload["author"]
                connection.execute(
                    """
                    UPDATE plugins
                    SET display_name = ?, author = ?, latest_version_id = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (
                        published_display_name,
                        published_author,
                        version_id,
                        now,
                        requested_id,
                    ),
                )
            connection.commit()
        return (
            {
                "pluginId": requested_id,
                "versionId": version_id,
                "contentHash": upload["content_hash"],
                "created": False,
                "unchanged": unchanged,
                "reviewStatus": review_status,
            },
            HTTPStatus.OK,
        )

    def _insert_version(
        self,
        connection: sqlite3.Connection,
        plugin_id: str,
        version_id: str,
        upload: dict[str, Any],
        created_at: str,
        review_status: str,
    ) -> None:
        files = upload["files"]
        main = files["main.java"]
        bshs = files.get("main.java.bshs")
        info = files.get("info.prop")
        readme = files.get("README.md")
        connection.execute(
            """
            INSERT INTO plugin_versions(
                id, plugin_id, version_name, release_notes, content_hash, total_size, created_at,
                review_status, submitted_display_name, submitted_author,
                uploader_wxid, uploader_wechat_id, uploader_nickname,
                main_content, main_sha256, main_size,
                bshs_content, bshs_sha256, bshs_size,
                info_content, info_sha256, info_size,
                readme_content, readme_sha256, readme_size
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                version_id,
                plugin_id,
                upload["version_name"],
                upload["release_notes"],
                upload["content_hash"],
                upload["total_size"],
                created_at,
                review_status,
                upload["display_name"],
                upload["author"],
                upload["uploader"]["wxid"],
                upload["uploader"]["wechat_id"],
                upload["uploader"]["nickname"],
                main["content"],
                main["sha256"],
                main["size"],
                bshs["content"] if bshs else None,
                bshs["sha256"] if bshs else None,
                bshs["size"] if bshs else None,
                info["content"] if info else None,
                info["sha256"] if info else None,
                info["size"] if info else None,
                readme["content"] if readme else None,
                readme["sha256"] if readme else None,
                readme["size"] if readme else None,
            ),
        )
        for name in upload["extra_file_names"]:
            extra = files[name]
            connection.execute(
                """
                INSERT INTO plugin_version_files(
                    version_id, name, encoding, content, sha256, size
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    version_id,
                    name,
                    extra["encoding"],
                    extra["bytes"],
                    extra["sha256"],
                    extra["size"],
                ),
            )

    @staticmethod
    def _require_public_plugin(connection: sqlite3.Connection, plugin_id: str) -> None:
        plugin = connection.execute(
            """
            SELECT 1
            FROM plugins p
            JOIN plugin_versions latest ON latest.id = p.latest_version_id
            WHERE p.id = ? AND latest.review_status = 'approved'
            """,
            (plugin_id,),
        ).fetchone()
        if plugin is None:
            raise ApiError(404, "PLUGIN_NOT_FOUND", "未找到插件")

    @staticmethod
    def _require_uploader_allowed(
        connection: sqlite3.Connection,
        uploader: dict[str, str],
    ) -> None:
        row = connection.execute(
            "SELECT blacklisted_at FROM uploader_blacklist WHERE wxid = ?",
            (uploader["wxid"],),
        ).fetchone()
        if row is not None:
            raise ApiError(
                403,
                "UPLOADER_BLACKLISTED",
                "该上传者已被禁止上传插件",
                details={"blacklistedAt": row["blacklisted_at"]},
            )

    def delete_plugin(self, plugin_id: str, owner_token: str, *, administrator: bool = False) -> None:
        if not administrator and not owner_token:
            raise ApiError(401, "OWNER_TOKEN_REQUIRED", "删除插件必须提供 ownerToken")
        with self.session() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT owner_token_hash FROM plugins WHERE id = ?",
                (plugin_id,),
            ).fetchone()
            if row is None:
                connection.rollback()
                raise ApiError(404, "PLUGIN_NOT_FOUND", "未找到插件")
            if not administrator and not hmac.compare_digest(
                row["owner_token_hash"], token_hash(owner_token)
            ):
                connection.rollback()
                raise ApiError(403, "OWNER_TOKEN_INVALID", "ownerToken 无效")
            connection.execute("DELETE FROM plugins WHERE id = ?", (plugin_id,))
            connection.commit()

    @staticmethod
    def _plugin_summary(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "pluginId": row["id"],
            "sourcePluginId": row["source_plugin_id"],
            "displayName": row["display_name"],
            "author": row["author"],
            "createdAt": row["created_at"],
            "updatedAt": row["updated_at"],
            "downloadCount": int(row["download_count"]),
            "likeCount": int(row["like_count"]),
            "commentCount": int(row["comment_count"]),
            "latestVersion": {
                "versionId": row["version_id"],
                "versionName": row["version_name"],
                "releaseNotes": row["release_notes"],
                "contentHash": row["content_hash"],
                "totalSize": int(row["total_size"]),
                "createdAt": row["version_created_at"],
                "reviewStatus": row["review_status"],
            },
        }

    @staticmethod
    def _snapshot_metadata(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "versionId": row["version_id"],
            "versionName": row["version_name"],
            "releaseNotes": row["release_notes"],
            "contentHash": row["content_hash"],
            "totalSize": int(row["total_size"]),
            "createdAt": row["version_created_at"],
            "reviewStatus": row["review_status"],
        }

    @staticmethod
    def _version_metadata(
        row: sqlite3.Row,
        *,
        is_published: bool,
    ) -> dict[str, Any]:
        return {
            "versionId": row["version_id"],
            "versionName": row["version_name"],
            "releaseNotes": row["release_notes"],
            "contentHash": row["content_hash"],
            "totalSize": int(row["total_size"]),
            "createdAt": row["version_created_at"],
            "reviewStatus": row["review_status"],
            "submittedDisplayName": row["submitted_display_name"],
            "submittedAuthor": row["submitted_author"],
            "uploaderWxId": row["uploader_wxid"],
            "uploaderWeChatId": row["uploader_wechat_id"],
            "uploaderNickname": row["uploader_nickname"],
            "isPublished": is_published,
        }

    @staticmethod
    def _blacklist_metadata(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "uploaderWxId": row["wxid"],
            "uploaderWeChatId": row["wechat_id"],
            "uploaderNickname": row["nickname"],
            "blacklistedAt": row["blacklisted_at"],
        }

    @staticmethod
    def _comment_metadata(row: sqlite3.Row, *, can_delete: bool = False) -> dict[str, Any]:
        return {
            "commentId": row["id"],
            "pluginId": row["plugin_id"],
            "userNickname": row["user_nickname"],
            "content": row["content"],
            "createdAt": row["created_at"],
            "parentCommentId": row["parent_comment_id"] or "",
            "replyToNickname": row["parent_user_nickname"] or "",
            "canDelete": can_delete,
        }

    @staticmethod
    def _notification_metadata(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "notificationId": row["id"],
            "type": "comment_reply",
            "pluginId": row["plugin_id"],
            "pluginName": row["plugin_name"],
            "replyCommentId": row["comment_id"],
            "parentCommentId": row["parent_comment_id"],
            "actorNickname": row["actor_nickname"],
            "content": row["content"],
            "originalContent": row["original_content"],
            "createdAt": row["created_at"],
            "read": row["read_at"] is not None,
        }

    def _version_files(
        self,
        row: sqlite3.Row,
        *,
        include_bshs_content: bool = True,
    ) -> list[dict[str, Any]]:
        files = [
            {
                "name": "main.java",
                "content": row["main_content"],
                "size": int(row["main_size"]),
                "sha256": row["main_sha256"],
            }
        ]
        if row["bshs_content"] is not None:
            bshs_content = bytes(row["bshs_content"])
            bshs_file = {
                "name": "main.java.bshs",
                "encoding": "base64",
                "size": int(row["bshs_size"]),
                "sha256": row["bshs_sha256"],
            }
            if include_bshs_content:
                bshs_file["content"] = base64.b64encode(bshs_content).decode("ascii")
            files.append(bshs_file)
        if row["info_content"] is not None:
            files.append(
                {
                    "name": "info.prop",
                    "content": row["info_content"],
                    "size": int(row["info_size"]),
                    "sha256": row["info_sha256"],
                }
            )
        if row["readme_content"] is not None:
            files.append(
                {
                    "name": "README.md",
                    "content": row["readme_content"],
                    "size": int(row["readme_size"]),
                    "sha256": row["readme_sha256"],
                }
            )
        with self.session() as connection:
            extra_rows = connection.execute(
                """
                SELECT name, encoding, content, sha256, size
                FROM plugin_version_files
                WHERE version_id = ?
                ORDER BY name COLLATE BINARY
                """,
                (row["version_id"],),
            ).fetchall()
        for extra in extra_rows:
            content_bytes = bytes(extra["content"])
            extra_file: dict[str, Any] = {
                "name": extra["name"],
                "size": int(extra["size"]),
                "sha256": extra["sha256"],
            }
            if extra["encoding"] == "base64":
                extra_file["encoding"] = "base64"
                extra_file["content"] = base64.b64encode(content_bytes).decode("ascii")
            else:
                extra_file["content"] = content_bytes.decode("utf-8")
            files.append(extra_file)
        return files


class PluginMarketServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        database: MarketDatabase,
        *,
        rate_limit_count: int,
        rate_limit_window: int,
        trust_proxy: bool,
        admin_password_hash: str | None = None,
        admin_password_file: Path | None = None,
    ) -> None:
        super().__init__(server_address, PluginMarketHandler)
        self.database = database
        self.rate_limit_count = rate_limit_count
        self.rate_limit_window = rate_limit_window
        self.trust_proxy = trust_proxy
        self.admin_password_hash = admin_password_hash
        self.admin_password_file = (
            admin_password_file.resolve() if admin_password_file is not None else None
        )
        self.admin_auth_lock = threading.RLock()
        self.admin_session_nonce = secrets.token_bytes(32)
        self.admin_login_failures: dict[str, tuple[int, int]] = {}
        self.admin_login_lock = threading.Lock()

    def _admin_session_signing_hash_locked(self) -> str | None:
        if self.admin_password_hash is None:
            return None
        return hmac.new(
            self.admin_session_nonce,
            bytes.fromhex(self.admin_password_hash),
            hashlib.sha256,
        ).hexdigest()

    def create_admin_session_for_password(self, password: str) -> tuple[str, int] | None:
        with self.admin_auth_lock:
            configured_hash = self.admin_password_hash
            signing_hash = self._admin_session_signing_hash_locked()
            if configured_hash is None or signing_hash is None:
                raise ApiError(503, "ADMIN_DISABLED", "管理员登录未启用")
            if not hmac.compare_digest(configured_hash, token_hash(password)):
                return None
            return create_admin_session(signing_hash)

    def validate_admin_session(self, session_value: str) -> int | None:
        with self.admin_auth_lock:
            return validate_admin_session(
                session_value,
                self._admin_session_signing_hash_locked(),
            )

    def change_admin_password(
        self,
        session_value: str,
        current_password: str,
        new_password: str,
    ) -> None:
        with self.admin_auth_lock:
            configured_hash = self.admin_password_hash
            password_file = self.admin_password_file
            if configured_hash is None:
                raise ApiError(503, "ADMIN_DISABLED", "管理员登录未启用")
            if password_file is None:
                raise ApiError(503, "ADMIN_PASSWORD_CHANGE_DISABLED", "管理员密码文件未配置")
            if validate_admin_session(
                session_value,
                self._admin_session_signing_hash_locked(),
            ) is None:
                raise ApiError(401, "ADMIN_SESSION_REQUIRED", "需要有效的管理员会话")
            if not hmac.compare_digest(configured_hash, token_hash(current_password)):
                raise ApiError(400, "ADMIN_CURRENT_PASSWORD_INVALID", "当前密码错误")

            new_password_hash = token_hash(new_password)
            if hmac.compare_digest(configured_hash, new_password_hash):
                raise ApiError(400, "ADMIN_PASSWORD_UNCHANGED", "新密码不能与当前密码相同")
            try:
                replace_admin_password(password_file, new_password)
            except OSError as error:
                LOGGER.exception("更新管理员密码文件失败 path=%s", password_file)
                raise ApiError(
                    500,
                    "ADMIN_PASSWORD_UPDATE_FAILED",
                    "管理员密码保存失败，请检查服务端文件权限",
                ) from error
            self.admin_password_hash = new_password_hash
            self.admin_session_nonce = secrets.token_bytes(32)

    def check_admin_login_allowed(self, client_ip: str) -> None:
        now = int(time.time())
        with self.admin_login_lock:
            entry = self.admin_login_failures.get(client_ip)
            if entry is None:
                return
            window_started_at, failure_count = entry
            if now - window_started_at >= ADMIN_LOGIN_FAILURE_WINDOW_SECONDS:
                self.admin_login_failures.pop(client_ip, None)
                return
            if failure_count >= ADMIN_LOGIN_FAILURE_LIMIT:
                retry_after = max(
                    1,
                    ADMIN_LOGIN_FAILURE_WINDOW_SECONDS - (now - window_started_at),
                )
                raise ApiError(
                    429,
                    "ADMIN_LOGIN_RATE_LIMITED",
                    "登录失败次数过多，请稍后重试",
                    details={"retryAfter": retry_after},
                    headers={"Retry-After": str(retry_after)},
                )

    def record_admin_login_failure(self, client_ip: str) -> None:
        now = int(time.time())
        with self.admin_login_lock:
            window_started_at, failure_count = self.admin_login_failures.get(
                client_ip,
                (now, 0),
            )
            if now - window_started_at >= ADMIN_LOGIN_FAILURE_WINDOW_SECONDS:
                window_started_at, failure_count = now, 0
            self.admin_login_failures[client_ip] = (window_started_at, failure_count + 1)

    def clear_admin_login_failures(self, client_ip: str) -> None:
        with self.admin_login_lock:
            self.admin_login_failures.pop(client_ip, None)


class PluginMarketHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "HchatPluginMarket/1.0"
    sys_version = ""

    @property
    def market_server(self) -> PluginMarketServer:
        return self.server  # type: ignore[return-value]

    def do_GET(self) -> None:
        self._run(self._do_get)

    def do_POST(self) -> None:
        self._run(self._do_post)

    def do_DELETE(self) -> None:
        self._run(self._do_delete)

    def _run(self, operation: Any) -> None:
        self.request_id = "req_" + uuid.uuid4().hex
        self.rate_headers: dict[str, str] = {}
        try:
            operation()
        except ApiError as error:
            self._send_error(error)
        except (BrokenPipeError, ConnectionResetError):
            LOGGER.info("请求连接提前关闭 requestId=%s", self.request_id)
        except Exception as error:
            LOGGER.exception("未处理的请求异常 requestId=%s", self.request_id)
            self._send_error(ApiError(500, "INTERNAL_ERROR", "服务器内部错误"))

    def _do_get(self) -> None:
        parsed = urlsplit(self.path)
        path = parsed.path.rstrip("/") or "/"
        if path == "/admin":
            self._send_admin_page()
            return
        if path == "/v1/admin/session":
            expires_at = self._admin_session_expiration()
            self._send_json(
                200,
                {
                    "authenticated": expires_at is not None,
                    "expiresAt": format_utc_timestamp(expires_at) if expires_at else None,
                },
            )
            return
        if path == "/v1/admin/settings":
            self._require_admin_session()
            self._send_json(
                200,
                {
                    "uploadReviewEnabled": (
                        self.market_server.database.get_upload_review_enabled()
                    )
                },
            )
            return
        if path == "/v1/admin/blacklist":
            self._require_admin_session()
            items = self.market_server.database.list_blacklisted_uploaders()
            self._send_json(200, {"items": items, "count": len(items)})
            return
        admin_version_match = ADMIN_PLUGIN_VERSION_PATH_PATTERN.fullmatch(path)
        if admin_version_match:
            self._require_admin_session()
            detail = self.market_server.database.get_admin_plugin_version(
                admin_version_match.group(1),
                admin_version_match.group(2),
            )
            self._send_json(200, detail)
            return
        if path == "/v1/admin/plugins":
            self._require_admin_session()
            parameters = parse_qs(parsed.query, keep_blank_values=True)
            query = parameters.get("q", [""])[0].strip()
            if len(query) > 100:
                raise ApiError(400, "INVALID_QUERY", "q 长度不能超过 100 个字符")
            raw_limit = parameters.get("limit", ["100"])[0]
            try:
                limit = int(raw_limit)
            except ValueError as error:
                raise ApiError(400, "INVALID_LIMIT", "limit 必须是整数") from error
            if limit < 1 or limit > MAX_LIST_LIMIT:
                raise ApiError(400, "INVALID_LIMIT", f"limit 必须在 1 到 {MAX_LIST_LIMIT} 之间")
            items = self.market_server.database.list_admin_plugins(query, limit)
            self._send_json(200, {"items": items, "count": len(items), "limit": limit})
            return
        if path == "/health":
            self.market_server.database.health()
            self._send_json(200, {"status": "ok", "database": "ok"})
            return
        if path == NOTIFICATIONS_PATH:
            parameters = parse_qs(parsed.query, keep_blank_values=True)
            viewer = validate_user_identity(
                {"userWxId": parameters.get("userWxId", [""])[0]}
            )
            raw_limit = parameters.get("limit", ["100"])[0]
            try:
                limit = int(raw_limit)
            except ValueError as error:
                raise ApiError(400, "INVALID_LIMIT", "limit 必须是整数") from error
            if limit < 1 or limit > MAX_LIST_LIMIT:
                raise ApiError(400, "INVALID_LIMIT", f"limit 必须在 1 到 {MAX_LIST_LIMIT} 之间")
            result = self.market_server.database.list_plugin_notifications(
                self._social_actor_key(viewer["wxid"]),
                limit,
            )
            self._send_json(200, result)
            return
        if path == "/v1/plugins":
            parameters = parse_qs(parsed.query, keep_blank_values=True)
            query = parameters.get("q", [""])[0].strip()
            if len(query) > 100:
                raise ApiError(400, "INVALID_QUERY", "q 长度不能超过 100 个字符")
            sort = parameters.get("sort", ["latest"])[0].strip() or "latest"
            if sort not in {"latest", "downloads"}:
                raise ApiError(400, "INVALID_SORT", "sort 只支持 latest 或 downloads")
            raw_limit = parameters.get("limit", ["20"])[0]
            try:
                limit = int(raw_limit)
            except ValueError as error:
                raise ApiError(400, "INVALID_LIMIT", "limit 必须是整数") from error
            if limit < 1 or limit > MAX_LIST_LIMIT:
                raise ApiError(400, "INVALID_LIMIT", f"limit 必须在 1 到 {MAX_LIST_LIMIT} 之间")
            items = self.market_server.database.list_plugins(query, sort, limit)
            self._send_json(200, {"items": items, "count": len(items), "limit": limit})
            return
        like_match = LIKE_PATH_PATTERN.fullmatch(path)
        if like_match:
            parameters = parse_qs(parsed.query, keep_blank_values=True)
            user = validate_user_identity({"userWxId": parameters.get("userWxId", [""])[0]})
            actor_key_hash = self._social_actor_key(user["wxid"])
            result = self.market_server.database.like_status(
                like_match.group(1),
                user["wxid"],
                actor_key_hash,
            )
            self._send_json(200, result)
            return
        comments_match = COMMENTS_PATH_PATTERN.fullmatch(path)
        if comments_match:
            parameters = parse_qs(parsed.query, keep_blank_values=True)
            raw_limit = parameters.get("limit", ["50"])[0]
            try:
                limit = int(raw_limit)
            except ValueError as error:
                raise ApiError(400, "INVALID_LIMIT", "limit 必须是整数") from error
            if limit < 1 or limit > MAX_LIST_LIMIT:
                raise ApiError(400, "INVALID_LIMIT", f"limit 必须在 1 到 {MAX_LIST_LIMIT} 之间")
            raw_user_wxid = parameters.get("userWxId", [""])[0].strip()
            viewer_actor_key_hash = None
            if raw_user_wxid:
                viewer = validate_user_identity({"userWxId": raw_user_wxid})
                viewer_actor_key_hash = self._social_actor_key(viewer["wxid"])
            result = self.market_server.database.list_plugin_comments(
                comments_match.group(1),
                limit,
                viewer_actor_key_hash,
            )
            self._send_json(200, result)
            return
        snapshot_match = SNAPSHOT_DETAIL_PATH_PATTERN.fullmatch(path)
        if snapshot_match:
            snapshot = self.market_server.database.get_plugin_snapshot(
                snapshot_match.group(1),
                snapshot_match.group(2),
            )
            self._send_json(200, snapshot)
            return
        snapshot_list_match = SNAPSHOT_LIST_PATH_PATTERN.fullmatch(path)
        if snapshot_list_match:
            snapshots = self.market_server.database.list_plugin_snapshots(
                snapshot_list_match.group(1)
            )
            self._send_json(
                200,
                {
                    "pluginId": snapshot_list_match.group(1),
                    "items": snapshots,
                    "count": len(snapshots),
                },
            )
            return
        match = DETAIL_PATH_PATTERN.fullmatch(path)
        if match:
            detail = self.market_server.database.get_plugin_detail(match.group(1))
            self._send_json(200, detail)
            return
        raise ApiError(404, "NOT_FOUND", "接口不存在")

    def _do_post(self) -> None:
        parsed = urlsplit(self.path)
        path = parsed.path.rstrip("/") or "/"
        if path == "/v1/admin/session":
            client_ip = self._client_ip()
            self.market_server.check_admin_login_allowed(client_ip)
            payload = self._read_json_body()
            admin_password = payload.get("adminPassword") if isinstance(payload, dict) else None
            if not isinstance(admin_password, str) or not admin_password.strip():
                self.market_server.record_admin_login_failure(client_ip)
                raise ApiError(401, "ADMIN_PASSWORD_INVALID", "管理员密码错误")
            try:
                admin_password = normalize_admin_password(admin_password.strip())
            except ValueError:
                self.market_server.record_admin_login_failure(client_ip)
                raise ApiError(401, "ADMIN_PASSWORD_INVALID", "管理员密码错误")
            session = self.market_server.create_admin_session_for_password(admin_password)
            if session is None:
                self.market_server.record_admin_login_failure(client_ip)
                raise ApiError(401, "ADMIN_PASSWORD_INVALID", "管理员密码错误")
            self.market_server.clear_admin_login_failures(client_ip)
            session_value, expires_at = session
            self._send_json(
                200,
                {
                    "authenticated": True,
                    "expiresAt": format_utc_timestamp(expires_at),
                },
                headers={"Set-Cookie": self._admin_session_cookie(session_value)},
            )
            return
        if path == "/v1/admin/password":
            self._require_admin_session()
            client_ip = self._client_ip()
            self.market_server.check_admin_login_allowed(client_ip)
            payload = self._read_json_body()
            if not isinstance(payload, dict):
                raise ApiError(400, "INVALID_ADMIN_PASSWORD_PAYLOAD", "改密请求必须是 JSON 对象")
            current_value = payload.get("currentPassword")
            new_value = payload.get("newPassword")
            confirmation_value = payload.get("confirmPassword")
            if not all(isinstance(value, str) for value in (
                current_value,
                new_value,
                confirmation_value,
            )):
                raise ApiError(400, "INVALID_ADMIN_PASSWORD_PAYLOAD", "请完整填写密码")
            try:
                current_password = normalize_admin_password(current_value.strip())
                new_password = normalize_admin_password(new_value)
                confirmation = normalize_admin_password(confirmation_value)
            except ValueError as error:
                raise ApiError(400, "INVALID_ADMIN_PASSWORD_FORMAT", str(error)) from error
            if new_password != confirmation:
                raise ApiError(
                    400,
                    "ADMIN_PASSWORD_CONFIRMATION_MISMATCH",
                    "两次输入的新密码不一致",
                )
            try:
                self.market_server.change_admin_password(
                    self._admin_session_value(),
                    current_password,
                    new_password,
                )
            except ApiError as error:
                if error.code == "ADMIN_CURRENT_PASSWORD_INVALID":
                    self.market_server.record_admin_login_failure(client_ip)
                raise
            self.market_server.clear_admin_login_failures(client_ip)
            self._send_json(
                200,
                {"authenticated": False, "expiresAt": None},
                headers={"Set-Cookie": self._admin_session_cookie("", clear=True)},
            )
            return
        if path == "/v1/admin/settings":
            self._require_admin_session()
            payload = self._read_json_body()
            enabled = payload.get("uploadReviewEnabled") if isinstance(payload, dict) else None
            if not isinstance(enabled, bool):
                raise ApiError(
                    400,
                    "INVALID_UPLOAD_REVIEW_SETTING",
                    "uploadReviewEnabled 必须是布尔值",
                )
            self.market_server.database.set_upload_review_enabled(enabled)
            self._send_json(200, {"uploadReviewEnabled": enabled})
            return
        if path == "/v1/admin/blacklist":
            self._require_admin_session()
            payload = self._read_json_body()
            if not isinstance(payload, dict):
                raise ApiError(400, "INVALID_BLACKLIST_PAYLOAD", "黑名单请求必须是 JSON 对象")
            result = self.market_server.database.blacklist_uploader(
                validate_uploader_identity(payload)
            )
            self._send_json(201 if result["created"] else 200, result)
            return
        if path == "/v1/admin/plugins/batch-delete":
            self._require_admin_session()
            plugin_ids = validate_batch_delete_payload(self._read_json_body())
            result = self.market_server.database.batch_delete_plugins(plugin_ids)
            self._send_json(200, result)
            return
        approve_match = ADMIN_PLUGIN_APPROVE_PATH_PATTERN.fullmatch(path)
        if approve_match:
            self._require_admin_session()
            result = self.market_server.database.approve_plugin_version(
                approve_match.group(1),
                approve_match.group(2),
            )
            self._send_json(200, result)
            return
        download_match = DOWNLOAD_PATH_PATTERN.fullmatch(path)
        if download_match:
            payload = self._read_json_body()
            if not isinstance(payload, dict):
                raise ApiError(400, "INVALID_DOWNLOAD_PAYLOAD", "下载统计请求必须是 JSON 对象")
            version_id = payload.get("versionId")
            event_id = payload.get("eventId")
            if not isinstance(version_id, str) or VERSION_ID_PATTERN.fullmatch(version_id) is None:
                raise ApiError(400, "INVALID_VERSION_ID", "versionId 格式无效")
            if not isinstance(event_id, str) or DOWNLOAD_EVENT_ID_PATTERN.fullmatch(event_id) is None:
                raise ApiError(400, "INVALID_DOWNLOAD_EVENT_ID", "eventId 格式无效")
            result = self.market_server.database.record_plugin_download(
                download_match.group(1),
                version_id,
                event_id,
            )
            self._send_json(200, result)
            return
        like_match = LIKE_PATH_PATTERN.fullmatch(path)
        if like_match:
            user = validate_user_identity(self._read_json_body())
            result = self.market_server.database.like_plugin(
                like_match.group(1),
                user,
                self._social_actor_key(user["wxid"]),
            )
            self._send_json(201 if result["created"] else 200, result)
            return
        if path == NOTIFICATIONS_READ_PATH:
            user, notification_ids = validate_notification_read_payload(
                self._read_json_body()
            )
            result = self.market_server.database.mark_plugin_notifications_read(
                self._social_actor_key(user["wxid"]),
                notification_ids,
            )
            self._send_json(200, result)
            return
        reply_match = COMMENT_REPLY_PATH_PATTERN.fullmatch(path)
        if reply_match:
            user, content = validate_comment_payload(self._read_json_body())
            result = self.market_server.database.add_plugin_comment(
                reply_match.group(1),
                user,
                content,
                self._social_actor_key(user["wxid"]),
                parent_comment_id=reply_match.group(2),
            )
            self._send_json(201, result)
            return
        comments_match = COMMENTS_PATH_PATTERN.fullmatch(path)
        if comments_match:
            user, content = validate_comment_payload(self._read_json_body())
            result = self.market_server.database.add_plugin_comment(
                comments_match.group(1),
                user,
                content,
                self._social_actor_key(user["wxid"]),
            )
            self._send_json(201, result)
            return
        if path != "/v1/plugins":
            raise ApiError(404, "NOT_FOUND", "接口不存在")

        install_id = self.headers.get("X-Hchat-Install-Id", "").strip()
        if len(install_id) < 8 or len(install_id) > 128:
            raise ApiError(
                400,
                "INSTALL_ID_REQUIRED",
                "X-Hchat-Install-Id 必须是 8 到 128 个字符的随机安装标识",
            )
        if any(ord(character) < 33 or ord(character) > 126 for character in install_id):
            raise ApiError(400, "INVALID_INSTALL_ID", "X-Hchat-Install-Id 格式无效")
        client_ip = self._client_ip()
        quota = self.market_server.database.consume_upload_quota(
            install_id,
            client_ip,
            self.market_server.rate_limit_count,
            self.market_server.rate_limit_window,
        )
        self.rate_headers = {
            "X-RateLimit-Limit": str(quota["limit"]),
            "X-RateLimit-Remaining": str(quota["remaining"]),
            "X-RateLimit-Reset": str(quota["reset"]),
        }

        upload = validate_upload_payload(self._read_json_body())
        if upload["owner_token"] is None:
            upload["owner_token"] = self._bearer_token()
        uploader_key_hash = sha256_hex(f"{install_id}\n{client_ip}".encode("utf-8"))
        result, status = self.market_server.database.save_plugin(upload, uploader_key_hash)
        self._send_json(status, result)

    def _do_delete(self) -> None:
        parsed = urlsplit(self.path)
        path = parsed.path.rstrip("/") or "/"
        if path == "/v1/admin/session":
            self._send_json(
                200,
                {"authenticated": False, "expiresAt": None},
                headers={"Set-Cookie": self._admin_session_cookie("", clear=True)},
            )
            return
        admin_version_match = ADMIN_PLUGIN_VERSION_PATH_PATTERN.fullmatch(path)
        if admin_version_match:
            self._require_admin_session()
            result = self.market_server.database.delete_plugin_version(
                admin_version_match.group(1),
                admin_version_match.group(2),
            )
            self._send_json(200, result)
            return
        blacklist_match = ADMIN_BLACKLIST_ITEM_PATH_PATTERN.fullmatch(path)
        if blacklist_match:
            self._require_admin_session()
            try:
                wxid = unquote(blacklist_match.group(1), encoding="utf-8", errors="strict")
            except UnicodeDecodeError as error:
                raise ApiError(400, "INVALID_UPLOADER_WXID", "黑名单 wxid 编码无效") from error
            uploader = validate_uploader_identity({"uploaderWxId": wxid})
            result = self.market_server.database.unblacklist_uploader(uploader["wxid"])
            self._send_json(200, result)
            return
        comment_match = COMMENT_DETAIL_PATH_PATTERN.fullmatch(path)
        if comment_match:
            administrator = self._is_admin_session()
            content_length = self.headers.get("Content-Length")
            if administrator:
                if content_length not in {None, "", "0"}:
                    self._read_json_body()
                actor_key_hash = ""
            else:
                user = validate_user_identity(self._read_json_body())
                actor_key_hash = self._social_actor_key(user["wxid"])
            result = self.market_server.database.delete_plugin_comment(
                comment_match.group(1),
                comment_match.group(2),
                actor_key_hash,
                administrator=administrator,
            )
            self._send_json(200, result)
            return
        like_match = LIKE_PATH_PATTERN.fullmatch(path)
        if like_match:
            user = validate_user_identity(self._read_json_body())
            result = self.market_server.database.unlike_plugin(
                like_match.group(1),
                user["wxid"],
                self._social_actor_key(user["wxid"]),
            )
            self._send_json(200, result)
            return
        match = DETAIL_PATH_PATTERN.fullmatch(path)
        if not match:
            raise ApiError(404, "NOT_FOUND", "接口不存在")
        bearer_token = self._bearer_token()
        administrator = self._is_admin_session()
        owner_token = "" if administrator else bearer_token
        content_length = self.headers.get("Content-Length")
        if not administrator and not owner_token and content_length not in {None, "", "0"}:
            payload = self._read_json_body()
            if isinstance(payload, dict) and isinstance(payload.get("ownerToken"), str):
                owner_token = payload["ownerToken"].strip()
        self.market_server.database.delete_plugin(
            match.group(1),
            owner_token,
            administrator=administrator,
        )
        self._send_json(200, {"pluginId": match.group(1), "deleted": True})

    def _read_json_body(self) -> Any:
        content_type = self.headers.get("Content-Type", "")
        if not content_type.lower().startswith("application/json"):
            raise ApiError(415, "CONTENT_TYPE_REQUIRED", "Content-Type 必须是 application/json")
        raw_length = self.headers.get("Content-Length")
        if raw_length is None:
            raise ApiError(411, "CONTENT_LENGTH_REQUIRED", "必须提供 Content-Length")
        try:
            content_length = int(raw_length)
        except ValueError as error:
            raise ApiError(400, "INVALID_CONTENT_LENGTH", "Content-Length 无效") from error
        if content_length < 1:
            raise ApiError(400, "EMPTY_BODY", "请求体不能为空")
        if content_length > MAX_REQUEST_BODY:
            raise ApiError(
                413,
                "REQUEST_TOO_LARGE",
                f"请求体超过 {MAX_REQUEST_BODY // (1024 * 1024)} MiB",
            )
        body = self.rfile.read(content_length)
        if len(body) != content_length:
            raise ApiError(400, "INCOMPLETE_BODY", "请求体不完整")
        try:
            return json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ApiError(400, "INVALID_JSON", "请求体不是有效的 UTF-8 JSON") from error

    def _require_install_id(self) -> str:
        install_id = self.headers.get("X-Hchat-Install-Id", "").strip()
        if len(install_id) < 8 or len(install_id) > 128:
            raise ApiError(
                400,
                "INSTALL_ID_REQUIRED",
                "X-Hchat-Install-Id 必须是 8 到 128 个字符的随机安装标识",
            )
        if any(ord(character) < 33 or ord(character) > 126 for character in install_id):
            raise ApiError(400, "INVALID_INSTALL_ID", "X-Hchat-Install-Id 格式无效")
        return install_id

    def _social_actor_key(self, user_wxid: str) -> str:
        install_id = self._require_install_id()
        return sha256_hex(f"social-v1\n{install_id}\n{user_wxid}".encode("utf-8"))

    def _client_ip(self) -> str:
        candidate = self.client_address[0]
        if self.market_server.trust_proxy:
            forwarded = self.headers.get("X-Forwarded-For", "").split(",", 1)[0].strip()
            real_ip = self.headers.get("X-Real-IP", "").strip()
            candidate = forwarded or real_ip or candidate
        try:
            return str(ipaddress.ip_address(candidate))
        except ValueError:
            return str(self.client_address[0])

    def _bearer_token(self) -> str:
        authorization = self.headers.get("Authorization", "").strip()
        if not authorization.lower().startswith("bearer "):
            return ""
        return authorization[7:].strip()

    def _admin_session_value(self) -> str:
        raw_cookie = self.headers.get("Cookie", "")
        if not raw_cookie:
            return ""
        try:
            cookies = SimpleCookie()
            cookies.load(raw_cookie)
        except CookieError:
            return ""
        session = cookies.get(ADMIN_SESSION_COOKIE)
        if session is None:
            return ""
        return session.value

    def _admin_session_expiration(self) -> int | None:
        return self.market_server.validate_admin_session(self._admin_session_value())

    def _is_admin_session(self) -> bool:
        return self._admin_session_expiration() is not None

    def _require_admin_session(self) -> None:
        if not self._is_admin_session():
            raise ApiError(401, "ADMIN_SESSION_REQUIRED", "需要有效的管理员会话")

    @staticmethod
    def _admin_session_cookie(value: str, *, clear: bool = False) -> str:
        cookie = SimpleCookie()
        cookie[ADMIN_SESSION_COOKIE] = value
        morsel = cookie[ADMIN_SESSION_COOKIE]
        morsel["path"] = "/"
        morsel["secure"] = True
        morsel["httponly"] = True
        morsel["samesite"] = "Strict"
        morsel["max-age"] = 0 if clear else ADMIN_SESSION_TTL_SECONDS
        if clear:
            morsel["expires"] = "Thu, 01 Jan 1970 00:00:00 GMT"
        return morsel.OutputString()

    def _send_admin_page(self) -> None:
        try:
            template = ADMIN_PAGE_PATH.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as error:
            raise ApiError(500, "ADMIN_PAGE_UNAVAILABLE", "管理页面不可用") from error
        if ADMIN_PAGE_NONCE_PLACEHOLDER not in template:
            raise ApiError(500, "ADMIN_PAGE_INVALID", "管理页面配置无效")
        nonce = secrets.token_urlsafe(24)
        body = template.replace(ADMIN_PAGE_NONCE_PLACEHOLDER, nonce).encode("utf-8")
        content_security_policy = (
            "default-src 'none'; "
            f"script-src 'nonce-{nonce}'; style-src 'nonce-{nonce}'; "
            "connect-src 'self'; img-src 'self' data:; "
            "base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
        )
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Security-Policy", content_security_policy)
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Robots-Tag", "noindex, nofollow")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Request-Id", self.request_id)
        self.end_headers()
        self.wfile.write(body)

    def _send_error(self, error: ApiError) -> None:
        payload: dict[str, Any] = {
            "code": error.code,
            "message": error.message,
        }
        if error.details is not None:
            payload["details"] = error.details
        headers = dict(self.rate_headers)
        headers.update(error.headers)
        self._send_json(error.status, None, error=payload, headers=headers)

    def _send_json(
        self,
        status: int,
        data: Any,
        *,
        error: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        envelope = {
            "ok": error is None,
            "data": data if error is None else None,
            "requestId": self.request_id,
        }
        if error is not None:
            envelope.pop("data", None)
            envelope["error"] = error
        body = json.dumps(envelope, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(int(status))
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Request-Id", self.request_id)
        combined_headers = dict(self.rate_headers)
        combined_headers.update(headers or {})
        for name, value in combined_headers.items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, message_format: str, *args: Any) -> None:
        LOGGER.info(
            "%s %s requestId=%s",
            self.client_address[0],
            message_format % args,
            getattr(self, "request_id", "-"),
        )


def parse_arguments() -> argparse.Namespace:
    base_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Hchat 在线插件市场服务")
    parser.add_argument("--host", default="127.0.0.1", help="监听地址，默认 127.0.0.1")
    parser.add_argument("--port", type=int, default=8765, help="监听端口，默认 8765")
    parser.add_argument(
        "--database",
        type=Path,
        default=base_dir / "data" / "plugin-market.db",
        help="SQLite 数据库路径",
    )
    parser.add_argument(
        "--migrations",
        type=Path,
        default=base_dir / "migrations",
        help="数据库迁移目录",
    )
    parser.add_argument("--rate-limit-count", type=int, default=10, help="每个限频窗口允许的上传次数")
    parser.add_argument("--rate-limit-window", type=int, default=3600, help="上传限频窗口秒数")
    parser.add_argument(
        "--trust-proxy",
        action="store_true",
        help="信任 Nginx 写入的 X-Forwarded-For/X-Real-IP",
    )
    parser.add_argument(
        "--admin-password-file",
        type=Path,
        help="管理员密码文件；不配置则关闭管理员登录和删除",
    )
    parser.add_argument(
        "--log-level",
        choices=("DEBUG", "INFO", "WARNING", "ERROR"),
        default="INFO",
        help="日志级别",
    )
    arguments = parser.parse_args()
    if arguments.port < 1 or arguments.port > 65535:
        parser.error("--port 必须在 1 到 65535 之间")
    if arguments.rate_limit_count < 1:
        parser.error("--rate-limit-count 必须大于 0")
    if arguments.rate_limit_window < 1:
        parser.error("--rate-limit-window 必须大于 0")
    return arguments


def main() -> int:
    arguments = parse_arguments()
    logging.basicConfig(
        level=getattr(logging, arguments.log_level),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    try:
        admin_password_hash = load_admin_password_hash(arguments.admin_password_file)
    except (OSError, UnicodeError, ValueError) as error:
        LOGGER.error("无法读取管理员密码文件 %s: %s", arguments.admin_password_file, error)
        return 2
    database = MarketDatabase(arguments.database.resolve(), arguments.migrations.resolve())
    server = PluginMarketServer(
        (arguments.host, arguments.port),
        database,
        rate_limit_count=arguments.rate_limit_count,
        rate_limit_window=arguments.rate_limit_window,
        trust_proxy=arguments.trust_proxy,
        admin_password_hash=admin_password_hash,
        admin_password_file=(
            arguments.admin_password_file.resolve()
            if arguments.admin_password_file is not None
            else None
        ),
    )

    def stop_server(signum: int, _frame: Any) -> None:
        LOGGER.info("收到信号 %s，正在停止服务", signum)
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop_server)
    signal.signal(signal.SIGINT, stop_server)
    LOGGER.info(
        "Hchat 插件市场已启动 http://%s:%s database=%s",
        arguments.host,
        arguments.port,
        arguments.database.resolve(),
    )
    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        server.server_close()
        LOGGER.info("Hchat 插件市场已停止")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
