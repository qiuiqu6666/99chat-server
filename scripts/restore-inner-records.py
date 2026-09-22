#!/usr/bin/env python3
"""Append missing inner record/class decompilations from the running jar into source files."""

import re
import subprocess
import zipfile
from pathlib import Path

ROOT = Path("/www/wwwroot/99chat-server")
JAR = ROOT / "target/server-0.0.1-SNAPSHOT.jar"
CFR = Path("/tmp/cfr.jar")
JAVA = "/www/server/java/jdk-17.0.8/bin/java"

MISSING = [
    "com.chat99.server.call.CallRecentService.RecentItemView",
    "com.chat99.server.call.CallRecentService.RecentPageView",
    "com.chat99.server.wallet.WalletConfigController.ConfigResponse",
    "com.chat99.server.wallet.WalletConfigController.UpdateRequest",
    "com.chat99.server.wallet.WalletAccountService.RegisterWalletInfo",
    "com.chat99.server.wallet.WalletAccountService.WalletInfo",
    "com.chat99.server.group.CommonGroupService.CommonGroupsResponse",
    "com.chat99.server.complaint.ChatComplaintController.C2cComplaintBody",
    "com.chat99.server.complaint.ChatComplaintController.GroupComplaintBody",
    "com.chat99.server.complaint.ChatComplaintController.SubmitResult",
    "com.chat99.server.complaint.ChatComplaintService.GroupSubmitRequest",
    "com.chat99.server.complaint.ChatComplaintService.SubmitRequest",
    "com.chat99.server.adminapi.AdminChatComplaintService.ComplaintItem",
    "com.chat99.server.adminapi.AdminChatComplaintService.ComplaintListResponse",
    "com.chat99.server.adminapi.AdminChatComplaintService.UpdateStatusRequest",
    "com.chat99.server.adminapi.AdminAuthController.LoginRequest",
    "com.chat99.server.adminapi.AdminAuthController.LoginResponse",
    "com.chat99.server.adminapi.AdminAuthController.MeResponse",
    "com.chat99.server.adminapi.AdminAuthController.ChangePasswordRequest",
    "com.chat99.server.adminapi.AdminJwtService.AdminTokenClaims",
    "com.chat99.server.auth.SliderCaptchaService.SliderCaptchaInitResult",
    "com.chat99.server.im.ImMessageWebhookController.ImCallbackResponse",
    "com.chat99.server.call.CallWebhookService.TrtcCallbackResponse",
    "com.chat99.server.push.PushConfigController.ConfigResponse",
    "com.chat99.server.push.PushConfigController.UpdateRequest",
    "com.chat99.server.im.ImCallbackVerifier.ImCallbackResponse",
    "com.chat99.server.adminapi.AdminAuthService.LoginResult",
    "com.chat99.server.complaint.ChatComplaintService.SubmitResult",
    "com.chat99.server.adminapi.AdminChatComplaintService.UserProfile",
    "com.chat99.server.call.CallRecentService.DeleteAllResult",
    "com.chat99.server.user.UserFriendService.FriendItem",
    "com.chat99.server.user.UserFriendService.FriendListResponse",
    "com.chat99.server.user.UserFriendService.FriendRelationResponse",
    "com.chat99.server.user.UserFriendService.RemarkUpdateResponse",
    "com.chat99.server.user.UserFriendService.DeleteFriendResponse",
]


def decompile_class(class_bytes: bytes) -> str:
    tmp = Path("/tmp/restore_inner.class")
    tmp.write_bytes(class_bytes)
    out = subprocess.check_output([JAVA, "-jar", str(CFR), "--silent", "true", str(tmp)], text=True)
    # Strip CFR header and package if duplicated; keep record/class body only
    m = re.search(r"(public (?:record|class|interface) .*)", out, re.S)
    return m.group(1).strip() if m else out.strip()


def main() -> None:
    if not JAR.exists():
        pid = subprocess.check_output(["pgrep", "-f", "server-0.0.1-SNAPSHOT.jar"], text=True).split()[0]
        fd = Path(f"/proc/{pid}/fd/6")
        JAR.parent.mkdir(parents=True, exist_ok=True)
        JAR.write_bytes(fd.read_bytes())

    by_outer: dict[str, list[str]] = {}
    for fq in MISSING:
        outer, inner = fq.rsplit(".", 1)
        pkg, _, simple = outer.rpartition(".")
        by_outer.setdefault(outer, []).append(inner)

    with zipfile.ZipFile(JAR) as zf:
        for outer, inners in by_outer.items():
            rel = outer.replace(".", "/")
            src = ROOT / "src/main/java" / f"{rel}.java"
            if not src.exists():
                print(f"skip missing source {src}")
                continue
            text = src.read_text(encoding="utf-8")
            additions = []
            for inner in inners:
                simple_outer = outer.rsplit(".", 1)[1]
                if f"record {inner}(" in text or f"class {inner} " in text:
                    continue
                entry = f"BOOT-INF/classes/{rel}${inner}.class"
                try:
                    body = decompile_class(zf.read(entry))
                except KeyError:
                    print(f"missing in jar: {entry}")
                    continue
                simple_outer = outer.rsplit(".", 1)[1]
                body = body.replace(f"public record {outer}.{inner}", f"public record {inner}")
                body = body.replace(f"public class {outer}.{inner}", f"public static class {inner}")
                body = body.replace(f"{outer}.", f"{simple_outer}.")
                additions.append(body)
            if not additions:
                continue
            if not text.rstrip().endswith("}"):
                print(f"bad format {src}")
                continue
            merged = text.rstrip()[:-1] + "\n\n" + "\n\n".join(additions) + "\n}\n"
            src.write_text(merged, encoding="utf-8")
            print(f"updated {src} (+{len(additions)} inners)")


if __name__ == "__main__":
    main()
