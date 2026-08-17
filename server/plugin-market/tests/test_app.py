import base64
import importlib.util
import json
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.cookies import SimpleCookie
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("plugin_market_app", ROOT / "app.py")
APP = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(APP)


class PluginMarketApiTest(unittest.TestCase):
    ADMIN_PASSWORD = "test-admin-password"

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        database = APP.MarketDatabase(
            Path(self.temp_dir.name) / "market.db",
            ROOT / "migrations",
        )
        self.admin_password_file = Path(self.temp_dir.name) / "admin.password"
        self.admin_password_file.write_text(self.ADMIN_PASSWORD + "\n", encoding="utf-8")
        self.server = APP.PluginMarketServer(
            ("127.0.0.1", 0),
            database,
            rate_limit_count=20,
            rate_limit_window=3600,
            trust_proxy=False,
            admin_password_hash=APP.token_hash(self.ADMIN_PASSWORD),
            admin_password_file=self.admin_password_file,
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.temp_dir.cleanup()

    def request_raw(self, method, path, body=None, headers=None):
        encoded = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request_headers = dict(headers or {})
        if encoded is not None:
            request_headers["Content-Type"] = "application/json"
        parsed = urllib.parse.urlsplit(path)
        encoded_path = urllib.parse.urlunsplit((
            parsed.scheme,
            parsed.netloc,
            urllib.parse.quote(parsed.path),
            urllib.parse.urlencode(urllib.parse.parse_qsl(parsed.query)),
            parsed.fragment,
        ))
        request = urllib.request.Request(
            self.base_url + encoded_path,
            data=encoded,
            headers=request_headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.read(), response.headers
        except urllib.error.HTTPError as error:
            return error.code, error.read(), error.headers

    def request(self, method, path, body=None, headers=None):
        status, response_body, _ = self.request_raw(method, path, body, headers)
        return status, json.loads(response_body.decode("utf-8"))

    def admin_login(self, password=None):
        status, response_body, headers = self.request_raw(
            "POST",
            "/v1/admin/session",
            {"adminPassword": self.ADMIN_PASSWORD if password is None else password},
        )
        response = json.loads(response_body.decode("utf-8"))
        cookie_header = headers.get("Set-Cookie")
        session_cookie = None
        if cookie_header:
            cookies = SimpleCookie()
            cookies.load(cookie_header)
            morsel = cookies.get(APP.ADMIN_SESSION_COOKIE)
            if morsel is not None:
                session_cookie = f"{APP.ADMIN_SESSION_COOKIE}={morsel.value}"
        return status, response, headers, session_cookie

    def upload_body(self):
        return {
            "displayName": "测试插件",
            "sourcePluginId": "test_plugin",
            "versionName": "1.0.0",
            "author": "tester",
            "uploaderWxId": "wxid_test_uploader",
            "uploaderWeChatId": "test_wechat_id",
            "uploaderNickname": "测试上传者",
            "files": [
                {"name": "main.java", "content": "void onLoad() {}"},
                {"name": "README.md", "content": "# 测试"},
                {"name": "info.prop", "content": "name=测试插件"},
            ],
        }

    def user_body(self, wxid="wxid_social_user", nickname="互动用户"):
        return {
            "userWxId": wxid,
            "userWeChatId": f"wechat_{wxid}",
            "userNickname": nickname,
        }

    def social_headers(self, wxid="wxid_social_user"):
        return {"X-Hchat-Install-Id": f"social-install-{wxid}"}

    def create_plugin(self, install_id):
        status, created = self.request(
            "POST",
            "/v1/plugins",
            self.upload_body(),
            {"X-Hchat-Install-Id": install_id},
        )
        self.assertEqual(status, 201)
        return created["data"]

    def admin_headers(self):
        status, _, _, session_cookie = self.admin_login()
        self.assertEqual(status, 200)
        self.assertIsNotNone(session_cookie)
        return {"Cookie": session_cookie}

    def test_create_list_detail_update_and_delete(self):
        status, health = self.request("GET", "/health")
        self.assertEqual(status, 200)
        self.assertTrue(health["ok"])

        headers = {"X-Hchat-Install-Id": "test-install-id-0001"}
        status, created = self.request("POST", "/v1/plugins", self.upload_body(), headers)
        self.assertEqual(status, 201)
        plugin_id = created["data"]["pluginId"]
        owner_token = created["data"]["ownerToken"]

        status, listed = self.request("GET", "/v1/plugins?q=测试&sort=latest&limit=20")
        self.assertEqual(status, 200)
        self.assertEqual(listed["data"]["items"][0]["pluginId"], plugin_id)

        status, detail = self.request("GET", f"/v1/plugins/{plugin_id}")
        self.assertEqual(status, 200)
        self.assertEqual(detail["data"]["downloadCount"], 0)
        self.assertEqual([item["name"] for item in detail["data"]["files"]], [
            "main.java",
            "info.prop",
            "README.md",
        ])
        status, reopened = self.request("GET", f"/v1/plugins/{plugin_id}")
        self.assertEqual(status, 200)
        self.assertEqual(reopened["data"]["downloadCount"], 0)

        status, downloaded = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/downloads",
            {
                "versionId": created["data"]["versionId"],
                "eventId": "0123456789abcdef0123456789abcdef",
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(downloaded["data"]["downloadCount"], 1)
        self.assertTrue(downloaded["data"]["recorded"])
        status, duplicate = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/downloads",
            {
                "versionId": created["data"]["versionId"],
                "eventId": "0123456789abcdef0123456789abcdef",
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(duplicate["data"]["downloadCount"], 1)
        self.assertFalse(duplicate["data"]["recorded"])
        status, detail = self.request("GET", f"/v1/plugins/{plugin_id}")
        self.assertEqual(status, 200)
        self.assertEqual(detail["data"]["downloadCount"], 1)

        update = self.upload_body()
        update["pluginId"] = plugin_id
        update["ownerToken"] = owner_token
        update["versionName"] = "1.1.0"
        update["files"][0]["content"] = "void onLoad() { log(\"updated\"); }"
        status, updated = self.request("POST", "/v1/plugins", update, headers)
        self.assertEqual(status, 200)
        self.assertFalse(updated["data"]["created"])
        self.assertNotEqual(updated["data"]["versionId"], created["data"]["versionId"])

        status, deleted = self.request(
            "DELETE",
            f"/v1/plugins/{plugin_id}",
            headers={"Authorization": f"Bearer {owner_token}"},
        )
        self.assertEqual(status, 200)
        self.assertTrue(deleted["data"]["deleted"])

        status, missing = self.request("GET", f"/v1/plugins/{plugin_id}")
        self.assertEqual(status, 404)
        self.assertEqual(missing["error"]["code"], "PLUGIN_NOT_FOUND")

    def test_plugin_likes_and_comments_persist_with_delete_permissions(self):
        created = self.create_plugin("social-feature-install")
        plugin_id = created["pluginId"]
        alice = self.user_body("wxid_social_alice", "小爱")
        bob = self.user_body("wxid_social_bob", "小波")

        status, detail = self.request("GET", f"/v1/plugins/{plugin_id}")
        self.assertEqual(status, 200)
        self.assertEqual(detail["data"]["likeCount"], 0)
        self.assertEqual(detail["data"]["commentCount"], 0)

        status, liked = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/likes",
            alice,
            self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 201)
        self.assertTrue(liked["data"]["created"])
        self.assertEqual(liked["data"]["likeCount"], 1)

        renamed_alice = dict(alice)
        renamed_alice["userNickname"] = "更新昵称"
        status, duplicate = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/likes",
            renamed_alice,
            self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 200)
        self.assertFalse(duplicate["data"]["created"])
        self.assertEqual(duplicate["data"]["likeCount"], 1)
        with self.server.database.session() as connection:
            like_rows = connection.execute(
                "SELECT user_nickname FROM plugin_likes WHERE plugin_id = ?",
                (plugin_id,),
            ).fetchall()
        self.assertEqual(len(like_rows), 1)
        self.assertEqual(like_rows[0]["user_nickname"], "更新昵称")

        status, alice_status = self.request(
            "GET",
            f"/v1/plugins/{plugin_id}/likes?userWxId={alice['userWxId']}",
            headers=self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 200)
        self.assertTrue(alice_status["data"]["liked"])
        status, spoofed_status = self.request(
            "GET",
            f"/v1/plugins/{plugin_id}/likes?userWxId={alice['userWxId']}",
            headers={"X-Hchat-Install-Id": "different-social-install"},
        )
        self.assertEqual(status, 200)
        self.assertFalse(spoofed_status["data"]["liked"])
        status, spoofed_unlike = self.request(
            "DELETE",
            f"/v1/plugins/{plugin_id}/likes",
            alice,
            {"X-Hchat-Install-Id": "different-social-install"},
        )
        self.assertEqual(status, 403)
        self.assertEqual(spoofed_unlike["error"]["code"], "LIKE_DELETE_FORBIDDEN")

        status, bob_liked = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/likes",
            bob,
            self.social_headers(bob["userWxId"]),
        )
        self.assertEqual(status, 201)
        self.assertEqual(bob_liked["data"]["likeCount"], 2)
        status, unliked = self.request(
            "DELETE",
            f"/v1/plugins/{plugin_id}/likes",
            bob,
            self.social_headers(bob["userWxId"]),
        )
        self.assertEqual(status, 200)
        self.assertTrue(unliked["data"]["removed"])
        self.assertEqual(unliked["data"]["likeCount"], 1)
        status, repeated_unlike = self.request(
            "DELETE",
            f"/v1/plugins/{plugin_id}/likes",
            bob,
            self.social_headers(bob["userWxId"]),
        )
        self.assertEqual(status, 200)
        self.assertFalse(repeated_unlike["data"]["removed"])
        self.assertEqual(repeated_unlike["data"]["likeCount"], 1)

        alice_comment = dict(alice)
        alice_comment["content"] = "  第一条评论  "
        status, first_comment = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/comments",
            alice_comment,
            self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 201)
        self.assertEqual(first_comment["data"]["comment"]["content"], "第一条评论")
        self.assertEqual(first_comment["data"]["commentCount"], 1)
        first_comment_id = first_comment["data"]["comment"]["commentId"]

        bob_comment = dict(bob)
        bob_comment["content"] = "第二条评论"
        status, second_comment = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/comments",
            bob_comment,
            self.social_headers(bob["userWxId"]),
        )
        self.assertEqual(status, 201)
        second_comment_id = second_comment["data"]["comment"]["commentId"]

        status, comments = self.request(
            "GET",
            f"/v1/plugins/{plugin_id}/comments?limit=1",
        )
        self.assertEqual(status, 200)
        self.assertEqual(comments["data"]["count"], 1)
        self.assertEqual(comments["data"]["total"], 2)
        self.assertEqual(comments["data"]["limit"], 1)
        self.assertNotIn("userWxId", comments["data"]["items"][0])
        self.assertNotIn("userWeChatId", comments["data"]["items"][0])
        self.assertFalse(comments["data"]["items"][0]["canDelete"])
        status, alice_comments = self.request(
            "GET",
            f"/v1/plugins/{plugin_id}/comments?limit=100&userWxId={alice['userWxId']}",
            headers=self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 200)
        ownership_by_id = {
            item["commentId"]: item["canDelete"]
            for item in alice_comments["data"]["items"]
        }
        self.assertTrue(ownership_by_id[first_comment_id])
        self.assertFalse(ownership_by_id[second_comment_id])

        status, spoofed_comment_delete = self.request(
            "DELETE",
            f"/v1/plugins/{plugin_id}/comments/{first_comment_id}",
            alice,
            {"X-Hchat-Install-Id": "different-social-install"},
        )
        self.assertEqual(status, 403)
        self.assertEqual(
            spoofed_comment_delete["error"]["code"],
            "COMMENT_DELETE_FORBIDDEN",
        )

        status, forbidden = self.request(
            "DELETE",
            f"/v1/plugins/{plugin_id}/comments/{first_comment_id}",
            bob,
            self.social_headers(bob["userWxId"]),
        )
        self.assertEqual(status, 403)
        self.assertEqual(forbidden["error"]["code"], "COMMENT_DELETE_FORBIDDEN")

        status, author_deleted = self.request(
            "DELETE",
            f"/v1/plugins/{plugin_id}/comments/{first_comment_id}",
            alice,
            self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 200)
        self.assertEqual(author_deleted["data"]["commentCount"], 1)

        status, admin_deleted = self.request(
            "DELETE",
            f"/v1/plugins/{plugin_id}/comments/{second_comment_id}",
            headers=self.admin_headers(),
        )
        self.assertEqual(status, 200)
        self.assertEqual(admin_deleted["data"]["commentCount"], 0)

        status, listed = self.request("GET", "/v1/plugins?limit=20")
        self.assertEqual(status, 200)
        listed_plugin = next(
            item for item in listed["data"]["items"] if item["pluginId"] == plugin_id
        )
        self.assertEqual(listed_plugin["likeCount"], 1)
        self.assertEqual(listed_plugin["commentCount"], 0)

        final_comment = dict(alice)
        final_comment["content"] = "随插件级联删除"
        self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/comments",
            final_comment,
            self.social_headers(alice["userWxId"]),
        )
        status, deleted = self.request(
            "DELETE",
            f"/v1/plugins/{plugin_id}",
            headers={"Authorization": f"Bearer {created['ownerToken']}"},
        )
        self.assertEqual(status, 200)
        with self.server.database.session() as connection:
            like_count = connection.execute(
                "SELECT COUNT(*) FROM plugin_likes WHERE plugin_id = ?",
                (plugin_id,),
            ).fetchone()[0]
            comment_count = connection.execute(
                "SELECT COUNT(*) FROM plugin_comments WHERE plugin_id = ?",
                (plugin_id,),
            ).fetchone()[0]
        self.assertEqual(like_count, 0)
        self.assertEqual(comment_count, 0)

    def test_plugin_social_input_validation_and_missing_rows(self):
        created = self.create_plugin("social-validation-install")
        plugin_id = created["pluginId"]

        status, invalid_identity = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/likes",
            {"userWxId": "含空格 wxid"},
            self.social_headers(),
        )
        self.assertEqual(status, 400)
        self.assertEqual(invalid_identity["error"]["code"], "INVALID_USER_WXID")

        empty_comment = self.user_body()
        empty_comment["content"] = " \n "
        status, empty = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/comments",
            empty_comment,
            self.social_headers(),
        )
        self.assertEqual(status, 400)
        self.assertEqual(empty["error"]["code"], "INVALID_COMMENT_CONTENT")

        long_comment = self.user_body()
        long_comment["content"] = "评" * (APP.MAX_COMMENT_LENGTH + 1)
        status, too_long = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/comments",
            long_comment,
            self.social_headers(),
        )
        self.assertEqual(status, 400)
        self.assertEqual(too_long["error"]["code"], "INVALID_COMMENT_CONTENT")

        status, invalid_limit = self.request(
            "GET",
            f"/v1/plugins/{plugin_id}/comments?limit=0",
        )
        self.assertEqual(status, 400)
        self.assertEqual(invalid_limit["error"]["code"], "INVALID_LIMIT")

        missing_comment_id = "c_" + "f" * 32
        status, missing_comment = self.request(
            "DELETE",
            f"/v1/plugins/{plugin_id}/comments/{missing_comment_id}",
            self.user_body(),
            self.social_headers(),
        )
        self.assertEqual(status, 404)
        self.assertEqual(missing_comment["error"]["code"], "COMMENT_NOT_FOUND")

        missing_plugin_id = "p_" + "f" * 32
        status, missing_plugin = self.request(
            "POST",
            f"/v1/plugins/{missing_plugin_id}/likes",
            self.user_body(),
            self.social_headers(),
        )
        self.assertEqual(status, 404)
        self.assertEqual(missing_plugin["error"]["code"], "PLUGIN_NOT_FOUND")

    def test_plugin_comment_replies_and_notifications(self):
        first_plugin = self.create_plugin("comment-reply-install")
        second_plugin = self.create_plugin("comment-reply-other-install")
        plugin_id = first_plugin["pluginId"]
        alice = self.user_body("wxid_reply_alice", "小爱")
        bob = self.user_body("wxid_reply_bob", "小波")

        root_payload = dict(alice)
        root_payload["content"] = "原评论内容"
        status, root_result = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/comments",
            root_payload,
            self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 201)
        root_comment_id = root_result["data"]["comment"]["commentId"]
        self.assertEqual(root_result["data"]["comment"]["parentCommentId"], "")
        self.assertEqual(root_result["data"]["comment"]["replyToNickname"], "")

        reply_payload = dict(bob)
        reply_payload["content"] = "回复原评论"
        status, reply_result = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/comments/{root_comment_id}/replies",
            reply_payload,
            self.social_headers(bob["userWxId"]),
        )
        self.assertEqual(status, 201)
        reply = reply_result["data"]["comment"]
        reply_comment_id = reply["commentId"]
        self.assertEqual(reply["parentCommentId"], root_comment_id)
        self.assertEqual(reply["replyToNickname"], "小爱")
        self.assertEqual(reply_result["data"]["commentCount"], 2)

        status, reply_context = self.request(
            "GET",
            f"/v1/plugins/{plugin_id}/comments?limit=1",
        )
        self.assertEqual(status, 200)
        self.assertEqual(reply_context["data"]["count"], 2)
        self.assertEqual(reply_context["data"]["limit"], 1)
        self.assertEqual(
            {item["commentId"] for item in reply_context["data"]["items"]},
            {root_comment_id, reply_comment_id},
        )

        nested_payload = dict(alice)
        nested_payload["content"] = "回复回复"
        status, nested_result = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/comments/{reply_comment_id}/replies",
            nested_payload,
            self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 201)
        nested = nested_result["data"]["comment"]
        self.assertEqual(nested["parentCommentId"], reply_comment_id)
        self.assertEqual(nested["replyToNickname"], "小波")

        status, comments = self.request(
            "GET",
            f"/v1/plugins/{plugin_id}/comments?limit=100&userWxId={alice['userWxId']}",
            headers=self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 200)
        comments_by_id = {
            item["commentId"]: item for item in comments["data"]["items"]
        }
        self.assertEqual(comments_by_id[reply_comment_id]["parentCommentId"], root_comment_id)
        self.assertEqual(comments_by_id[reply_comment_id]["replyToNickname"], "小爱")
        self.assertNotIn("userWxId", comments_by_id[reply_comment_id])
        self.assertNotIn("actorKeyHash", comments_by_id[reply_comment_id])

        status, alice_notifications = self.request(
            "GET",
            f"/v1/notifications?userWxId={alice['userWxId']}&limit=100",
            headers=self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 200)
        self.assertEqual(alice_notifications["data"]["unreadCount"], 1)
        self.assertEqual(alice_notifications["data"]["total"], 1)
        notification = alice_notifications["data"]["items"][0]
        notification_id = notification["notificationId"]
        self.assertEqual(notification["type"], "comment_reply")
        self.assertEqual(notification["pluginId"], plugin_id)
        self.assertEqual(notification["pluginName"], "测试插件")
        self.assertEqual(notification["replyCommentId"], reply_comment_id)
        self.assertEqual(notification["parentCommentId"], root_comment_id)
        self.assertEqual(notification["actorNickname"], "小波")
        self.assertEqual(notification["content"], "回复原评论")
        self.assertEqual(notification["originalContent"], "原评论内容")
        self.assertFalse(notification["read"])
        self.assertNotIn("recipientActorKeyHash", notification)

        status, bob_notifications = self.request(
            "GET",
            f"/v1/notifications?userWxId={bob['userWxId']}&limit=100",
            headers=self.social_headers(bob["userWxId"]),
        )
        self.assertEqual(status, 200)
        self.assertEqual(bob_notifications["data"]["unreadCount"], 1)
        self.assertEqual(bob_notifications["data"]["items"][0]["content"], "回复回复")
        status, bob_marked = self.request(
            "POST",
            "/v1/notifications/read",
            bob,
            self.social_headers(bob["userWxId"]),
        )
        self.assertEqual(status, 200)
        self.assertEqual(bob_marked["data"]["markedCount"], 1)
        self.assertEqual(bob_marked["data"]["unreadCount"], 0)

        status, spoofed_notifications = self.request(
            "GET",
            f"/v1/notifications?userWxId={alice['userWxId']}&limit=100",
            headers={"X-Hchat-Install-Id": "different-social-install"},
        )
        self.assertEqual(status, 200)
        self.assertEqual(spoofed_notifications["data"]["total"], 0)

        status, spoofed_read = self.request(
            "POST",
            "/v1/notifications/read",
            {**alice, "notificationIds": [notification_id]},
            {"X-Hchat-Install-Id": "different-social-install"},
        )
        self.assertEqual(status, 200)
        self.assertEqual(spoofed_read["data"]["markedCount"], 0)

        status, marked = self.request(
            "POST",
            "/v1/notifications/read",
            {**alice, "notificationIds": [notification_id, notification_id]},
            self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 200)
        self.assertEqual(marked["data"]["markedCount"], 1)
        self.assertEqual(marked["data"]["unreadCount"], 0)
        status, repeated_mark = self.request(
            "POST",
            "/v1/notifications/read",
            {**alice, "notificationIds": [notification_id]},
            self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 200)
        self.assertEqual(repeated_mark["data"]["markedCount"], 0)

        self_reply_payload = dict(alice)
        self_reply_payload["content"] = "自己回复自己"
        status, _ = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/comments/{root_comment_id}/replies",
            self_reply_payload,
            self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 201)
        status, alice_notifications = self.request(
            "GET",
            f"/v1/notifications?userWxId={alice['userWxId']}&limit=100",
            headers=self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 200)
        self.assertEqual(alice_notifications["data"]["total"], 1)

        foreign_root = dict(alice)
        foreign_root["content"] = "另一个插件的评论"
        status, foreign_result = self.request(
            "POST",
            f"/v1/plugins/{second_plugin['pluginId']}/comments",
            foreign_root,
            self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 201)
        foreign_comment_id = foreign_result["data"]["comment"]["commentId"]
        status, cross_plugin = self.request(
            "POST",
            f"/v1/plugins/{plugin_id}/comments/{foreign_comment_id}/replies",
            reply_payload,
            self.social_headers(bob["userWxId"]),
        )
        self.assertEqual(status, 404)
        self.assertEqual(cross_plugin["error"]["code"], "COMMENT_NOT_FOUND")

        status, deleted = self.request(
            "DELETE",
            f"/v1/plugins/{plugin_id}/comments/{root_comment_id}",
            alice,
            self.social_headers(alice["userWxId"]),
        )
        self.assertEqual(status, 200)
        self.assertEqual(deleted["data"]["commentCount"], 0)
        with self.server.database.session() as connection:
            notification_count = connection.execute(
                "SELECT COUNT(*) FROM plugin_notifications WHERE plugin_id = ?",
                (plugin_id,),
            ).fetchone()[0]
        self.assertEqual(notification_count, 0)

    def test_plugin_notification_input_validation(self):
        status, invalid_limit = self.request(
            "GET",
            "/v1/notifications?userWxId=wxid_social_user&limit=0",
            headers=self.social_headers(),
        )
        self.assertEqual(status, 400)
        self.assertEqual(invalid_limit["error"]["code"], "INVALID_LIMIT")

        status, invalid_ids = self.request(
            "POST",
            "/v1/notifications/read",
            {**self.user_body(), "notificationIds": ["invalid"]},
            self.social_headers(),
        )
        self.assertEqual(status, 400)
        self.assertEqual(invalid_ids["error"]["code"], "INVALID_NOTIFICATION_ID")

    def test_rejects_missing_main_and_wrong_owner(self):
        headers = {"X-Hchat-Install-Id": "test-install-id-0002"}
        invalid = self.upload_body()
        invalid["files"] = [{"name": "README.md", "content": "only readme"}]
        status, response = self.request("POST", "/v1/plugins", invalid, headers)
        self.assertEqual(status, 400)
        self.assertEqual(response["error"]["code"], "MAIN_FILE_REQUIRED")

        status, created = self.request("POST", "/v1/plugins", self.upload_body(), headers)
        self.assertEqual(status, 201)
        update = self.upload_body()
        update["pluginId"] = created["data"]["pluginId"]
        update["ownerToken"] = "wrong-owner-token"
        status, response = self.request("POST", "/v1/plugins", update, headers)
        self.assertEqual(status, 403)
        self.assertEqual(response["error"]["code"], "OWNER_TOKEN_INVALID")

    def test_owner_can_delete_plugin(self):
        created = self.create_plugin("delete-owner-install")
        status, response = self.request(
            "DELETE",
            f"/v1/plugins/{created['pluginId']}",
            headers={"Authorization": f"Bearer {created['ownerToken']}"},
        )
        self.assertEqual(status, 200)
        self.assertTrue(response["data"]["deleted"])

    def test_wrong_delete_token_is_rejected(self):
        created = self.create_plugin("delete-wrong-install")
        status, response = self.request(
            "DELETE",
            f"/v1/plugins/{created['pluginId']}",
            headers={"Authorization": "Bearer definitely-not-an-owner-token"},
        )
        self.assertEqual(status, 403)
        self.assertEqual(response["error"]["code"], "OWNER_TOKEN_INVALID")

        status, detail = self.request("GET", f"/v1/plugins/{created['pluginId']}")
        self.assertEqual(status, 200)
        self.assertEqual(detail["data"]["pluginId"], created["pluginId"])

    def test_admin_password_cannot_be_used_as_bearer_token(self):
        created = self.create_plugin("delete-admin-install")
        self.assertNotEqual(created["ownerToken"], self.ADMIN_PASSWORD)
        status, response = self.request(
            "DELETE",
            f"/v1/plugins/{created['pluginId']}",
            headers={"Authorization": f"Bearer {self.ADMIN_PASSWORD}"},
        )
        self.assertEqual(status, 403)
        self.assertEqual(response["error"]["code"], "OWNER_TOKEN_INVALID")

        status, detail = self.request("GET", f"/v1/plugins/{created['pluginId']}")
        self.assertEqual(status, 200)
        self.assertEqual(detail["data"]["pluginId"], created["pluginId"])

    def test_admin_session_login_rejects_wrong_password_and_sets_secure_cookie(self):
        status, rejected, rejected_headers, session_cookie = self.admin_login("wrong-password")
        self.assertEqual(status, 401)
        self.assertEqual(rejected["error"]["code"], "ADMIN_PASSWORD_INVALID")
        self.assertIsNone(rejected_headers.get("Set-Cookie"))
        self.assertIsNone(session_cookie)

        status, logged_in, headers, session_cookie = self.admin_login()
        self.assertEqual(status, 200)
        self.assertTrue(logged_in["data"]["authenticated"])
        self.assertIsNotNone(logged_in["data"]["expiresAt"])
        self.assertNotIn(self.ADMIN_PASSWORD, json.dumps(logged_in, ensure_ascii=False))
        self.assertIsNotNone(session_cookie)

        set_cookie = headers.get("Set-Cookie")
        self.assertIsNotNone(set_cookie)
        self.assertIn("HttpOnly", set_cookie)
        self.assertIn("Secure", set_cookie)
        self.assertIn("SameSite=Strict", set_cookie)
        self.assertIn(f"Max-Age={APP.ADMIN_SESSION_TTL_SECONDS}", set_cookie)
        self.assertIn("Path=/", set_cookie)
        self.assertIn("__Host-hchat_admin_session=", set_cookie)

        status, session = self.request(
            "GET",
            "/v1/admin/session",
            headers={"Cookie": session_cookie},
        )
        self.assertEqual(status, 200)
        self.assertTrue(session["data"]["authenticated"])
        self.assertEqual(session["data"]["expiresAt"], logged_in["data"]["expiresAt"])

    def test_admin_login_rejects_legacy_token_field_and_limits_failures(self):
        status, legacy = self.request(
            "POST",
            "/v1/admin/session",
            {"adminToken": self.ADMIN_PASSWORD},
        )
        self.assertEqual(status, 401)
        self.assertEqual(legacy["error"]["code"], "ADMIN_PASSWORD_INVALID")

        for _ in range(APP.ADMIN_LOGIN_FAILURE_LIMIT - 1):
            status, rejected, _, _ = self.admin_login("wrong-password")
            self.assertEqual(status, 401)
            self.assertEqual(rejected["error"]["code"], "ADMIN_PASSWORD_INVALID")

        status, limited, headers, session_cookie = self.admin_login()
        self.assertEqual(status, 429)
        self.assertEqual(limited["error"]["code"], "ADMIN_LOGIN_RATE_LIMITED")
        self.assertIsNotNone(headers.get("Retry-After"))
        self.assertIsNone(session_cookie)

    def test_admin_session_can_delete_plugin_and_owner_bearer_still_works(self):
        created = self.create_plugin("delete-session-install")
        status, _, _, session_cookie = self.admin_login()
        self.assertEqual(status, 200)
        self.assertIsNotNone(session_cookie)

        status, deleted = self.request(
            "DELETE",
            f"/v1/plugins/{created['pluginId']}",
            headers={"Cookie": session_cookie},
        )
        self.assertEqual(status, 200)
        self.assertTrue(deleted["data"]["deleted"])

        owner_plugin = self.create_plugin("delete-owner-after-session-install")
        status, owner_deleted = self.request(
            "DELETE",
            f"/v1/plugins/{owner_plugin['pluginId']}",
            headers={"Authorization": f"Bearer {owner_plugin['ownerToken']}"},
        )
        self.assertEqual(status, 200)
        self.assertTrue(owner_deleted["data"]["deleted"])

    def test_admin_logout_clears_session_cookie(self):
        status, _, _, session_cookie = self.admin_login()
        self.assertEqual(status, 200)

        status, logged_out, headers = self.request_raw(
            "DELETE",
            "/v1/admin/session",
            headers={"Cookie": session_cookie},
        )
        self.assertEqual(status, 200)
        response = json.loads(logged_out.decode("utf-8"))
        self.assertFalse(response["data"]["authenticated"])
        set_cookie = headers.get("Set-Cookie")
        self.assertIn("Max-Age=0", set_cookie)
        self.assertIn("HttpOnly", set_cookie)
        self.assertIn("Secure", set_cookie)
        self.assertIn("SameSite=Strict", set_cookie)

        status, session = self.request("GET", "/v1/admin/session")
        self.assertEqual(status, 200)
        self.assertFalse(session["data"]["authenticated"])
        self.assertIsNone(session["data"]["expiresAt"])

    def test_admin_password_change_requires_session_and_valid_payload(self):
        change = {
            "currentPassword": self.ADMIN_PASSWORD,
            "newPassword": "new-admin-password",
            "confirmPassword": "new-admin-password",
        }
        status, unauthorized = self.request("POST", "/v1/admin/password", change)
        self.assertEqual(status, 401)
        self.assertEqual(unauthorized["error"]["code"], "ADMIN_SESSION_REQUIRED")

        status, _, _, session_cookie = self.admin_login()
        self.assertEqual(status, 200)
        invalid_payloads = (
            ({"currentPassword": self.ADMIN_PASSWORD}, "INVALID_ADMIN_PASSWORD_PAYLOAD"),
            (
                {**change, "newPassword": "has whitespace", "confirmPassword": "has whitespace"},
                "INVALID_ADMIN_PASSWORD_FORMAT",
            ),
            (
                {**change, "newPassword": " padded-password ", "confirmPassword": " padded-password "},
                "INVALID_ADMIN_PASSWORD_FORMAT",
            ),
            (
                {**change, "confirmPassword": "different-password"},
                "ADMIN_PASSWORD_CONFIRMATION_MISMATCH",
            ),
            (
                {
                    **change,
                    "newPassword": self.ADMIN_PASSWORD,
                    "confirmPassword": self.ADMIN_PASSWORD,
                },
                "ADMIN_PASSWORD_UNCHANGED",
            ),
        )
        for payload, expected_code in invalid_payloads:
            with self.subTest(expected_code=expected_code):
                status, rejected = self.request(
                    "POST",
                    "/v1/admin/password",
                    payload,
                    headers={"Cookie": session_cookie},
                )
                self.assertEqual(status, 400)
                self.assertEqual(rejected["error"]["code"], expected_code)

        status, session = self.request(
            "GET",
            "/v1/admin/session",
            headers={"Cookie": session_cookie},
        )
        self.assertEqual(status, 200)
        self.assertTrue(session["data"]["authenticated"])
        self.assertEqual(
            self.admin_password_file.read_text(encoding="utf-8"),
            self.ADMIN_PASSWORD + "\n",
        )

    def test_admin_password_change_rejects_wrong_current_password(self):
        status, _, _, session_cookie = self.admin_login()
        self.assertEqual(status, 200)
        status, rejected = self.request(
            "POST",
            "/v1/admin/password",
            {
                "currentPassword": "wrong-password",
                "newPassword": "new-admin-password",
                "confirmPassword": "new-admin-password",
            },
            headers={"Cookie": session_cookie},
        )
        self.assertEqual(status, 400)
        self.assertEqual(rejected["error"]["code"], "ADMIN_CURRENT_PASSWORD_INVALID")
        self.assertEqual(
            self.admin_password_file.read_text(encoding="utf-8"),
            self.ADMIN_PASSWORD + "\n",
        )
        status, session = self.request(
            "GET",
            "/v1/admin/session",
            headers={"Cookie": session_cookie},
        )
        self.assertEqual(status, 200)
        self.assertTrue(session["data"]["authenticated"])

    def test_admin_password_change_persists_and_invalidates_all_sessions(self):
        status, _, _, first_cookie = self.admin_login()
        self.assertEqual(status, 200)
        status, _, _, second_cookie = self.admin_login()
        self.assertEqual(status, 200)

        new_password = "new-admin-password"
        status, response_body, headers = self.request_raw(
            "POST",
            "/v1/admin/password",
            {
                "currentPassword": self.ADMIN_PASSWORD,
                "newPassword": new_password,
                "confirmPassword": new_password,
            },
            headers={"Cookie": first_cookie},
        )
        self.assertEqual(status, 200)
        changed = json.loads(response_body.decode("utf-8"))
        self.assertFalse(changed["data"]["authenticated"])
        self.assertIsNone(changed["data"]["expiresAt"])
        self.assertIn("Max-Age=0", headers.get("Set-Cookie"))
        self.assertIn("HttpOnly", headers.get("Set-Cookie"))
        self.assertEqual(self.admin_password_file.read_text(encoding="utf-8"), new_password + "\n")
        self.assertEqual(self.server.admin_password_hash, APP.token_hash(new_password))

        for session_cookie in (first_cookie, second_cookie):
            status, session = self.request(
                "GET",
                "/v1/admin/session",
                headers={"Cookie": session_cookie},
            )
            self.assertEqual(status, 200)
            self.assertFalse(session["data"]["authenticated"])

        status, rejected, _, _ = self.admin_login()
        self.assertEqual(status, 401)
        self.assertEqual(rejected["error"]["code"], "ADMIN_PASSWORD_INVALID")
        status, logged_in, _, new_cookie = self.admin_login(new_password)
        self.assertEqual(status, 200)
        self.assertTrue(logged_in["data"]["authenticated"])
        self.assertIsNotNone(new_cookie)

    def test_admin_password_generation_prevents_old_session_revival(self):
        status, _, _, original_cookie = self.admin_login()
        self.assertEqual(status, 200)
        second_password = "second-admin-password"
        status, _ = self.request(
            "POST",
            "/v1/admin/password",
            {
                "currentPassword": self.ADMIN_PASSWORD,
                "newPassword": second_password,
                "confirmPassword": second_password,
            },
            headers={"Cookie": original_cookie},
        )
        self.assertEqual(status, 200)
        status, _, _, second_cookie = self.admin_login(second_password)
        self.assertEqual(status, 200)
        status, _ = self.request(
            "POST",
            "/v1/admin/password",
            {
                "currentPassword": second_password,
                "newPassword": self.ADMIN_PASSWORD,
                "confirmPassword": self.ADMIN_PASSWORD,
            },
            headers={"Cookie": second_cookie},
        )
        self.assertEqual(status, 200)

        status, session = self.request(
            "GET",
            "/v1/admin/session",
            headers={"Cookie": original_cookie},
        )
        self.assertEqual(status, 200)
        self.assertFalse(session["data"]["authenticated"])

    def test_admin_password_write_failure_keeps_current_credentials(self):
        status, _, _, session_cookie = self.admin_login()
        self.assertEqual(status, 200)
        with mock.patch.object(APP, "replace_admin_password", side_effect=OSError("read-only")):
            status, rejected = self.request(
                "POST",
                "/v1/admin/password",
                {
                    "currentPassword": self.ADMIN_PASSWORD,
                    "newPassword": "new-admin-password",
                    "confirmPassword": "new-admin-password",
                },
                headers={"Cookie": session_cookie},
            )
        self.assertEqual(status, 500)
        self.assertEqual(rejected["error"]["code"], "ADMIN_PASSWORD_UPDATE_FAILED")
        self.assertEqual(self.server.admin_password_hash, APP.token_hash(self.ADMIN_PASSWORD))
        self.assertEqual(
            self.admin_password_file.read_text(encoding="utf-8"),
            self.ADMIN_PASSWORD + "\n",
        )
        status, session = self.request(
            "GET",
            "/v1/admin/session",
            headers={"Cookie": session_cookie},
        )
        self.assertEqual(status, 200)
        self.assertTrue(session["data"]["authenticated"])

    def test_forged_expired_and_rotated_admin_sessions_are_rejected(self):
        created = self.create_plugin("delete-invalid-session-install")
        status, _, _, session_cookie = self.admin_login()
        self.assertEqual(status, 200)
        cookie_name, cookie_value = session_cookie.split("=", 1)
        encoded_payload, encoded_signature = cookie_value.split(".", 1)
        replacement = "A" if encoded_signature[0] != "A" else "B"
        forged_cookie = (
            f"{cookie_name}={encoded_payload}.{replacement}{encoded_signature[1:]}"
        )

        status, forged_session = self.request(
            "GET",
            "/v1/admin/session",
            headers={"Cookie": forged_cookie},
        )
        self.assertEqual(status, 200)
        self.assertFalse(forged_session["data"]["authenticated"])
        status, rejected_delete = self.request(
            "DELETE",
            f"/v1/plugins/{created['pluginId']}",
            headers={"Cookie": forged_cookie},
        )
        self.assertEqual(status, 401)
        self.assertEqual(rejected_delete["error"]["code"], "OWNER_TOKEN_REQUIRED")

        expired_value, _ = APP.create_admin_session(
            self.server.admin_password_hash,
            now=int(time.time()) - 10,
            ttl_seconds=1,
        )
        status, expired_session = self.request(
            "GET",
            "/v1/admin/session",
            headers={"Cookie": f"{APP.ADMIN_SESSION_COOKIE}={expired_value}"},
        )
        self.assertEqual(status, 200)
        self.assertFalse(expired_session["data"]["authenticated"])

        self.server.admin_password_hash = APP.token_hash("rotated-admin-password")
        status, rotated_session = self.request(
            "GET",
            "/v1/admin/session",
            headers={"Cookie": session_cookie},
        )
        self.assertEqual(status, 200)
        self.assertFalse(rotated_session["data"]["authenticated"])

        status, detail = self.request("GET", f"/v1/plugins/{created['pluginId']}")
        self.assertEqual(status, 200)
        self.assertEqual(detail["data"]["pluginId"], created["pluginId"])

    def test_admin_page_has_security_headers(self):
        for path in ("/admin", "/admin/"):
            status, body, headers = self.request_raw("GET", path)
            self.assertEqual(status, 200)
            self.assertIn("Hchat 插件管理", body.decode("utf-8"))
            self.assertIn("上传者黑名单", body.decode("utf-8"))
            self.assertIn("批量删除", body.decode("utf-8"))
            self.assertIn('id="accountMenu"', body.decode("utf-8"))
            self.assertIn('id="passwordToggleButton"', body.decode("utf-8"))
            self.assertIn('id="passwordEditor"', body.decode("utf-8"))
            self.assertNotIn("password-panel", body.decode("utf-8"))
            self.assertNotIn(b"__HCHAT_CSP_NONCE__", body)
            self.assertNotIn(b"innerHTML", body)
            self.assertEqual(headers.get("Cache-Control"), "no-store")
            self.assertEqual(headers.get("X-Frame-Options"), "DENY")
            self.assertEqual(headers.get("X-Content-Type-Options"), "nosniff")
            self.assertEqual(headers.get("X-Robots-Tag"), "noindex, nofollow")
            self.assertIn("default-src 'none'", headers.get("Content-Security-Policy"))
            self.assertIn("frame-ancestors 'none'", headers.get("Content-Security-Policy"))
            self.assertIsNone(headers.get("Access-Control-Allow-Origin"))

    def test_admin_delete_is_disabled_without_password_hash(self):
        created = self.create_plugin("delete-disabled-install")
        self.server.admin_password_hash = None
        status, response = self.request(
            "DELETE",
            f"/v1/plugins/{created['pluginId']}",
            headers={"Authorization": f"Bearer {self.ADMIN_PASSWORD}"},
        )
        self.assertEqual(status, 403)
        self.assertEqual(response["error"]["code"], "OWNER_TOKEN_INVALID")

    def test_snapshot_history_download_and_plugin_ownership(self):
        created = self.create_plugin("snapshot-owner-install")
        plugin_id = created["pluginId"]
        first_version_id = created["versionId"]
        first_main = self.upload_body()["files"][0]["content"]

        update = self.upload_body()
        update["pluginId"] = plugin_id
        update["ownerToken"] = created["ownerToken"]
        update["versionName"] = "2.0.0"
        update["releaseNotes"] = "第二个历史版本"
        update["files"][0]["content"] = "void onLoad() { log(\"snapshot two\"); }"
        status, updated = self.request(
            "POST",
            "/v1/plugins",
            update,
            {"X-Hchat-Install-Id": "snapshot-owner-install"},
        )
        self.assertEqual(status, 200)
        second_version_id = updated["data"]["versionId"]
        self.assertNotEqual(first_version_id, second_version_id)

        update["releaseNotes"] = "修订后的更新说明"
        status, duplicate = self.request(
            "POST",
            "/v1/plugins",
            update,
            {"X-Hchat-Install-Id": "snapshot-owner-install"},
        )
        self.assertEqual(status, 200)
        self.assertTrue(duplicate["data"]["unchanged"])
        self.assertEqual(duplicate["data"]["versionId"], second_version_id)

        update.pop("releaseNotes")
        status, duplicate_without_notes = self.request(
            "POST",
            "/v1/plugins",
            update,
            {"X-Hchat-Install-Id": "snapshot-owner-install"},
        )
        self.assertEqual(status, 200)
        self.assertTrue(duplicate_without_notes["data"]["unchanged"])

        status, snapshots = self.request("GET", f"/v1/plugins/{plugin_id}/snapshots")
        self.assertEqual(status, 200)
        self.assertEqual(snapshots["data"]["count"], 2)
        self.assertEqual(
            [item["versionId"] for item in snapshots["data"]["items"]],
            [second_version_id, first_version_id],
        )
        self.assertEqual(
            snapshots["data"]["items"][0]["releaseNotes"],
            "修订后的更新说明",
        )
        self.assertEqual(snapshots["data"]["items"][1]["releaseNotes"], "")

        status, old_snapshot = self.request(
            "GET",
            f"/v1/plugins/{plugin_id}/snapshots/{first_version_id}",
        )
        self.assertEqual(status, 200)
        self.assertEqual(old_snapshot["data"]["snapshot"]["versionId"], first_version_id)
        self.assertEqual(old_snapshot["data"]["snapshot"]["releaseNotes"], "")
        self.assertEqual(old_snapshot["data"]["downloadCount"], 0)
        status, reopened_snapshot = self.request(
            "GET",
            f"/v1/plugins/{plugin_id}/snapshots/{first_version_id}",
        )
        self.assertEqual(status, 200)
        self.assertEqual(reopened_snapshot["data"]["downloadCount"], 0)
        main_file = old_snapshot["data"]["files"][0]
        self.assertEqual(main_file["name"], "main.java")
        self.assertEqual(main_file["content"], first_main)
        self.assertEqual(main_file["sha256"], APP.sha256_hex(first_main.encode("utf-8")))

        reused = self.upload_body()
        reused["pluginId"] = plugin_id
        reused["ownerToken"] = created["ownerToken"]
        reused["releaseNotes"] = "旧版本重新发布"
        status, reused_response = self.request(
            "POST",
            "/v1/plugins",
            reused,
            {"X-Hchat-Install-Id": "snapshot-owner-install"},
        )
        self.assertEqual(status, 200)
        self.assertTrue(reused_response["data"]["unchanged"])
        self.assertEqual(reused_response["data"]["versionId"], first_version_id)

        status, reordered = self.request("GET", f"/v1/plugins/{plugin_id}/snapshots")
        self.assertEqual(status, 200)
        self.assertEqual(reordered["data"]["count"], 2)
        self.assertEqual(reordered["data"]["items"][0]["versionId"], first_version_id)
        self.assertEqual(reordered["data"]["items"][0]["releaseNotes"], "旧版本重新发布")

        other = self.create_plugin("snapshot-other-install")
        status, response = self.request(
            "GET",
            f"/v1/plugins/{plugin_id}/snapshots/{other['versionId']}",
        )
        self.assertEqual(status, 404)
        self.assertEqual(response["error"]["code"], "SNAPSHOT_NOT_FOUND")

    def test_binary_bshs_history_round_trip(self):
        bshs_bytes = b"BSHS\x00\xff\xfe\x80\x01\x02encrypted-bshs\x00\x9f"
        bshs_base64 = base64.b64encode(bshs_bytes).decode("ascii")
        bshs_sha256 = APP.sha256_hex(bshs_bytes)
        body = self.upload_body()
        body["files"].append(
            {
                "name": "main.java.bshs",
                "encoding": "base64",
                "content": bshs_base64,
                "size": len(bshs_bytes),
                "sha256": bshs_sha256,
            }
        )
        status, created = self.request(
            "POST",
            "/v1/plugins",
            body,
            {"X-Hchat-Install-Id": "binary-bshs-install"},
        )
        self.assertEqual(status, 201)

        plugin_id = created["data"]["pluginId"]
        version_id = created["data"]["versionId"]
        status, history = self.request(
            "GET",
            f"/v1/plugins/{plugin_id}/snapshots/{version_id}",
        )
        self.assertEqual(status, 200)
        bshs_file = next(
            item for item in history["data"]["files"] if item["name"] == "main.java.bshs"
        )
        self.assertEqual(bshs_file["encoding"], "base64")
        self.assertEqual(bshs_file["content"], bshs_base64)
        self.assertEqual(base64.b64decode(bshs_file["content"], validate=True), bshs_bytes)
        self.assertEqual(bshs_file["size"], len(bshs_bytes))
        self.assertEqual(bshs_file["sha256"], bshs_sha256)

        with self.server.database.session() as connection:
            stored = connection.execute(
                """
                SELECT typeof(bshs_content) AS storage_type, bshs_content
                FROM plugin_versions
                WHERE id = ?
                """,
                (version_id,),
            ).fetchone()
        self.assertEqual(stored["storage_type"], "blob")
        self.assertEqual(bytes(stored["bshs_content"]), bshs_bytes)

    def test_binary_bshs_rejects_invalid_base64_and_integrity_mismatch(self):
        invalid = self.upload_body()
        invalid["files"].append(
            {
                "name": "main.java.bshs",
                "encoding": "base64",
                "content": "AA==\n",
            }
        )
        status, response = self.request(
            "POST",
            "/v1/plugins",
            invalid,
            {"X-Hchat-Install-Id": "binary-invalid-install"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(response["error"]["code"], "INVALID_FILE_CONTENT")

        wrong_header = self.upload_body()
        wrong_header["files"].append(
            {
                "name": "main.java.bshs",
                "encoding": "base64",
                "content": base64.b64encode(b"NOT-A-BSHS-FILE").decode("ascii"),
            }
        )
        status, response = self.request(
            "POST",
            "/v1/plugins",
            wrong_header,
            {"X-Hchat-Install-Id": "binary-header-install"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(response["error"]["code"], "INVALID_FILE_CONTENT")

        raw = b"BSHS\x00\xffbinary"
        mismatched = self.upload_body()
        mismatched["files"].append(
            {
                "name": "main.java.bshs",
                "encoding": "base64",
                "content": base64.b64encode(raw).decode("ascii"),
                "size": len(raw) + 1,
            }
        )
        status, response = self.request(
            "POST",
            "/v1/plugins",
            mismatched,
            {"X-Hchat-Install-Id": "binary-size-install"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(response["error"]["code"], "FILE_SIZE_MISMATCH")

        mismatched["files"][-1]["size"] = len(raw)
        mismatched["files"][-1]["sha256"] = "0" * 64
        status, response = self.request(
            "POST",
            "/v1/plugins",
            mismatched,
            {"X-Hchat-Install-Id": "binary-sha-install"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(response["error"]["code"], "FILE_SHA256_MISMATCH")

    def test_extra_files_upload_detail_and_history_round_trip(self):
        binary = b"\x00\x01dependency\xff"
        body = self.upload_body()
        body["files"].extend(
            [
                {
                    "name": "config.json",
                    "encoding": "utf8",
                    "content": '{"enabled":true}',
                    "size": 16,
                    "sha256": APP.sha256_hex(b'{"enabled":true}'),
                },
                {
                    "name": "helper.dex",
                    "encoding": "base64",
                    "content": base64.b64encode(binary).decode("ascii"),
                    "size": len(binary),
                    "sha256": APP.sha256_hex(binary),
                },
            ]
        )
        status, created = self.request(
            "POST",
            "/v1/plugins",
            body,
            {"X-Hchat-Install-Id": "extra-files-install"},
        )
        self.assertEqual(status, 201)
        plugin_id = created["data"]["pluginId"]
        first_version_id = created["data"]["versionId"]

        status, detail = self.request("GET", f"/v1/plugins/{plugin_id}")
        self.assertEqual(status, 200)
        self.assertEqual(
            [item["name"] for item in detail["data"]["files"]],
            ["main.java", "info.prop", "README.md", "config.json", "helper.dex"],
        )
        config_file = next(
            item for item in detail["data"]["files"] if item["name"] == "config.json"
        )
        self.assertEqual(config_file["content"], '{"enabled":true}')
        self.assertEqual(config_file["size"], 16)
        helper_file = next(
            item for item in detail["data"]["files"] if item["name"] == "helper.dex"
        )
        self.assertEqual(helper_file["encoding"], "base64")
        self.assertEqual(base64.b64decode(helper_file["content"], validate=True), binary)

        update = self.upload_body()
        update.update(
            {
                "pluginId": plugin_id,
                "ownerToken": created["data"]["ownerToken"],
                "versionName": "2.0.0",
            }
        )
        update["files"].append({"name": "new.dat", "encoding": "base64", "content": "AAEC"})
        status, updated = self.request(
            "POST",
            "/v1/plugins",
            update,
            {"X-Hchat-Install-Id": "extra-files-install"},
        )
        self.assertEqual(status, 200)
        second_version_id = updated["data"]["versionId"]
        self.assertNotEqual(first_version_id, second_version_id)

        status, history = self.request(
            "GET", f"/v1/plugins/{plugin_id}/snapshots/{first_version_id}"
        )
        self.assertEqual(status, 200)
        history_names = [item["name"] for item in history["data"]["files"]]
        self.assertIn("config.json", history_names)
        self.assertIn("helper.dex", history_names)
        self.assertNotIn("new.dat", history_names)

        with self.server.database.session() as connection:
            stored = connection.execute(
                "SELECT encoding, typeof(content), size FROM plugin_version_files WHERE version_id = ? ORDER BY name",
                (first_version_id,),
            ).fetchall()
        self.assertEqual(
            [(row["encoding"], row["typeof(content)"], row["size"]) for row in stored],
            [("utf-8", "blob", 16), ("base64", "blob", len(binary))],
        )

    def test_extra_files_reject_paths_duplicates_encoding_and_integrity(self):
        invalid_names = ("../escape", "nested/file", "nested\\file", ".", "..", "bad\x00name")
        for index, name in enumerate(invalid_names):
            body = self.upload_body()
            body["files"].append({"name": name, "content": "x"})
            status, response = self.request(
                "POST",
                "/v1/plugins",
                body,
                {"X-Hchat-Install-Id": f"invalid-name-{index}"},
            )
            self.assertEqual(status, 400, name)
            self.assertEqual(response["error"]["code"], "INVALID_FILE_NAME", name)

        duplicate = self.upload_body()
        duplicate["files"].append({"name": "readme.md", "content": "again"})
        status, response = self.request(
            "POST",
            "/v1/plugins",
            duplicate,
            {"X-Hchat-Install-Id": "duplicate-default"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(response["error"]["code"], "DUPLICATE_FILE")

        for index, name in enumerate(("readme.MD", "MAIN.JAVA")):
            case_duplicate = self.upload_body()
            case_duplicate["files"].append({"name": name, "content": "again"})
            status, response = self.request(
                "POST",
                "/v1/plugins",
                case_duplicate,
                {"X-Hchat-Install-Id": f"duplicate-case-{index}"},
            )
            self.assertEqual(status, 400, name)
            self.assertEqual(response["error"]["code"], "DUPLICATE_FILE", name)

        extra_duplicate = self.upload_body()
        extra_duplicate["files"].extend(
            [
                {"name": "Helper.dat", "content": "first"},
                {"name": "helper.DAT", "content": "second"},
            ]
        )
        status, response = self.request(
            "POST",
            "/v1/plugins",
            extra_duplicate,
            {"X-Hchat-Install-Id": "duplicate-extra-case"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(response["error"]["code"], "DUPLICATE_FILE")

        invalid_encoding = self.upload_body()
        invalid_encoding["files"].append(
            {"name": "helper.dat", "encoding": "gzip", "content": "x"}
        )
        status, response = self.request(
            "POST",
            "/v1/plugins",
            invalid_encoding,
            {"X-Hchat-Install-Id": "invalid-extra-encoding"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(response["error"]["code"], "INVALID_FILE_ENCODING")

        invalid_base64 = self.upload_body()
        invalid_base64["files"].append(
            {"name": "helper.dat", "encoding": "base64", "content": "not-base64"}
        )
        status, response = self.request(
            "POST",
            "/v1/plugins",
            invalid_base64,
            {"X-Hchat-Install-Id": "invalid-extra-base64"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(response["error"]["code"], "INVALID_FILE_CONTENT")

        mismatch = self.upload_body()
        mismatch["files"].append(
            {"name": "helper.dat", "content": "content", "size": 99, "sha256": "0" * 64}
        )
        status, response = self.request(
            "POST",
            "/v1/plugins",
            mismatch,
            {"X-Hchat-Install-Id": "invalid-extra-size"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(response["error"]["code"], "FILE_SIZE_MISMATCH")
        mismatch["files"][-1]["size"] = len("content".encode("utf-8"))
        status, response = self.request(
            "POST",
            "/v1/plugins",
            mismatch,
            {"X-Hchat-Install-Id": "invalid-extra-sha"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(response["error"]["code"], "FILE_SHA256_MISMATCH")

    def test_extra_files_enforce_count_single_and_total_limits(self):
        too_many = self.upload_body()
        too_many["files"].extend(
            {"name": f"dependency-{index}.dat", "content": "x"}
            for index in range(APP.MAX_FILE_COUNT)
        )
        status, response = self.request(
            "POST",
            "/v1/plugins",
            too_many,
            {"X-Hchat-Install-Id": "extra-count-limit"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(response["error"]["code"], "INVALID_FILES")

        too_large = self.upload_body()
        too_large["files"].append(
            {"name": "large.dat", "content": "x" * (APP.EXTRA_FILE_LIMIT + 1)}
        )
        status, response = self.request(
            "POST",
            "/v1/plugins",
            too_large,
            {"X-Hchat-Install-Id": "extra-single-limit"},
        )
        self.assertEqual(status, 413)
        self.assertEqual(response["error"]["code"], "FILE_TOO_LARGE")

        total_too_large = self.upload_body()
        total_too_large["files"].extend(
            {"name": f"total-{index}.dat", "content": "x" * APP.EXTRA_FILE_LIMIT}
            for index in range(APP.TOTAL_FILE_LIMIT // APP.EXTRA_FILE_LIMIT + 1)
        )
        with self.assertRaises(APP.ApiError) as raised:
            APP.validate_upload_payload(total_too_large)
        self.assertEqual(raised.exception.code, "PLUGIN_TOO_LARGE")

    def test_admin_review_apis_require_session_and_validate_setting(self):
        plugin_id = "p_" + "1" * 32
        version_id = "v_" + "2" * 32
        protected_requests = (
            ("GET", "/v1/admin/settings", None),
            ("POST", "/v1/admin/settings", {"uploadReviewEnabled": True}),
            ("GET", "/v1/admin/plugins", None),
            ("POST", "/v1/admin/plugins/batch-delete", {"pluginIds": [plugin_id]}),
            ("GET", "/v1/admin/blacklist", None),
            (
                "POST",
                "/v1/admin/blacklist",
                {
                    "uploaderWxId": "wxid_protected",
                    "uploaderWeChatId": "",
                    "uploaderNickname": "",
                },
            ),
            ("DELETE", "/v1/admin/blacklist/wxid_protected", None),
            ("GET", f"/v1/admin/plugins/{plugin_id}/versions/{version_id}", None),
            (
                "POST",
                f"/v1/admin/plugins/{plugin_id}/versions/{version_id}/approve",
                None,
            ),
            ("DELETE", f"/v1/admin/plugins/{plugin_id}/versions/{version_id}", None),
        )
        for method, path, body in protected_requests:
            status, response = self.request(method, path, body)
            self.assertEqual(status, 401, path)
            self.assertEqual(response["error"]["code"], "ADMIN_SESSION_REQUIRED")

        headers = self.admin_headers()
        status, settings = self.request("GET", "/v1/admin/settings", headers=headers)
        self.assertEqual(status, 200)
        self.assertFalse(settings["data"]["uploadReviewEnabled"])

        status, invalid = self.request(
            "POST",
            "/v1/admin/settings",
            {"uploadReviewEnabled": 1},
            headers,
        )
        self.assertEqual(status, 400)
        self.assertEqual(invalid["error"]["code"], "INVALID_UPLOAD_REVIEW_SETTING")

    def test_upload_identity_is_required_persisted_and_searchable_by_admin(self):
        missing_identity = self.upload_body()
        missing_identity.pop("uploaderWxId")
        status, rejected = self.request(
            "POST",
            "/v1/plugins",
            missing_identity,
            {"X-Hchat-Install-Id": "missing-uploader-identity"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(rejected["error"]["code"], "INVALID_FIELD")

        invalid_identity = self.upload_body()
        invalid_identity["uploaderWxId"] = "wxid invalid"
        status, rejected = self.request(
            "POST",
            "/v1/plugins",
            invalid_identity,
            {"X-Hchat-Install-Id": "invalid-uploader-identity"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(rejected["error"]["code"], "INVALID_UPLOADER_WXID")

        body = self.upload_body()
        body["uploaderWxId"] = "wxid_identity_owner"
        body["uploaderWeChatId"] = "identity_wechat"
        body["uploaderNickname"] = "身份测试昵称"
        status, created = self.request(
            "POST",
            "/v1/plugins",
            body,
            {"X-Hchat-Install-Id": "persist-uploader-identity"},
        )
        self.assertEqual(status, 201)

        update = self.upload_body()
        update["pluginId"] = created["data"]["pluginId"]
        update["ownerToken"] = created["data"]["ownerToken"]
        update["versionName"] = "2.0.0"
        update["uploaderWxId"] = "wxid_second_identity"
        update["uploaderWeChatId"] = "second_wechat"
        update["uploaderNickname"] = "第二位上传者"
        update["files"][0]["content"] = "void onLoad() { log(\"second identity\"); }"
        status, updated = self.request(
            "POST",
            "/v1/plugins",
            update,
            {"X-Hchat-Install-Id": "persist-uploader-identity"},
        )
        self.assertEqual(status, 200)

        status, public_list = self.request("GET", "/v1/plugins?limit=100")
        self.assertEqual(status, 200)
        public_plugin = next(
            item
            for item in public_list["data"]["items"]
            if item["pluginId"] == created["data"]["pluginId"]
        )
        status, public_detail = self.request(
            "GET",
            f"/v1/plugins/{created['data']['pluginId']}",
        )
        self.assertEqual(status, 200)
        for public_payload in (public_plugin, public_detail["data"]):
            self.assertNotIn("uploaderWxId", public_payload)
            self.assertNotIn("uploaderWeChatId", public_payload)
            self.assertNotIn("uploaderNickname", public_payload)

        headers = self.admin_headers()
        status, admin_list = self.request(
            "GET",
            "/v1/admin/plugins?q=第二位上传者&limit=100",
            headers=headers,
        )
        self.assertEqual(status, 200)
        self.assertEqual(admin_list["data"]["count"], 1)
        plugin = admin_list["data"]["items"][0]
        self.assertEqual(plugin["uploaderWxId"], "wxid_second_identity")
        self.assertEqual(plugin["uploaderWeChatId"], "second_wechat")
        self.assertEqual(plugin["uploaderNickname"], "第二位上传者")
        versions = {version["versionId"]: version for version in plugin["versions"]}
        first_version = versions[created["data"]["versionId"]]
        self.assertEqual(first_version["uploaderWxId"], "wxid_identity_owner")
        self.assertEqual(first_version["uploaderWeChatId"], "identity_wechat")
        self.assertEqual(first_version["uploaderNickname"], "身份测试昵称")
        second_version = versions[updated["data"]["versionId"]]
        self.assertEqual(second_version["uploaderWxId"], "wxid_second_identity")
        self.assertEqual(second_version["uploaderWeChatId"], "second_wechat")
        self.assertEqual(second_version["uploaderNickname"], "第二位上传者")

        status, detail = self.request(
            "GET",
            f"/v1/admin/plugins/{plugin['pluginId']}/versions/{created['data']['versionId']}",
            headers=headers,
        )
        self.assertEqual(status, 200)
        self.assertEqual(detail["data"]["version"]["uploaderWxId"], "wxid_identity_owner")

    def test_admin_blacklist_blocks_create_and_update_until_removed(self):
        existing = self.create_plugin("blacklist-existing-owner")
        headers = self.admin_headers()
        identity = {
            "uploaderWxId": "wxid_test_uploader",
            "uploaderWeChatId": "test_wechat_id",
            "uploaderNickname": "测试上传者",
        }
        status, added = self.request(
            "POST",
            "/v1/admin/blacklist",
            identity,
            headers,
        )
        self.assertEqual(status, 201)
        self.assertTrue(added["data"]["created"])
        blacklisted_at = added["data"]["blacklistedAt"]

        changed_snapshot = dict(identity)
        changed_snapshot["uploaderNickname"] = "更新后的昵称"
        status, repeated = self.request(
            "POST",
            "/v1/admin/blacklist",
            changed_snapshot,
            headers,
        )
        self.assertEqual(status, 200)
        self.assertFalse(repeated["data"]["created"])
        self.assertEqual(repeated["data"]["blacklistedAt"], blacklisted_at)

        status, blacklist = self.request("GET", "/v1/admin/blacklist", headers=headers)
        self.assertEqual(status, 200)
        self.assertEqual(blacklist["data"]["count"], 1)
        self.assertEqual(blacklist["data"]["items"][0]["uploaderNickname"], "更新后的昵称")

        create_body = self.upload_body()
        create_body["sourcePluginId"] = "blocked_new_plugin"
        status, blocked_create = self.request(
            "POST",
            "/v1/plugins",
            create_body,
            {"X-Hchat-Install-Id": "blacklist-blocked-create"},
        )
        self.assertEqual(status, 403)
        self.assertEqual(blocked_create["error"]["code"], "UPLOADER_BLACKLISTED")

        update = self.upload_body()
        update["pluginId"] = existing["pluginId"]
        update["ownerToken"] = existing["ownerToken"]
        update["versionName"] = "2.0.0"
        update["files"][0]["content"] = "void onLoad() { log(\"blocked\"); }"
        status, blocked_update = self.request(
            "POST",
            "/v1/plugins",
            update,
            {"X-Hchat-Install-Id": "blacklist-existing-owner"},
        )
        self.assertEqual(status, 403)
        self.assertEqual(blocked_update["error"]["code"], "UPLOADER_BLACKLISTED")

        status, removed = self.request(
            "DELETE",
            "/v1/admin/blacklist/wxid_test_uploader",
            headers=headers,
        )
        self.assertEqual(status, 200)
        self.assertTrue(removed["data"]["deleted"])
        status, updated = self.request(
            "POST",
            "/v1/plugins",
            update,
            {"X-Hchat-Install-Id": "blacklist-existing-owner"},
        )
        self.assertEqual(status, 200)
        self.assertEqual(updated["data"]["reviewStatus"], "approved")

        status, missing = self.request(
            "DELETE",
            "/v1/admin/blacklist/wxid_test_uploader",
            headers=headers,
        )
        self.assertEqual(status, 404)
        self.assertEqual(missing["error"]["code"], "BLACKLIST_ENTRY_NOT_FOUND")

    def test_admin_batch_delete_returns_per_plugin_results(self):
        first = self.create_plugin("batch-delete-first")
        second = self.create_plugin("batch-delete-second")
        missing_id = "p_" + "f" * 32
        headers = self.admin_headers()
        status, result = self.request(
            "POST",
            "/v1/admin/plugins/batch-delete",
            {"pluginIds": [first["pluginId"], missing_id, second["pluginId"]]},
            headers,
        )
        self.assertEqual(status, 200)
        self.assertEqual(result["data"]["requestedCount"], 3)
        self.assertEqual(result["data"]["deletedCount"], 2)
        self.assertEqual(result["data"]["failedCount"], 1)
        self.assertEqual(
            result["data"]["items"],
            [
                {"pluginId": first["pluginId"], "deleted": True},
                {
                    "pluginId": missing_id,
                    "deleted": False,
                    "error": {"code": "PLUGIN_NOT_FOUND", "message": "未找到插件"},
                },
                {"pluginId": second["pluginId"], "deleted": True},
            ],
        )
        for plugin_id in (first["pluginId"], second["pluginId"]):
            status, missing = self.request("GET", f"/v1/plugins/{plugin_id}")
            self.assertEqual(status, 404)
            self.assertEqual(missing["error"]["code"], "PLUGIN_NOT_FOUND")

        status, duplicate = self.request(
            "POST",
            "/v1/admin/plugins/batch-delete",
            {"pluginIds": [missing_id, missing_id]},
            headers,
        )
        self.assertEqual(status, 400)
        self.assertEqual(duplicate["error"]["code"], "DUPLICATE_PLUGIN_ID")

    def test_pending_plugin_is_private_until_approved_and_admin_can_preview_files(self):
        headers = self.admin_headers()
        status, _ = self.request(
            "POST",
            "/v1/admin/settings",
            {"uploadReviewEnabled": True},
            headers,
        )
        self.assertEqual(status, 200)

        bshs_bytes = b"BSHS\x00\xffpending-encrypted-content"
        body = self.upload_body()
        body["displayName"] = "待审核插件"
        body["author"] = "待审核作者"
        body["files"].append(
            {
                "name": "main.java.bshs",
                "encoding": "base64",
                "content": base64.b64encode(bshs_bytes).decode("ascii"),
            }
        )
        status, created = self.request(
            "POST",
            "/v1/plugins",
            body,
            {"X-Hchat-Install-Id": "pending-review-install"},
        )
        self.assertEqual(status, 201)
        self.assertEqual(created["data"]["reviewStatus"], "pending")
        plugin_id = created["data"]["pluginId"]
        version_id = created["data"]["versionId"]

        status, public_list = self.request("GET", "/v1/plugins")
        self.assertEqual(status, 200)
        self.assertEqual(public_list["data"]["count"], 0)
        for path in (
            f"/v1/plugins/{plugin_id}",
            f"/v1/plugins/{plugin_id}/snapshots",
            f"/v1/plugins/{plugin_id}/snapshots/{version_id}",
        ):
            status, _ = self.request("GET", path)
            self.assertEqual(status, 404, path)

        status, admin_list = self.request(
            "GET",
            "/v1/admin/plugins?q=待审核作者&limit=100",
            headers=headers,
        )
        self.assertEqual(status, 200)
        self.assertEqual(admin_list["data"]["count"], 1)
        admin_version = admin_list["data"]["items"][0]["versions"][0]
        self.assertEqual(admin_version["reviewStatus"], "pending")
        self.assertFalse(admin_version["isPublished"])
        self.assertEqual(admin_version["submittedDisplayName"], "待审核插件")

        status, preview = self.request(
            "GET",
            f"/v1/admin/plugins/{plugin_id}/versions/{version_id}",
            headers=headers,
        )
        self.assertEqual(status, 200)
        self.assertEqual(preview["data"]["version"]["reviewStatus"], "pending")
        main_file = next(
            item for item in preview["data"]["files"] if item["name"] == "main.java"
        )
        self.assertEqual(main_file["content"], body["files"][0]["content"])
        bshs_file = next(
            item
            for item in preview["data"]["files"]
            if item["name"] == "main.java.bshs"
        )
        self.assertEqual(bshs_file["encoding"], "base64")
        self.assertEqual(bshs_file["size"], len(bshs_bytes))
        self.assertNotIn("content", bshs_file)

        other = self.create_plugin("pending-review-other")
        status, cross_plugin = self.request(
            "GET",
            f"/v1/admin/plugins/{plugin_id}/versions/{other['versionId']}",
            headers=headers,
        )
        self.assertEqual(status, 404)
        self.assertEqual(cross_plugin["error"]["code"], "VERSION_NOT_FOUND")

        status, approved = self.request(
            "POST",
            f"/v1/admin/plugins/{plugin_id}/versions/{version_id}/approve",
            headers=headers,
        )
        self.assertEqual(status, 200)
        self.assertEqual(approved["data"]["reviewStatus"], "approved")
        self.assertTrue(approved["data"]["isPublished"])

        status, detail = self.request("GET", f"/v1/plugins/{plugin_id}")
        self.assertEqual(status, 200)
        self.assertEqual(detail["data"]["displayName"], "待审核插件")
        self.assertEqual(detail["data"]["latestVersion"]["reviewStatus"], "approved")

    def test_pending_update_keeps_published_version_until_approved(self):
        created = self.create_plugin("review-existing-install")
        plugin_id = created["pluginId"]
        original_version_id = created["versionId"]
        headers = self.admin_headers()
        self.request(
            "POST",
            "/v1/admin/settings",
            {"uploadReviewEnabled": True},
            headers,
        )

        update = self.upload_body()
        update["pluginId"] = plugin_id
        update["ownerToken"] = created["ownerToken"]
        update["displayName"] = "审核后的新名称"
        update["author"] = "新作者"
        update["versionName"] = "2.0.0"
        update["uploaderWxId"] = "wxid_pending_uploader"
        update["uploaderWeChatId"] = "pending_wechat_id"
        update["uploaderNickname"] = "待审核上传者"
        update["files"][0]["content"] = "void onLoad() { log(\"pending update\"); }"
        status, pending = self.request(
            "POST",
            "/v1/plugins",
            update,
            {"X-Hchat-Install-Id": "review-existing-install"},
        )
        self.assertEqual(status, 200)
        self.assertEqual(pending["data"]["reviewStatus"], "pending")
        pending_version_id = pending["data"]["versionId"]

        status, detail = self.request("GET", f"/v1/plugins/{plugin_id}")
        self.assertEqual(status, 200)
        self.assertEqual(detail["data"]["displayName"], "测试插件")
        self.assertEqual(detail["data"]["latestVersion"]["versionId"], original_version_id)
        status, history = self.request("GET", f"/v1/plugins/{plugin_id}/snapshots")
        self.assertEqual(status, 200)
        self.assertEqual(
            [item["versionId"] for item in history["data"]["items"]],
            [original_version_id],
        )
        status, hidden = self.request(
            "GET", f"/v1/plugins/{plugin_id}/snapshots/{pending_version_id}"
        )
        self.assertEqual(status, 404)
        self.assertEqual(hidden["error"]["code"], "SNAPSHOT_NOT_FOUND")

        status, admin_list = self.request(
            "GET",
            f"/v1/admin/plugins?q={urllib.parse.quote('待审核上传者')}&limit=100",
            headers=headers,
        )
        self.assertEqual(status, 200)
        self.assertEqual(admin_list["data"]["count"], 1)
        admin_plugin = admin_list["data"]["items"][0]
        self.assertEqual(admin_plugin["uploaderWxId"], "wxid_pending_uploader")
        self.assertEqual(admin_plugin["uploaderWeChatId"], "pending_wechat_id")
        self.assertEqual(admin_plugin["uploaderNickname"], "待审核上传者")

        status, _ = self.request(
            "POST",
            f"/v1/admin/plugins/{plugin_id}/versions/{pending_version_id}/approve",
            headers=headers,
        )
        self.assertEqual(status, 200)
        status, detail = self.request("GET", f"/v1/plugins/{plugin_id}")
        self.assertEqual(status, 200)
        self.assertEqual(detail["data"]["displayName"], "审核后的新名称")
        self.assertEqual(detail["data"]["author"], "新作者")
        self.assertEqual(detail["data"]["latestVersion"]["versionId"], pending_version_id)

    def test_disabling_review_approves_pending_versions_and_publishes_newest(self):
        headers = self.admin_headers()
        self.request(
            "POST",
            "/v1/admin/settings",
            {"uploadReviewEnabled": True},
            headers,
        )
        created = self.create_plugin("disable-review-install")
        first_version_id = created["versionId"]
        self.assertEqual(created["reviewStatus"], "pending")

        update = self.upload_body()
        update["pluginId"] = created["pluginId"]
        update["ownerToken"] = created["ownerToken"]
        update["displayName"] = "关闭审核后发布"
        update["versionName"] = "2.0.0"
        update["files"][0]["content"] = "void onLoad() { log(\"newest\"); }"
        status, second = self.request(
            "POST",
            "/v1/plugins",
            update,
            {"X-Hchat-Install-Id": "disable-review-install"},
        )
        self.assertEqual(status, 200)
        second_version_id = second["data"]["versionId"]
        self.assertEqual(second["data"]["reviewStatus"], "pending")

        status, disabled = self.request(
            "POST",
            "/v1/admin/settings",
            {"uploadReviewEnabled": False},
            headers,
        )
        self.assertEqual(status, 200)
        self.assertFalse(disabled["data"]["uploadReviewEnabled"])

        status, detail = self.request("GET", f"/v1/plugins/{created['pluginId']}")
        self.assertEqual(status, 200)
        self.assertEqual(detail["data"]["displayName"], "关闭审核后发布")
        self.assertEqual(detail["data"]["latestVersion"]["versionId"], second_version_id)
        status, history = self.request("GET", f"/v1/plugins/{created['pluginId']}/snapshots")
        self.assertEqual(status, 200)
        self.assertEqual(
            {item["versionId"] for item in history["data"]["items"]},
            {first_version_id, second_version_id},
        )
        self.assertTrue(
            all(item["reviewStatus"] == "approved" for item in history["data"]["items"])
        )

    def test_admin_can_delete_history_and_pending_but_not_current_published_version(self):
        created = self.create_plugin("version-delete-install")
        plugin_id = created["pluginId"]
        first_version_id = created["versionId"]
        update = self.upload_body()
        update["pluginId"] = plugin_id
        update["ownerToken"] = created["ownerToken"]
        update["versionName"] = "2.0.0"
        update["files"][0]["content"] = "void onLoad() { log(\"published two\"); }"
        status, second = self.request(
            "POST",
            "/v1/plugins",
            update,
            {"X-Hchat-Install-Id": "version-delete-install"},
        )
        self.assertEqual(status, 200)
        second_version_id = second["data"]["versionId"]
        headers = self.admin_headers()

        status, blocked = self.request(
            "DELETE",
            f"/v1/admin/plugins/{plugin_id}/versions/{second_version_id}",
            headers=headers,
        )
        self.assertEqual(status, 409)
        self.assertEqual(blocked["error"]["code"], "PUBLISHED_VERSION_DELETE_FORBIDDEN")

        status, deleted_history = self.request(
            "DELETE",
            f"/v1/admin/plugins/{plugin_id}/versions/{first_version_id}",
            headers=headers,
        )
        self.assertEqual(status, 200)
        self.assertFalse(deleted_history["data"]["pluginDeleted"])

        self.request(
            "POST",
            "/v1/admin/settings",
            {"uploadReviewEnabled": True},
            headers,
        )
        update["versionName"] = "3.0.0"
        update["files"][0]["content"] = "void onLoad() { log(\"pending three\"); }"
        status, pending = self.request(
            "POST",
            "/v1/plugins",
            update,
            {"X-Hchat-Install-Id": "version-delete-install"},
        )
        self.assertEqual(status, 200)
        pending_version_id = pending["data"]["versionId"]
        status, deleted_pending = self.request(
            "DELETE",
            f"/v1/admin/plugins/{plugin_id}/versions/{pending_version_id}",
            headers=headers,
        )
        self.assertEqual(status, 200)
        self.assertFalse(deleted_pending["data"]["pluginDeleted"])

        pending_only = self.create_plugin("version-delete-pending-only")
        status, deleted_plugin = self.request(
            "DELETE",
            f"/v1/admin/plugins/{pending_only['pluginId']}/versions/{pending_only['versionId']}",
            headers=headers,
        )
        self.assertEqual(status, 200)
        self.assertTrue(deleted_plugin["data"]["pluginDeleted"])
        status, admin_list = self.request("GET", "/v1/admin/plugins", headers=headers)
        self.assertEqual(status, 200)
        self.assertNotIn(
            pending_only["pluginId"],
            {item["pluginId"] for item in admin_list["data"]["items"]},
        )

    def test_migration_preserves_existing_version_rows(self):
        legacy_migrations = Path(self.temp_dir.name) / "legacy-migrations"
        legacy_migrations.mkdir()
        (legacy_migrations / "001_initial.sql").write_text(
            (ROOT / "migrations" / "001_initial.sql").read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        database_path = Path(self.temp_dir.name) / "legacy-market.db"
        legacy_database = APP.MarketDatabase(database_path, legacy_migrations)
        plugin_id = "p_" + "1" * 32
        version_id = "v_" + "2" * 32
        created_at = APP.utc_now()
        main_content = "void onLoad() { log(\"legacy\"); }"
        main_bytes = main_content.encode("utf-8")
        with legacy_database.session() as connection:
            connection.execute(
                """
                INSERT INTO plugins(
                    id, source_plugin_id, display_name, author,
                    owner_token_hash, uploader_key_hash, latest_version_id,
                    download_count, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 7, ?, ?)
                """,
                (
                    plugin_id,
                    "legacy_plugin",
                    "旧插件",
                    "tester",
                    APP.token_hash("legacy-owner"),
                    APP.sha256_hex(b"legacy-uploader"),
                    version_id,
                    created_at,
                    created_at,
                ),
            )
            connection.execute(
                """
                INSERT INTO plugin_versions(
                    id, plugin_id, version_name, content_hash, total_size, created_at,
                    main_content, main_sha256, main_size
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    version_id,
                    plugin_id,
                    "1.0.0",
                    APP.sha256_hex(main_bytes),
                    len(main_bytes),
                    created_at,
                    main_content,
                    APP.sha256_hex(main_bytes),
                    len(main_bytes),
                ),
            )

        upgraded_database = APP.MarketDatabase(database_path, ROOT / "migrations")
        detail = upgraded_database.get_plugin_detail(plugin_id)
        self.assertEqual(detail["latestVersion"]["versionId"], version_id)
        self.assertEqual(detail["files"][0]["content"], main_content)
        self.assertEqual(detail["downloadCount"], 0)
        with upgraded_database.session() as connection:
            migration_versions = [
                row["version"]
                for row in connection.execute(
                    "SELECT version FROM schema_migrations ORDER BY version"
                ).fetchall()
            ]
            columns = {
                row["name"] for row in connection.execute("PRAGMA table_info(plugin_versions)")
            }
            integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
            foreign_key_errors = connection.execute("PRAGMA foreign_key_check").fetchall()
        self.assertEqual(migration_versions, [1, 2, 3, 4, 5, 6, 7, 8, 9])
        self.assertIn("bshs_content", columns)
        self.assertIn("release_notes", columns)
        self.assertIn("review_status", columns)
        self.assertIn("submitted_display_name", columns)
        self.assertIn("uploader_wxid", columns)
        self.assertIn("uploader_wechat_id", columns)
        self.assertIn("uploader_nickname", columns)
        with upgraded_database.session() as connection:
            extra_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(plugin_version_files)")
            }
            extra_table_sql = connection.execute(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'plugin_version_files'"
            ).fetchone()["sql"]
        self.assertEqual(
            extra_columns,
            {"version_id", "name", "encoding", "content", "sha256", "size"},
        )
        self.assertIn("16777216", extra_table_sql)
        self.assertEqual(detail["latestVersion"]["releaseNotes"], "")
        self.assertEqual(detail["latestVersion"]["reviewStatus"], "approved")
        self.assertFalse(upgraded_database.get_upload_review_enabled())
        with upgraded_database.session() as connection:
            migrated_version = connection.execute(
                """
                SELECT
                    review_status, submitted_display_name, submitted_author,
                    uploader_wxid, uploader_wechat_id, uploader_nickname
                FROM plugin_versions
                WHERE id = ?
                """,
                (version_id,),
            ).fetchone()
        self.assertEqual(migrated_version["review_status"], "approved")
        self.assertEqual(migrated_version["submitted_display_name"], "旧插件")
        self.assertEqual(migrated_version["submitted_author"], "tester")
        self.assertEqual(migrated_version["uploader_wxid"], "")
        self.assertEqual(migrated_version["uploader_wechat_id"], "")
        self.assertEqual(migrated_version["uploader_nickname"], "")
        APP.MarketDatabase(database_path, ROOT / "migrations")
        with upgraded_database.session() as connection:
            repeated_versions = [
                row["version"]
                for row in connection.execute(
                    "SELECT version FROM schema_migrations ORDER BY version"
                ).fetchall()
            ]
            blacklist_table = connection.execute(
                "SELECT name FROM sqlite_master "
                "WHERE type = 'table' AND name = 'uploader_blacklist'"
            ).fetchone()
            likes_table = connection.execute(
                "SELECT name FROM sqlite_master "
                "WHERE type = 'table' AND name = 'plugin_likes'"
            ).fetchone()
            comments_table = connection.execute(
                "SELECT name FROM sqlite_master "
                "WHERE type = 'table' AND name = 'plugin_comments'"
            ).fetchone()
            notification_table = connection.execute(
                "SELECT name FROM sqlite_master "
                "WHERE type = 'table' AND name = 'plugin_notifications'"
            ).fetchone()
            comment_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(plugin_comments)")
            }
        self.assertEqual(repeated_versions, [1, 2, 3, 4, 5, 6, 7, 8, 9])
        self.assertIsNotNone(blacklist_table)
        self.assertIsNotNone(likes_table)
        self.assertIsNotNone(comments_table)
        self.assertIsNotNone(notification_table)
        self.assertIn("parent_comment_id", comment_columns)
        self.assertEqual(integrity, "ok")
        self.assertEqual(foreign_key_errors, [])

    def test_migration_recovers_missing_extra_files_table(self):
        database_path = Path(self.temp_dir.name) / "missing-extra-files.db"
        APP.MarketDatabase(database_path, ROOT / "migrations")
        with APP.MarketDatabase(database_path, ROOT / "migrations").session() as connection:
            connection.execute("DROP TABLE plugin_version_files")
            connection.execute("DELETE FROM schema_migrations WHERE version = 5")

        upgraded_database = APP.MarketDatabase(database_path, ROOT / "migrations")
        with upgraded_database.session() as connection:
            migration_versions = [
                row["version"]
                for row in connection.execute(
                    "SELECT version FROM schema_migrations ORDER BY version"
                ).fetchall()
            ]
            table_sql = connection.execute(
                "SELECT sql FROM sqlite_master "
                "WHERE type = 'table' AND name = 'plugin_version_files'"
            ).fetchone()["sql"]
        self.assertEqual(migration_versions, [1, 2, 3, 4, 5, 6, 7, 8, 9])
        self.assertIn("16777216", table_sql)

    def test_load_admin_password_hash(self):
        password_file = Path(self.temp_dir.name) / "admin.password"
        password_file.write_text(f"  {self.ADMIN_PASSWORD}\n", encoding="utf-8")
        self.assertIsNone(APP.load_admin_password_hash(None))
        loaded_hash = APP.load_admin_password_hash(password_file)
        self.assertTrue(APP.hmac.compare_digest(loaded_hash, APP.token_hash(self.ADMIN_PASSWORD)))
        self.assertNotEqual(loaded_hash, self.ADMIN_PASSWORD)

        password_file.write_text("\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            APP.load_admin_password_hash(password_file)

    def test_rate_limit_and_file_size_limit(self):
        database = self.server.database
        quota = database.consume_upload_quota("quota-install", "127.0.0.2", 1, 3600)
        self.assertEqual(quota["remaining"], 0)
        with self.assertRaises(APP.ApiError) as raised:
            database.consume_upload_quota("quota-install", "127.0.0.2", 1, 3600)
        self.assertEqual(raised.exception.code, "UPLOAD_RATE_LIMITED")

        body = self.upload_body()
        body["files"] = [{"name": "main.java", "content": "x" * (512 * 1024 + 1)}]
        status, response = self.request(
            "POST",
            "/v1/plugins",
            body,
            {"X-Hchat-Install-Id": "test-install-id-size"},
        )
        self.assertEqual(status, 413)
        self.assertEqual(response["error"]["code"], "FILE_TOO_LARGE")

        body = self.upload_body()
        body["releaseNotes"] = "更" * 501
        status, response = self.request(
            "POST",
            "/v1/plugins",
            body,
            {"X-Hchat-Install-Id": "test-release-notes-size"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(response["error"]["code"], "INVALID_FIELD")


if __name__ == "__main__":
    unittest.main()
