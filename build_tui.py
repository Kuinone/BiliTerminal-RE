#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RE:哔哩终端（ReBiliClient）构建 / 安装 TUI 工具。

功能（对应菜单）：
    1. 构建 Debug 版（普通版 / 共存版）
    2. 构建 Release 版（普通版 / 共存版）
    3. 构建全部：普通版 + 共存版 × Debug + Release 一次出 4 个包
    4. 安装到真机（Debug / Release × 普通版 / 共存版）
    5. 安装到模拟器（Debug / Release × 普通版 / 共存版）
    6. 修改版本号 / 版本名（写 app/build.gradle，可顺带同步 strings.xml 的更新日志锚点）
    7. 同步私有功能源码（从 GitHub 私有仓库拉取到 private/，见 docs/optional-features.md）

设计要点：
    * 只用 Python 标准库，不引入第三方依赖（仓库约定「不轻易引入新第三方库」）。
    * 安装前自动判断 APK 是否缺失、是否比源码旧；缺了 / 旧了先构建，避免装到旧包。
    * 真机 / 模拟器区分不只看 `ro.kernel.qemu`：MuMu、雷电这类第三方模拟器
      不写这个属性，还要看 AVD 序列号、x86_64 ABI 与虚拟机 DMI 标识。
    * APK 按设备 ABI 选分包；找不到对应 ABI 时回落到 universal 通用包。
    * Debug 与 Release 签名不同，覆盖安装会被系统拒绝；此时提示先卸载再装
      （会清空该应用数据：登录态、设置、缓存）。
    * Release 需要 `local.properties` 里的 KEY_PATH / KEY_PASSWORD 才会签名；
      没配时会给出提示，产物装不上。
    * 改 versionName 只动 `app/build.gradle` 那一行；`strings.xml` 里的
      「【YY.MM.DD 本次更新】」锚点不会自动跟着改（会询问），不同步的话
      `verifyVersionConsistency` 在 check / CI 上会失败，assemble 不受影响。
    * 私有功能（扫码 / 分享二维码等）的源码放在独立的 GitHub **私有**仓库，
      构建前同步到 `private/`（整目录被 .gitignore 忽略）。同步走 `git clone --depth 1`，
      仓库配置读环境变量或 local.properties 里的 PRIVATE_FEATURE_REPO / PRIVATE_FEATURE_TOKEN /
      PRIVATE_FEATURE_BRANCH；没同步时构建出来的就是公开版（不含私有功能），与 CI 的降级行为一致。

用法：
    python build_tui.py                  # 交互式 TUI（终端不支持时自动降级为行式菜单）
    python build_tui.py --connect        # 连接本机模拟器（MuMu / 雷电 / 夜神）并列出设备
    python build_tui.py --list-devices   # 列出 adb 设备（含 ABI）
    python build_tui.py --list-apks      # 列出已构建的 APK
    python build_tui.py --list-version   # 打印当前 versionName / versionCode
    python build_tui.py --build --type release --flavor coexist
    python build_tui.py --build-all      # 一次构建全部四个变体
    python build_tui.py --install --target emulator --type debug --flavor standard
    python build_tui.py --version-name 26.10.20 --version-code 2610200 --sync-anchor
    python build_tui.py --sync-private   # 从私有仓库同步 private/（先配 --private-repo）
    python build_tui.py --private-repo owner/repo --private-token XXX   # 写入 local.properties
    python build_tui.py --dry-run ...    # 只打印将执行的命令，不真正执行

注意：TUI 用 ANSI 转义序列 + Windows 控制台 VT 模式实现，Windows 10 及以上、以及
Linux / macOS 的常见终端都没问题；老式控制台会自动降级成行式菜单。
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------

IS_WIN = os.name == "nt"
ROOT = Path(__file__).resolve().parent
GRADLEW = ROOT / ("gradlew.bat" if IS_WIN else "gradlew")
APK_ROOT = ROOT / "app" / "build" / "outputs" / "apk"

APP_ID = "com.RobinNotBad.BiliClient"
TITLE = "RE:哔哩终端 · 构建与安装"

# 私有功能源码：不在公开仓库里，构建前从独立的 GitHub 私有仓库同步到这个目录。
# 配置键与 CI（.github/workflows/build-release.yml）保持一致，本机优先读环境变量、其次 local.properties。
PRIVATE_DIR = ROOT / "private"
PRIVATE_SRC = PRIVATE_DIR / "src" / "main" / "java"
LOCAL_PROPS = ROOT / "local.properties"
PRIVATE_REPO_KEY = "PRIVATE_FEATURE_REPO"
PRIVATE_TOKEN_KEY = "PRIVATE_FEATURE_TOKEN"
PRIVATE_BRANCH_KEY = "PRIVATE_FEATURE_BRANCH"

FLAVORS = ("standard", "coexist")
TYPES = ("debug", "release")
# 全量构建的四个变体：两个渠道 × 两种类型
ALL_VARIANTS = tuple((flavor, build_type) for flavor in FLAVORS for build_type in TYPES)

FLAVOR_LABEL = {"standard": "普通版", "coexist": "共存版"}
TYPE_LABEL = {"debug": "Debug", "release": "Release"}

# 设备 ABI → Gradle splits 里的分包名
ABI_SPLIT = {
    "arm64-v8a": "arm64-v8a",
    "armeabi-v7a": "armeabi-v7a",
    "armeabi": "armeabi-v7a",
    "x86": "x86",
    "x86_64": "x86",
}

# adb install 常见失败码 → 人话
INSTALL_HINTS = {
    "INSTALL_FAILED_USER_RESTRICTED": "设备禁止了 USB 安装，请在开发者选项里允许「USB 安装」/ 关闭「监控 ADB 安装」。",
    "INSTALL_FAILED_INSUFFICIENT_STORAGE": "设备存储空间不足，先清理一下。",
    "INSTALL_FAILED_OLDER_SDK": "设备 Android 版本低于 minSdk 19，装不了。",
    "INSTALL_PARSE_FAILED_NO_CERTIFICATES": "APK 未签名（Release 需要 key.jks + local.properties 配置）。",
    "INSTALL_FAILED_ABORTED": "安装被设备端取消（可能需要在手表 / 手机屏幕上点确认）。",
    "INSTALL_FAILED_INVALID_APK": "APK 损坏或与设备不兼容，建议重新构建。",
    "INSTALL_FAILED_UPDATE_INCOMPATIBLE": "与已安装版本签名不一致，需要先卸载。",
}


# ---------------------------------------------------------------------------
# 颜色 / 终端
# ---------------------------------------------------------------------------


class C:
    """ANSI 颜色（只做 16 色，兼容性最好）。"""

    RESET = "\x1b[0m"
    BOLD = "\x1b[1m"
    DIM = "\x1b[2m"
    RED = "\x1b[31m"
    GREEN = "\x1b[32m"
    YELLOW = "\x1b[33m"
    BLUE = "\x1b[34m"
    MAGENTA = "\x1b[35m"
    CYAN = "\x1b[36m"
    WHITE = "\x1b[37m"


# 输出重定向（管道 / 文件）时不输出颜色码，避免日志里出现垃圾字符
USE_ANSI = True


def paint(text, *styles):
    """按需给文本套 ANSI 样式；不支持颜色时原样返回。"""
    if not USE_ANSI or not styles:
        return text
    return "".join(styles) + text + C.RESET


def dwidth(text):
    """文本在终端里的显示宽度（中日韩字符按 2 列算）。"""
    width = 0
    for ch in text:
        if unicodedata.combining(ch):
            continue
        width += 2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1
    return width


def truncate(text, width):
    """按显示宽度截断，超长时补省略号。"""
    if width <= 0:
        return ""
    if dwidth(text) <= width:
        return text
    out = []
    used = 0
    limit = width - 1
    for ch in text:
        cw = 2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1
        if used + cw > limit:
            break
        out.append(ch)
        used += cw
    return "".join(out) + "…"


def pad(text, width):
    """按显示宽度右侧补空格。"""
    return text + " " * max(0, width - dwidth(text))


class Term:
    """终端封装：VT 模式、备用屏、按键读取。"""

    def __init__(self, force_tui=None):
        self.is_tty = sys.stdout.isatty() and sys.stdin.isatty()
        self.tui = bool(self.is_tty if force_tui is None else (force_tui and self.is_tty))
        self._vt = os.name != "nt"
        self._led = False
        self._entered = False
        self._orig_mode = None
        self._handle = None
        if os.name == "nt" and self.tui:
            self._enable_vt()
            if not self._vt:
                # 老控制台不支持 VT，退回行式菜单
                self.tui = False

    # -- VT 模式 ------------------------------------------------------------
    def _enable_vt(self):
        try:
            import ctypes

            k32 = ctypes.windll.kernel32
            k32.GetStdHandle.restype = ctypes.c_void_p
            handle = k32.GetStdHandle(-11)
            if not handle:
                return
            mode = ctypes.c_uint32()
            k32.GetConsoleMode.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32)]
            k32.SetConsoleMode.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
            if not k32.GetConsoleMode(handle, ctypes.byref(mode)):
                return
            enable_vt = 0x0004  # ENABLE_VIRTUAL_TERMINAL_PROCESSING
            if k32.SetConsoleMode(handle, mode.value | enable_vt):
                self._vt = True
                self._led = True
                self._handle = handle
                self._orig_mode = mode.value
        except Exception:
            self._vt = False

    # -- 备用屏 ------------------------------------------------------------
    def enter(self):
        if not self.tui or self._entered:
            return
        sys.stdout.write("\x1b[?1049h\x1b[?25l\x1b[2J")
        sys.stdout.flush()
        self._entered = True

    def leave(self):
        if not self._entered:
            return
        sys.stdout.write("\x1b[?25h\x1b[?1049l")
        sys.stdout.flush()
        self._entered = False

    def cleanup(self):
        try:
            self.leave()
        finally:
            if os.name == "nt" and self._led and self._handle is not None and self._orig_mode is not None:
                try:
                    import ctypes

                    k32 = ctypes.windll.kernel32
                    k32.SetConsoleMode.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
                    k32.SetConsoleMode(self._handle, self._orig_mode)
                except Exception:
                    pass

    # -- 按键 --------------------------------------------------------------
    def read_key(self):
        """读一个按键，返回语义化名字（up/down/left/right/enter/esc/字符...）。"""
        if os.name == "nt":
            import msvcrt

            ch = msvcrt.getwch()
            if ch in ("\x00", "\xe0"):
                code = msvcrt.getwch()
                return {
                    "H": "up",
                    "P": "down",
                    "K": "left",
                    "M": "right",
                    "S": "delete",
                    "I": "pageup",
                    "Q": "pagedown",
                    "G": "home",
                    "O": "end",
                }.get(code, "unknown")
            if ch == "\r":
                return "enter"
            if ch == "\x1b":
                return "esc"
            if ch == "\x03":
                return "ctrl-c"
            if ch == "\x08":
                return "backspace"
            if ch == "\t":
                return "tab"
            if ch == " ":
                return "space"
            return ch.lower() if ch.isalpha() else ch

        import select
        import termios
        import tty

        fd = sys.stdin.fileno()
        old = termios.tcgetattr(fd)
        try:
            tty.setcbreak(fd)
            first = os.read(fd, 1)
            if not first:
                return "esc"
            if first != b"\x1b":
                ch = first.decode("utf-8", "replace")
                if ch == "\r" or ch == "\n":
                    return "enter"
                if ch == "\x03":
                    return "ctrl-c"
                if ch == "\x7f" or ch == "\x08":
                    return "backspace"
                if ch == "\t":
                    return "tab"
                return ch.lower() if ch.isalpha() else ch
            seq = b""
            for _ in range(8):
                ready, _, _ = select.select([fd], [], [], 0.02)
                if not ready:
                    break
                seq += os.read(fd, 1)
            return {
                b"[A": "up",
                b"[B": "down",
                b"[C": "right",
                b"[D": "left",
                b"[H": "home",
                b"[F": "end",
                b"OP": "f1",
            }.get(seq, "esc")
        finally:
            termios.tcsetattr(fd, termios.TCSADRAIN, old)

    def ask_yes_no(self, question):
        """终端里的 y/N 确认；非交互环境读一行。"""
        if not (self.tui or sys.stdin.isatty()):
            try:
                answer = input(f"{question} [y/N] ").strip().lower()
            except EOFError:
                return False
            return answer in ("y", "yes")
        sys.stdout.write(question + " [y/N] ")
        sys.stdout.flush()
        while True:
            key = self.read_key()
            if key == "y":
                sys.stdout.write("y\n")
                sys.stdout.flush()
                return True
            if key in ("n", "enter", "esc"):
                sys.stdout.write("n\n")
                sys.stdout.flush()
                return False

    def wait_return(self, message="按任意键返回菜单…"):
        """暂停一下，让用户看完构建 / 安装输出。"""
        print()
        if self.is_tty:
            sys.stdout.write(message)
            sys.stdout.flush()
            self.read_key()
            print()
        else:
            try:
                input("按回车返回菜单…")
            except EOFError:
                print()


class EnvError(RuntimeError):
    """环境问题（找不到 adb / gradlew 等），需要给用户可读提示。"""


# ---------------------------------------------------------------------------
# 通用小工具
# ---------------------------------------------------------------------------


def terminal_width(default=88):
    try:
        cols = shutil.get_terminal_size((default, 24)).columns
    except Exception:
        cols = default
    return max(46, min(cols, 100))


def human_size(size):
    value = float(size)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.0f} {unit}" if unit == "B" else f"{value:.1f} {unit}"
        value /= 1024
    return f"{size} B"


def rel(path):
    try:
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


# ---------------------------------------------------------------------------
# 路径 / 构建产物
# ---------------------------------------------------------------------------


def apk_dir(flavor, build_type):
    return APK_ROOT / flavor / build_type


def package_id(flavor):
    return APP_ID + ".coexist" if flavor == "coexist" else APP_ID


def task_name(flavor, build_type):
    """AGP 的任务名：flavor 首字母大写 + BuildType 首字母大写。"""
    return f":app:assemble{flavor.capitalize()}{build_type.capitalize()}"


def list_apks(flavor, build_type):
    directory = apk_dir(flavor, build_type)
    if not directory.is_dir():
        return []
    return sorted(directory.glob("*.apk"))


def universal_apk(flavor, build_type):
    for apk in list_apks(flavor, build_type):
        if "universal" in apk.name:
            return apk
    return None


def choose_apk(flavor, build_type, abi):
    """按设备 ABI 选分包；没有对应分包就退回通用包。"""
    apks = list_apks(flavor, build_type)
    if not apks:
        return None
    split = ABI_SPLIT.get(abi, abi)
    if split:
        for apk in apks:
            if f"-{split}-" in apk.name:
                return apk
    fallback = universal_apk(flavor, build_type)
    return fallback or apks[0]


_SRC_MTIME = (0.0, 0.0)


def newest_input_mtime():
    """源码 / 构建脚本的最新修改时间（3 秒缓存）。"""
    global _SRC_MTIME
    now = time.time()
    cached_at, cached_value = _SRC_MTIME
    if now - cached_at < 3:
        return cached_value

    newest = 0.0
    # PRIVATE_DIR 也算输入：同步了私有源码之后，旧 APK 就过期了
    for path in (ROOT / "app" / "src", ROOT / "app" / "libs", PRIVATE_DIR):
        if not path.is_dir():
            continue
        for item in path.rglob("*"):
            try:
                if ".git" in item.parts:
                    continue
                if item.is_file():
                    newest = max(newest, item.stat().st_mtime)
            except OSError:
                continue
    for path in (
        ROOT / "build.gradle",
        ROOT / "settings.gradle",
        ROOT / "gradle.properties",
        ROOT / "app" / "build.gradle",
        ROOT / "app" / "proguard-rules.pro",
    ):
        if path.is_file():
            newest = max(newest, path.stat().st_mtime)
    _SRC_MTIME = (now, newest)
    return newest


def build_required(flavor, build_type):
    """返回 (是否需要构建, 原因)。"""
    apks = list_apks(flavor, build_type)
    if not apks:
        return True, "APK 不存在"
    newest_apk = max(apk.stat().st_mtime for apk in apks)
    if newest_input_mtime() > newest_apk + 1.0:
        return True, "源码比 APK 新"
    return False, ""


def has_release_signing():
    local_props = ROOT / "local.properties"
    if not local_props.is_file():
        return False
    try:
        text = local_props.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    return "KEY_PATH" in text and "KEY_PASSWORD" in text


# ---------------------------------------------------------------------------
# 私有功能源码（GitHub 私有仓库 → private/）
# ---------------------------------------------------------------------------


def read_local_props():
    """把 local.properties 读成 dict（文件不存在 / 读不了就返回空 dict）。"""
    if not LOCAL_PROPS.is_file():
        return {}
    try:
        text = LOCAL_PROPS.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return {}
    props = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def write_local_props(key, value):
    """在 local.properties 里写入 / 更新一行 KEY=VALUE，保留其余内容与原有行尾风格。"""
    try:
        text = (
            LOCAL_PROPS.read_text(encoding="utf-8", errors="replace")
            if LOCAL_PROPS.is_file()
            else ""
        )
    except OSError:
        text = ""
    newline = "\r\n" if "\r\n" in text else "\n"
    pattern = re.compile(r"(?m)^[ \t]*" + re.escape(key) + r"[ \t]*=.*$")
    line = f"{key}={value}"
    if pattern.search(text):
        text = pattern.sub(lambda _match: line, text, count=1)
    else:
        if text and not text.endswith(("\n", "\r")):
            text += newline
        text += line + newline
    LOCAL_PROPS.write_text(text, encoding="utf-8")


def private_setting(key):
    """取私有仓库配置：环境变量优先，其次 local.properties（都和 CI 的命名一致）。"""
    value = os.environ.get(key, "").strip()
    if value:
        return value
    return read_local_props().get(key, "").strip()


def private_present():
    """private/ 里是否已有可编译的私有源码（与 app/build.gradle 的 hasPrivateFeature 同一判据）。"""
    return PRIVATE_SRC.is_dir()


def private_hint():
    """TUI 菜单里那列简短的私有功能状态。"""
    return "已就位" if private_present() else "未获取"


def private_note():
    """构建横幅上的私有功能状态：让人一眼知道这个包带不带私有功能。"""
    if private_present():
        return "私有功能：已注入 private/（按私有仓库的内容构建）"
    return "私有功能：未获取（本次构建不含私有功能，等同公开版）"


def _mask_url(text):
    """把 URL 里 `://…@` 那一段的凭据抹成 ***，日志与报错都不许把 token 带出去。"""
    return re.sub(r"(://)[^@/\s]*@", r"\1***@", text)


def _normalize_repo(repo):
    """允许 owner/repo、https://github.com/owner/repo(.git)、git@github.com:owner/repo 几种写法。"""
    repo = (repo or "").strip()
    for prefix in ("https://github.com/", "http://github.com/", "git@github.com:"):
        if repo.startswith(prefix):
            repo = repo[len(prefix) :]
    if repo.endswith(".git"):
        repo = repo[: -len(".git")]
    return repo.strip("/ ")


def _private_clone_url(repo, token):
    """拼 clone 地址；有 token 用 x-access-token（细粒度 PAT，Contents: Read 即可）。"""
    if token:
        return f"https://x-access-token:{token}@github.com/{repo}.git"
    return f"https://github.com/{repo}.git"


def sync_private_feature(dry_run=False, force=False):
    """把私有功能源码从 GitHub 私有仓库同步到 private/。返回 (是否成功, 说明)。

    做法与 CI 的注入步骤一致：浅克隆到一个临时目录，再整体替换 private/。
    **带了 token 才删 .git**（凭据不能留在磁盘上，CI 就是这种情况）；本机靠凭据管理器
    鉴权时不带 token，于是 private/ 保持是一个能继续 commit / push 的仓库。
    已存在且没有 force 时**不动**它——本地手改的私有源码不该被静默覆盖。
    """
    raw_repo = private_setting(PRIVATE_REPO_KEY)
    if not raw_repo:
        return False, (
            "还没有配置私有仓库：在 local.properties 里写 "
            f"{PRIVATE_REPO_KEY}=<owner/repo>（或设同名环境变量），再回来同步。"
        )
    repo = _normalize_repo(raw_repo)
    if "/" not in repo:
        return False, f"私有仓库名不像 owner/repo：{raw_repo}"

    token = private_setting(PRIVATE_TOKEN_KEY)
    branch = private_setting(PRIVATE_BRANCH_KEY)
    url = _private_clone_url(repo, token)

    if PRIVATE_DIR.exists() and not force:
        return False, (
            "private/ 已存在，没有覆盖它。要按私有仓库的最新内容重建，"
            "加 --private-force（TUI 里会先问一句）。"
        )

    if shutil.which("git") is None:
        return False, "找不到 git 命令：先安装 Git，或把它加进 PATH。"

    tmp_dir = PRIVATE_DIR.with_name("private.tmp")
    cmd = ["git", "clone", "--depth", "1"]
    if branch:
        cmd += ["--branch", branch]
    cmd += [url, str(tmp_dir)]

    print()
    print(paint(f"$ {_mask_url(' '.join(cmd))}", C.BOLD, C.BLUE))
    sys.stdout.flush()
    if dry_run:
        print(paint("[dry-run] 未真正拉取", C.DIM))
        return True, "[dry-run] 将把私有仓库拉取到 private/"

    def _remove(path):
        if path.exists():
            shutil.rmtree(path, ignore_errors=True)

    _remove(tmp_dir)
    try:
        result = subprocess.run(
            cmd,
            cwd=str(ROOT),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            errors="replace",
        )
    except OSError as exc:
        _remove(tmp_dir)
        return False, f"启动 git 失败：{_mask_url(str(exc))}"
    output = (result.stdout or "").strip()
    if result.returncode != 0:
        _remove(tmp_dir)
        detail = _mask_url(truncate(output, 200)) if output else f"git 退出码 {result.returncode}"
        return False, f"拉取失败：{detail}"
    if not (tmp_dir / "src" / "main" / "java").is_dir():
        _remove(tmp_dir)
        return False, (
            "拉下来的仓库里没有 src/main/java，目录结构不对："
            "私有仓库根目录应当就是 private/ 的内容（manifest/ · src/ · res/ · keep.pro）。"
        )
    if token:
        # 带 token 的克隆：删掉 .git，别把凭据留在 .git/config 里
        shutil.rmtree(tmp_dir / ".git", ignore_errors=True)
    _remove(PRIVATE_DIR)
    try:
        os.replace(tmp_dir, PRIVATE_DIR)
    except OSError:
        # 跨盘 / 被占用时 os.replace 换不动目录，退回 shutil.move
        shutil.move(str(tmp_dir), str(PRIVATE_DIR))
    tail = "已删除 .git（凭据不留在磁盘上）" if token else "保留了 .git，可在 private/ 里继续 commit / push"
    return True, f"私有源码已同步到 private/（{repo}，{tail}）"


def save_private_settings(args):
    """把 --private-repo / --private-token / --private-branch 写进 local.properties。返回写入的键名列表。"""
    saved = []
    if args.private_repo is not None:
        repo = _normalize_repo(args.private_repo)
        if repo:
            write_local_props(PRIVATE_REPO_KEY, repo)
            saved.append(PRIVATE_REPO_KEY)
    if args.private_token is not None:
        write_local_props(PRIVATE_TOKEN_KEY, args.private_token.strip())
        saved.append(PRIVATE_TOKEN_KEY)
    if args.private_branch is not None:
        write_local_props(PRIVATE_BRANCH_KEY, args.private_branch.strip())
        saved.append(PRIVATE_BRANCH_KEY)
    return saved


def sync_private_feature_interactive(dry_run=False):
    """交互式同步：缺配置时就地问，并写进 local.properties（token 只报键名，不回显）。"""
    repo = private_setting(PRIVATE_REPO_KEY)
    if not repo:
        print(paint("私有功能源码不在公开仓库里，需要从 GitHub 私有仓库拉取。", C.DIM))
        print(paint("私有仓库根目录应当就是 private/ 的内容（manifest/ · src/ · res/ · keep.pro）。", C.DIM))
        try:
            repo = input("私有仓库 (owner/repo，直接回车取消)> ").strip()
        except EOFError:
            repo = ""
        if not repo:
            return False, "已取消"
        write_local_props(PRIVATE_REPO_KEY, _normalize_repo(repo))
        print(paint(f"  已写入 local.properties：{PRIVATE_REPO_KEY}={_normalize_repo(repo)}", C.GREEN))
    if not private_setting(PRIVATE_TOKEN_KEY):
        print(paint("私有仓库需要一个只读 token（细粒度 PAT，Contents: Read）；公开仓库可留空。", C.DIM))
        try:
            token = input("Token（回车跳过）> ").strip()
        except EOFError:
            token = ""
        if token:
            write_local_props(PRIVATE_TOKEN_KEY, token)
            print(paint(f"  已写入 local.properties：{PRIVATE_TOKEN_KEY}=***", C.GREEN))
    branch = private_setting(PRIVATE_BRANCH_KEY)
    if branch:
        print(paint(f"  分支：{branch}", C.DIM))
    else:
        print(paint("  分支：默认分支（想固定分支就配 PRIVATE_FEATURE_BRANCH）", C.DIM))
    force = False
    if private_present():
        try:
            answer = input("private/ 已存在，按私有仓库最新内容覆盖？(y/N)> ").strip().lower()
        except EOFError:
            answer = ""
        force = answer in ("y", "yes")
        if not force:
            return False, "保留现有 private/，未同步"
    return sync_private_feature(dry_run, force=force)


# ---------------------------------------------------------------------------
# 版本号 / 版本名
# ---------------------------------------------------------------------------

BUILD_GRADLE = ROOT / "app" / "build.gradle"
STRINGS_XML = ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml"

# defaultConfig 里的两行声明；`versionName += "-BETA" + betaVer` 那种拼接行不会被匹配。
# 说明：仓库里的 gradle 文件可能是 CRLF，`$` 不会在 `\r` 前成立，所以必须显式吃掉 `\r`
# 并且把它放进分组——否则 sub() 会连同 `\r` 一起替换掉，把这一行改成 LF（曾真踩过）。
_RE_VERSION_CODE = re.compile(
    r"(?m)^([ \t]*versionCode[ \t]+)(\d+)([ \t]*(?://[^\r\n]*)?)(\r?)$"
)
_RE_VERSION_NAME = re.compile(r'(?m)^([ \t]*versionName[ \t]+")([^"\r\n]*)(")')
_RE_VERSION_NAME_OK = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]*$")


class VersionChange:
    """一次版本改动（或 dry-run）的结果。"""

    def __init__(self, old_name, old_code):
        self.ok = True
        self.error = ""
        self.changed = False
        self.old_name = old_name
        self.old_code = old_code
        self.new_name = old_name
        self.new_code = old_code
        self.anchor_synced = False
        self.anchor_available = False


def _read_text_exact(path):
    """按原样读文本（不转换换行），写回时才能保证除目标行外一个字节都不变。"""
    with path.open("r", encoding="utf-8", newline="") as handle:
        return handle.read()


def _write_text_exact(path, text):
    with path.open("w", encoding="utf-8", newline="") as handle:
        handle.write(text)


def read_version_info():
    """读 app/build.gradle 里真正生效的 versionCode / versionName。"""
    if not BUILD_GRADLE.is_file():
        raise EnvError(f"找不到 {rel(BUILD_GRADLE)}")
    try:
        text = _read_text_exact(BUILD_GRADLE)
    except OSError as exc:
        raise EnvError(f"读取 {rel(BUILD_GRADLE)} 失败：{exc}") from exc
    code_match = _RE_VERSION_CODE.search(text)
    name_match = _RE_VERSION_NAME.search(text)
    if not code_match or not name_match:
        raise EnvError(f"解析 {rel(BUILD_GRADLE)} 的 versionCode / versionName 失败")
    return int(code_match.group(2)), name_match.group(2)


def validate_version_name(value):
    """校验版本名；返回空字符串表示通过。"""
    if not value:
        return "版本名不能为空"
    if not _RE_VERSION_NAME_OK.match(value):
        return "版本名只能用字母、数字、点、下划线、连字符（本仓库惯例是 YY.MM.DD，如 26.10.20）"
    if len(value) > 32:
        return "版本名太长（最多 32 个字符）"
    return ""


def is_yy_mm_dd(value):
    return bool(re.fullmatch(r"\d{2}\.\d{2}\.\d{2}", value))


def version_anchor(name):
    """更新日志锚点，与 app/build.gradle 的 verifyVersionConsistency 一致。"""
    return f"【{name} 本次更新】"


def anchor_present(name):
    """strings.xml 里是否已有该版本名的更新日志锚点。"""
    if not STRINGS_XML.is_file():
        return False
    try:
        return version_anchor(name) in _read_text_exact(STRINGS_XML)
    except OSError:
        return False


def sync_version_anchor(old_name, new_name, dry_run=False):
    """把 strings.xml 的更新日志锚点【旧版本 本次更新】改成新版本。返回是否找到锚点。"""
    if not STRINGS_XML.is_file():
        return False
    try:
        text = _read_text_exact(STRINGS_XML)
    except OSError:
        return False
    old_anchor = version_anchor(old_name)
    if old_anchor not in text:
        return False
    if not dry_run:
        _write_text_exact(STRINGS_XML, text.replace(old_anchor, version_anchor(new_name)))
    return True


def apply_version(new_name=None, new_code=None, dry_run=False, sync_anchor=False):
    """改 versionName / versionCode；返回 VersionChange。不打印任何东西。"""
    old_code, old_name = read_version_info()
    change = VersionChange(old_name, old_code)
    target_name = old_name if new_name is None else new_name
    target_code = old_code if new_code is None else new_code

    error = validate_version_name(target_name)
    if error:
        change.ok = False
        change.error = error
        return change
    if not isinstance(target_code, int) or target_code <= 0:
        change.ok = False
        change.error = "版本号必须是正整数"
        return change

    change.new_name = target_name
    change.new_code = target_code
    change.anchor_available = anchor_present(old_name)
    if target_name == old_name and target_code == old_code:
        return change

    text = _read_text_exact(BUILD_GRADLE)
    if target_name != old_name:
        text = _RE_VERSION_NAME.sub(
            lambda match: match.group(1) + target_name + match.group(3), text, count=1
        )
    if target_code != old_code:
        text = _RE_VERSION_CODE.sub(
            lambda match: match.group(1) + str(target_code) + match.group(3) + match.group(4),
            text,
            count=1,
        )

    if dry_run:
        change.changed = True
        return change

    _write_text_exact(BUILD_GRADLE, text)
    # 写回后回读，确认真的生效（正则写错时不要留个假成功）
    try:
        read_code, read_name = read_version_info()
    except EnvError as exc:
        change.ok = False
        change.error = str(exc)
        return change
    if read_code != target_code or read_name != target_name:
        change.ok = False
        change.error = f"回读校验失败（现在是 {read_name} / {read_code}），请手动检查 {rel(BUILD_GRADLE)}"
        return change

    change.changed = True
    if sync_anchor and target_name != old_name and change.anchor_available:
        change.anchor_synced = sync_version_anchor(old_name, target_name)
    return change


def print_version_change(change, dry_run=False):
    """打印一次版本改动的人话结果。"""
    if not change.ok:
        print(paint(f"  ✗ {change.error}", C.RED))
        return
    if not change.changed:
        print(paint(f"  版本没有变化：{change.old_name} ({change.old_code})", C.DIM))
        return
    arrow = f"{change.old_name} ({change.old_code}) → {change.new_name} ({change.new_code})"
    prefix = "[dry-run] 未写入，将改为" if dry_run else "✓ 版本已改为"
    print(paint(f"  {prefix} {arrow}", C.GREEN if not dry_run else C.CYAN, C.BOLD))
    if dry_run:
        return
    print(f"  已写入 {rel(BUILD_GRADLE)}")
    if not is_yy_mm_dd(change.new_name):
        print(paint(f"  [提示] 版本名不是仓库惯例的 YY.MM.DD 格式：{change.new_name}", C.YELLOW))
    if change.new_name != change.old_name and not change.anchor_synced:
        if change.anchor_available:
            print(
                paint(
                    f"  [注意] {rel(STRINGS_XML)} 里仍是【{change.old_name} 本次更新】；"
                    "构建不受影响，但 check / CI 的 verifyVersionConsistency 会失败。",
                    C.YELLOW,
                )
            )
        else:
            print(
                paint(
                    f"  [注意] {rel(STRINGS_XML)} 里没有【{change.old_name} 本次更新】锚点，"
                    "verifyVersionConsistency 会失败。",
                    C.YELLOW,
                )
            )


def prompt_version_edit(term, dry_run=False):
    """交互修改版本号 / 版本名（TUI 与行式菜单共用）。返回 (成功, 状态文案)。"""
    try:
        old_code, old_name = read_version_info()
    except EnvError as exc:
        print(paint(f"[错误] {exc}", C.RED))
        return False, str(exc)

    print()
    print(paint("▶ 修改版本号 / 版本名", C.BOLD, C.CYAN))
    print(f'  当前：versionName "{old_name}"    versionCode {old_code}')
    print(paint("  直接回车表示保持不变，输入 q 取消。", C.DIM))
    try:
        name_input = input("新的 versionName: ").strip()
        code_input = "" if name_input.lower() == "q" else input("新的 versionCode: ").strip()
    except EOFError:
        print(paint("  已取消", C.DIM))
        return False, "已取消修改版本号"
    if name_input.lower() == "q" or code_input.lower() == "q":
        print(paint("  已取消", C.DIM))
        return False, "已取消修改版本号"

    new_name = name_input or old_name
    if code_input:
        try:
            new_code = int(code_input)
        except ValueError:
            print(paint("  [错误] versionCode 必须是数字", C.RED))
            return False, "版本号必须是正整数"
    else:
        new_code = old_code

    error = validate_version_name(new_name)
    if error:
        print(paint(f"  [错误] {error}", C.RED))
        return False, error

    sync = False
    if new_name != old_name:
        if anchor_present(old_name):
            sync = term.ask_yes_no(
                f"同时把 strings.xml 的更新日志锚点改成【{new_name} 本次更新】？"
            )
        else:
            print(paint(f"  [注意] strings.xml 里没有【{old_name} 本次更新】锚点，跳过同步", C.YELLOW))

    try:
        change = apply_version(new_name, new_code, dry_run, sync)
    except (OSError, EnvError) as exc:
        print(paint(f"  ✗ 写版本失败：{exc}", C.RED))
        return False, f"写版本失败：{exc}"
    print_version_change(change, dry_run)
    if not change.ok:
        return False, change.error
    if not change.changed:
        return True, f"版本没有变化：{change.old_name} ({change.old_code})"
    return True, f"版本：{change.new_name} ({change.new_code})"


# ---------------------------------------------------------------------------
# adb
# ---------------------------------------------------------------------------

_ADB = None
_KIND_CACHE = {}
_PROPS_CACHE = {}
# 自动 connect 成功的模拟器（供 TUI 状态栏提示）
AUTO_CONNECTED = []

# 常见模拟器的本机 adb 端口：MuMu 12 / 老版 MuMu / 夜神 / 逍遥
EMULATOR_PORTS = (5555, 16384, 7555, 62001, 21503)

# ro.hardware 取值 → 一定是模拟器
_EMULATOR_HARDWARE = {"goldfish", "ranchu", "qemu", "virtualbox", "vbox"}

# 虚拟机 DMI 特征（小写比较）：innotek / VirtualBox 是 MuMu 的底层
_VM_MARKERS = (
    "innotek",
    "virtualbox",
    "vmware",
    "qemu",
    "parallels",
    "hyper-v",
    "microsoft corporation",
    "bochs",
    "niohbox",
)

# adb connect 出来的本机设备：127.0.0.1:5555 / localhost:16384 / 10.0.2.2:5555
_LOCAL_TCP = re.compile(r"^(127\.0\.0\.1|localhost|10\.0\.2\.2|::1):\d+$")


def _read_sdk_dir():
    local_props = ROOT / "local.properties"
    if not local_props.is_file():
        return None
    try:
        text = local_props.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("sdk.dir="):
            return line.split("=", 1)[1].strip().replace("\\\\", "\\")
    return None


def adb_path():
    """找 adb：PATH → ANDROID_HOME / ANDROID_SDK_ROOT → local.properties 的 sdk.dir。"""
    global _ADB
    if _ADB is not None:
        return _ADB
    found = shutil.which("adb")
    if found:
        _ADB = found
        return _ADB
    candidates = []
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(key)
        if value:
            candidates.append(Path(value) / "platform-tools" / "adb.exe")
    sdk_dir = _read_sdk_dir()
    if sdk_dir:
        candidates.append(Path(sdk_dir) / "platform-tools" / "adb.exe")
    for candidate in candidates:
        if candidate.is_file():
            _ADB = str(candidate)
            return _ADB
    return None


def adb_run(args, timeout=120):
    adb = adb_path()
    if not adb:
        raise EnvError(
            "未找到 adb：请把 Android SDK 的 platform-tools 加入 PATH，"
            "或在 local.properties 里配置 sdk.dir。"
        )
    return subprocess.run(
        [adb, *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        # `adb shell ...` 会把 stdin 转给远端 shell，若不隔离就会吃掉
        # 通过管道喂给本脚本的按键（--no-tui 的行式菜单因此直接 EOF 退出）。
        stdin=subprocess.DEVNULL,
    )


def _parse_props(text):
    """把 `adb shell getprop` 的 `[key]: [value]` 输出解析成 dict。"""
    props = {}
    for line in (text or "").splitlines():
        line = line.strip()
        if not line.startswith("[") or "]:" not in line:
            continue
        raw_key, _, rest = line.partition("]:")
        value = rest.strip()
        if value.startswith("[") and value.endswith("]"):
            value = value[1:-1]
        props[raw_key[1:].strip()] = value.strip()
    return props


def device_props(serial):
    """一次 getprop 取回设备全部系统属性（按序列号缓存，省得反复连）。"""
    if serial in _PROPS_CACHE:
        return _PROPS_CACHE[serial]
    props = {}
    try:
        result = adb_run(["-s", serial, "shell", "getprop"], timeout=20)
        props = _parse_props(result.stdout or "")
    except Exception:
        props = {}
    _PROPS_CACHE[serial] = props
    return props


def _dmi_text(serial):
    """读虚拟机 DMI 标识；真机也有这两个文件（报的是主板厂商）。"""
    try:
        # 两个纯路径参数交给设备端 cat，一次读完（别带管道 / 分号，会被拆开）
        result = adb_run(
            ["-s", serial, "shell", "cat", "/sys/class/dmi/id/sys_vendor",
             "/sys/class/dmi/id/product_name"],
            timeout=10,
        )
    except Exception:
        return ""
    return " ".join((result.stdout or "").lower().split())


def probe_device(serial):
    """返回 (kind, model)；kind 为 device / emulator。

    判定顺序（越靠前越可信）：
        1. AVD 的序列号一定是 emulator-5554 这种；
        2. 官方模拟器写 ro.kernel.qemu / ro.boot.qemu = 1；
        3. ro.hardware 是 goldfish / ranchu（AVD）或 virtualbox（MuMu 这类）；
        4. 虚拟机 DMI 标识 —— MuMu 报 innotek GmbH，雷电 / 夜神也各有特征；
        5. 本机回环地址的 TCP 设备（adb connect 127.0.0.1:端口）；
        6. x86 / x86_64 的安卓手表、手机几乎不存在。
    """
    if serial in _KIND_CACHE:
        return _KIND_CACHE[serial]
    props = device_props(serial)
    model = props.get("ro.product.model", "")
    kind = "device"
    abi = props.get("ro.product.cpu.abi", "").lower()
    hardware = props.get("ro.hardware", "").lower()
    if serial.startswith("emulator-"):
        kind = "emulator"
    elif props.get("ro.kernel.qemu") == "1" or props.get("ro.boot.qemu") == "1":
        kind = "emulator"
    elif hardware in _EMULATOR_HARDWARE:
        kind = "emulator"
    elif any(mark in _dmi_text(serial) for mark in _VM_MARKERS):
        kind = "emulator"
    elif _LOCAL_TCP.match(serial):
        kind = "emulator"
    elif abi.startswith("x86"):
        kind = "emulator"
    _KIND_CACHE[serial] = (kind, model)
    return kind, model


def connect_emulators():
    """尝试 adb connect 本机常见模拟器端口，返回「新连上」的 serial 列表。

    MuMu / 雷电 / 夜神这类模拟器不会自己出现在 `adb devices` 里，
    必须先 `adb connect 127.0.0.1:<端口>`；端口各家不同，这里挨个试。
    注意 MuMu 会同时开 5555 / 7555 / 16384 三个转发口，去重在 dedupe_devices()。
    """
    newly = []
    for port in EMULATOR_PORTS:
        target = f"127.0.0.1:{port}"
        try:
            result = adb_run(["connect", target], timeout=10)
        except Exception:
            continue
        text = ((result.stdout or "") + (result.stderr or "")).strip().lower()
        # 断开时 adb 报 "cannot connect to ...: 由于目标计算机积极拒绝"，
        # 这类文本里也含 "connect"，必须逐项排除，别把失败当成功。
        if "cannot connect" in text or "failed" in text or "refused" in text:
            continue
        if "already connected" in text:
            continue
        if not re.search(r"(?:^|\n)\s*connected to\s+127\.0\.0\.1:", text):
            continue
        newly.append(target)
    return newly


_ANDROID_ID_CACHE = {}


def _device_identity(device):
    """给「连到本机的 TCP 设备」算唯一身份，用来识别同一台模拟器的多个端口。

    优先 android_id：同一台模拟器不管挂几个端口都相同；不同模拟器实例不同。
    读不到时退回 model + ro.product.device + ABI，再读不到就当它独一份。
    """
    if device.serial not in _ANDROID_ID_CACHE:
        value = ""
        try:
            result = adb_run(
                ["-s", device.serial, "shell", "settings", "get", "secure", "android_id"],
                timeout=10,
            )
            value = (result.stdout or "").strip()
        except Exception:
            value = ""
        _ANDROID_ID_CACHE[device.serial] = "" if value == "null" else value
    android_id = _ANDROID_ID_CACHE[device.serial]
    if android_id:
        return ("android_id", android_id)
    props = device_props(device.serial)
    return (
        "model",
        device.model,
        props.get("ro.product.device", ""),
        props.get("ro.product.cpu.abi", ""),
    )


def _port_rank(serial):
    try:
        port = int(serial.rsplit(":", 1)[1])
    except (IndexError, ValueError):
        return len(EMULATOR_PORTS)
    return EMULATOR_PORTS.index(port) if port in EMULATOR_PORTS else len(EMULATOR_PORTS)


def dedupe_devices(devices):
    """同一台模拟器同时暴露多个 adb 端口时，只留一台（端口按 EMULATOR_PORTS 顺序优先）。"""
    local = [d for d in devices if d.state == "device" and _LOCAL_TCP.match(d.serial)]
    if len(local) < 2:
        return devices
    kept_serials = set()
    seen = set()
    for device in sorted(local, key=lambda d: _port_rank(d.serial)):
        identity = _device_identity(device)
        if identity[0] == "model" and not any(identity[1:]):
            kept_serials.add(device.serial)
            continue
        if identity in seen:
            continue
        seen.add(identity)
        kept_serials.add(device.serial)
    return [
        device
        for device in devices
        if not (device.state == "device" and _LOCAL_TCP.match(device.serial))
        or device.serial in kept_serials
    ]


@dataclass
class Device:
    serial: str
    state: str
    kind: str = "device"
    model: str = ""

    @property
    def label(self):
        return "模拟器" if self.kind == "emulator" else "真机"


def _read_devices():
    """解析 `adb devices -l`，并对在线设备补上类型与型号。"""
    result = adb_run(["devices", "-l"], timeout=20)
    if result.returncode != 0:
        raise EnvError((result.stderr or result.stdout or "adb devices 执行失败").strip())
    devices = []
    for line in (result.stdout or "").splitlines()[1:]:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        serial, state = parts[0], parts[1]
        model = ""
        for token in parts[2:]:
            if token.startswith("model:"):
                model = token[len("model:"):]
        devices.append(Device(serial=serial, state=state, model=model))
    for device in devices:
        if device.state == "device":
            kind, model = probe_device(device.serial)
            device.kind = kind
            device.model = model or device.model
        elif device.serial.startswith("emulator-"):
            device.kind = "emulator"
    return devices


def list_devices(auto_connect=True):
    """读取 adb 设备列表；一台都没连上时自动尝试本机模拟器端口，并合并多端口重复项。"""
    devices = _read_devices()
    if auto_connect and not any(device.state == "device" for device in devices):
        newly = connect_emulators()
        if newly:
            devices = _read_devices()
            AUTO_CONNECTED[:] = newly
    devices = dedupe_devices(devices)
    if AUTO_CONNECTED:
        kept = {device.serial for device in devices}
        AUTO_CONNECTED[:] = [serial for serial in AUTO_CONNECTED if serial in kept]
    return devices


def device_abi(device):
    props = device_props(device.serial)
    abi = props.get("ro.product.cpu.abi", "").strip()
    if abi:
        return abi
    # 极老设备没有这个属性时，退回归一化列表的首项
    abi_list = props.get("ro.product.cpu.abilist", "")
    first = abi_list.split(",")[0].strip()
    return first


# ---------------------------------------------------------------------------
# Gradle
# ---------------------------------------------------------------------------


def gradle_cmd(tasks, extra=()):
    """构造 gradlew 调用；用相对文件名避免仓库路径里带空格时 cmd 解析出错。

    tasks 可以是单个任务名（str），也可以是任务名列表（一次调用构建多个变体）。
    """
    if not GRADLEW.is_file():
        raise EnvError(f"找不到 {GRADLEW.name}，请在仓库根目录运行本脚本。")
    names = [tasks] if isinstance(tasks, str) else list(tasks)
    if IS_WIN:
        return ["cmd", "/c", "gradlew.bat", *names, *extra]
    return ["./gradlew", *names, *extra]


def run_gradle(tasks, dry_run=False, extra=()):
    cmd = gradle_cmd(tasks, extra)
    print()
    print(paint(f"$ {' '.join(cmd)}", C.BOLD, C.BLUE))
    sys.stdout.flush()
    if dry_run:
        print(paint("[dry-run] 未执行构建", C.DIM))
        return True, 0.0
    started = time.time()
    try:
        code = subprocess.run(cmd, cwd=str(ROOT)).returncode
    except OSError as exc:
        print(paint(f"[错误] 启动 Gradle 失败：{exc}", C.RED))
        return False, 0.0
    return code == 0, time.time() - started


def print_apks(flavor, build_type):
    apks = list_apks(flavor, build_type)
    directory = apk_dir(flavor, build_type)
    if not apks:
        print(paint("  （该目录下没有 APK）", C.DIM))
        return
    print(f"  产物目录：{rel(directory)}")
    for apk in apks:
        stat = apk.stat()
        stamp = time.strftime("%H:%M:%S", time.localtime(stat.st_mtime))
        print(f"    {apk.name:<44} {human_size(stat.st_size):>9}  {stamp}")


def version_note():
    """构建横幅上那行版本信息；读不到就空着，不打断构建。"""
    try:
        code, name = read_version_info()
    except EnvError:
        return ""
    return f"当前版本：{name} ({code})"


def execute_build(build_type, flavor, dry_run=False):
    """执行一次构建，返回是否成功。"""
    task = task_name(flavor, build_type)
    print()
    print(paint(f"▶ 构建 {TYPE_LABEL[build_type]} · {FLAVOR_LABEL[flavor]}", C.BOLD, C.CYAN))
    print(f"  Gradle 任务：{task}")
    note = version_note()
    if note:
        print(f"  {note}")
    print(f"  {private_note()}")
    if build_type == "release" and not has_release_signing():
        print(
            paint(
                "  [注意] local.properties 未配置 KEY_PATH / KEY_PASSWORD，"
                "Release 包不会签名，无法直接安装。",
                C.YELLOW,
            )
        )
    ok, duration = run_gradle(task, dry_run)
    if not ok:
        print(paint(f"✗ 构建失败（{duration:.1f}s），请查看上面的 Gradle 输出。", C.RED, C.BOLD))
        return False
    print(paint(f"✓ 构建成功（{duration:.1f}s）", C.GREEN, C.BOLD))
    print_apks(flavor, build_type)
    return True


def execute_build_all(dry_run=False):
    """一次构建四个变体：普通版 / 共存版 × Debug / Release。"""
    tasks = [task_name(flavor, build_type) for flavor, build_type in ALL_VARIANTS]
    print()
    print(paint("▶ 构建全部：普通版 + 共存版 × Debug + Release", C.BOLD, C.CYAN))
    note = version_note()
    if note:
        print(f"  {note}")
    print(f"  {private_note()}")
    for task in tasks:
        print(f"  {task}")
    if not has_release_signing():
        print(
            paint(
                "  [注意] local.properties 未配置 KEY_PATH / KEY_PASSWORD，"
                "两个 Release 包不会签名，无法直接安装。",
                C.YELLOW,
            )
        )
    # --continue：某个变体失败时其余任务照常跑完，避免只出一半包就中断
    ok, duration = run_gradle(tasks, dry_run, extra=("--continue",))
    if not ok:
        print(
            paint(
                f"✗ 全量构建有失败（{duration:.1f}s），请查看上面的 Gradle 输出，"
                "已完成的变体仍可用。",
                C.RED,
                C.BOLD,
            )
        )
        for flavor, build_type in ALL_VARIANTS:
            print()
            print(paint(f"[{FLAVOR_LABEL[flavor]} · {TYPE_LABEL[build_type]}]", C.BOLD))
            print_apks(flavor, build_type)
        return False
    print(paint(f"✓ 全部构建成功（{duration:.1f}s，4 个变体）", C.GREEN, C.BOLD))
    for flavor, build_type in ALL_VARIANTS:
        print()
        print(paint(f"[{FLAVOR_LABEL[flavor]} · {TYPE_LABEL[build_type]}]", C.BOLD))
        print_apks(flavor, build_type)
    return True


# ---------------------------------------------------------------------------
# 安装
# ---------------------------------------------------------------------------


def _ask_yes_no(question):
    if TERM is None:
        try:
            return input(f"{question} [y/N] ").strip().lower() in ("y", "yes")
        except EOFError:
            return False
    return TERM.ask_yes_no(question)


def adb_install(device, apk, flavor, build_type, dry_run=False):
    """adb install，带签名冲突 / 降级 / ABI 不匹配 / testOnly 的自动重试。"""
    package = package_id(flavor)
    fallback = universal_apk(flavor, build_type)
    flags = ["-r", "-t"]
    for attempt in range(4):
        cmd = ["-s", device.serial, "install", *flags, str(apk)]
        print()
        print(paint(f"$ adb {' '.join(cmd)}", C.BOLD, C.BLUE))
        sys.stdout.flush()
        if dry_run:
            print(paint("[dry-run] 未执行安装", C.DIM))
            return True
        try:
            result = adb_run(cmd, timeout=300)
        except subprocess.TimeoutExpired:
            print(paint("✗ adb install 超时（设备可能无响应）", C.RED))
            return False
        output = ((result.stdout or "") + (result.stderr or "")).strip()
        if result.returncode == 0 and "Success" in output:
            print(paint(f"✓ 安装成功：{apk.name}", C.GREEN, C.BOLD))
            return True
        print(output or f"(adb 退出码 {result.returncode})")

        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in output or "signatures do not match" in output:
            print(paint("！Debug / Release 签名不同，覆盖安装会被系统拒绝。", C.YELLOW))
            if not _ask_yes_no(f"卸载 {package} 后重装？（会清空该应用数据：登录态、设置、缓存）"):
                return False
            print(paint(f"$ adb -s {device.serial} uninstall {package}", C.BOLD, C.BLUE))
            try:
                uninstall = adb_run(["-s", device.serial, "uninstall", package], timeout=120)
                print(((uninstall.stdout or "") + (uninstall.stderr or "")).strip())
            except Exception as exc:
                print(paint(f"[警告] 卸载失败：{exc}", C.YELLOW))
            continue

        if "INSTALL_FAILED_VERSION_DOWNGRADE" in output and "-d" not in flags:
            print(paint("→ 版本降级被拒，重试时允许降级（-d）。", C.DIM))
            flags.append("-d")
            continue

        if "INSTALL_FAILED_NO_MATCHING_ABIS" in output and fallback and fallback != apk:
            print(paint(f"→ ABI 不匹配，改用通用包 {fallback.name}。", C.DIM))
            apk = fallback
            continue

        if "INSTALL_FAILED_TEST_ONLY" in output and "-t" not in flags:
            flags.append("-t")
            continue

        for code, hint in INSTALL_HINTS.items():
            if code in output:
                print(paint("提示：" + hint, C.YELLOW))
                break
        if attempt < 3:
            print(paint(f"（第 {attempt + 1} 次安装失败）", C.DIM))
    print(paint("✗ 多次重试后仍安装失败。", C.RED, C.BOLD))
    return False


def execute_install(device, flavor, build_type, must_build, dry_run=False):
    """先（可选）构建，再按 ABI 选包安装。"""
    print()
    print(
        paint(
            f"▶ 安装 {TYPE_LABEL[build_type]} · {FLAVOR_LABEL[flavor]} → "
            f"{device.serial}（{device.label}）",
            C.BOLD,
            C.CYAN,
        )
    )
    if must_build:
        if not execute_build(build_type, flavor, dry_run):
            return False

    # dry-run 也真读 ABI（只是读属性，不改设备），这样打印出来的选包结果才可信
    abi = device_abi(device)
    apk = choose_apk(flavor, build_type, abi)
    if apk is None:
        if dry_run:
            apk = apk_dir(flavor, build_type) / f"app-{flavor}-universal-{build_type}.apk"
            print(paint(f"[dry-run] 预计使用：{rel(apk)}", C.DIM))
        else:
            print(paint("✗ 找不到可安装的 APK，构建可能没有产出该渠道 / 类型的包。", C.RED))
            return False
    print(f"  设备 ABI：{abi or '未知'}    选用：{apk.name}")
    return adb_install(device, apk, flavor, build_type, dry_run)


def _device_state_ok(device):
    return device.state == "device"


# ---------------------------------------------------------------------------
# TUI 渲染
# ---------------------------------------------------------------------------


@dataclass
class Item:
    key: str
    label: str
    kind: str = "action"  # action / toggle
    hotkey: str = ""
    value: str = ""
    arg: str = ""
    hint: str = ""


@dataclass
class AppState:
    build_type: str = "debug"
    flavor: str = "standard"
    cursor: int = 2
    devices: list = field(default_factory=list)
    adb_error: str = ""
    status: str = ""
    status_kind: str = "info"
    version_name: str = ""
    version_code: int = 0

    def set_status(self, text, kind="info"):
        self.status = text
        self.status_kind = kind

    def version_hint(self):
        if self.version_name:
            return f"{self.version_name} ({self.version_code})"
        return "读取失败"


def load_version(state):
    """把 build.gradle 里的当前版本读进 state（失败时留空，不抛异常）。"""
    try:
        state.version_code, state.version_name = read_version_info()
    except EnvError:
        state.version_name = ""
        state.version_code = 0


def make_items(state):
    real = sum(1 for d in state.devices if _device_state_ok(d) and d.kind == "device")
    emulators = sum(1 for d in state.devices if _device_state_ok(d) and d.kind == "emulator")
    return [
        Item("toggle_type", "构建类型", kind="toggle", value=TYPE_LABEL[state.build_type]),
        Item("toggle_flavor", "发行渠道", kind="toggle", value=FLAVOR_LABEL[state.flavor]),
        Item("build", "构建 Debug", hotkey="1", arg="debug", hint=task_name(state.flavor, "debug")),
        Item("build", "构建 Release", hotkey="2", arg="release", hint=task_name(state.flavor, "release")),
        Item("build_all", "构建全部", hotkey="a", hint="两渠道 × 两类型"),
        Item(
            "edit_version",
            "版本号 / 版本名",
            hotkey="v",
            hint=f"改 build.gradle：{state.version_hint()}",
        ),
        Item(
            "sync_private",
            "同步私有功能源码",
            hotkey="p",
            hint=f"GitHub 私有仓库 → private/：{private_hint()}",
        ),
        Item(
            "install_device",
            "安装到真机",
            hotkey="3",
            arg="device",
            hint=f"{real} 台可用" if real else "未连接",
        ),
        Item(
            "install_emulator",
            "安装到模拟器",
            hotkey="4",
            arg="emulator",
            hint=f"{emulators} 台可用" if emulators else "未连接",
        ),
        Item("quit", "退出", hotkey="0"),
    ]


def _clip(segs, max_width):
    """把 [(文本, 样式)] 按总显示宽度裁到 max_width。"""
    if sum(dwidth(text) for text, _ in segs) <= max_width:
        return segs
    clipped = []
    used = 0
    for text, style in segs:
        if used >= max_width:
            break
        allow = max_width - used
        text_width = dwidth(text)
        if text_width <= allow:
            clipped.append((text, style))
            used += text_width
        else:
            clipped.append((truncate(text, allow), style))
            used = max_width
            break
    return clipped


def row(segs, width, right=None):
    """渲染一行内容，左侧段落 + 右侧对齐文本，整体补到 width 显示宽度。"""
    right_text, right_style = right if right else ("", "")
    right_width = dwidth(right_text)
    max_left = width - right_width - (1 if right_text else 0)
    segs = _clip(segs, max(1, max_left))
    left_width = sum(dwidth(text) for text, _ in segs)
    out = "".join((style + text + C.RESET) if style else text for text, style in segs)
    out += " " * max(0, width - left_width - right_width)
    if right_text:
        out += (right_style + right_text + C.RESET) if right_style else right_text
    return out


def build_frame(state, width):
    """构建整屏 TUI 文本。"""
    w = max(46, width)
    inner = w - 4
    lines = []
    lines.append("┌" + "─" * (w - 2) + "┐")

    if state.adb_error:
        header_right = ("adb ✗", C.RED)
    else:
        header_right = (f"adb ✓  {len(state.devices)} 台设备", C.DIM)
    lines.append("│ " + row([(f" {TITLE}", C.BOLD + C.CYAN)], inner, header_right) + " │")

    lines.append("├" + "─" * (w - 2) + "┤")

    # 设备行
    dev_segs = [("设备  ", C.BOLD)]
    if state.adb_error:
        dev_segs.append((truncate(state.adb_error, inner - 8), C.RED))
    elif not state.devices:
        dev_segs.append(("未检测到设备（真机查 USB 调试；模拟器按 C 连接）", C.DIM))
    else:
        for device in state.devices:
            if _device_state_ok(device):
                color = C.CYAN if device.kind == "emulator" else C.GREEN
                label = device.label
            else:
                color = C.YELLOW
                label = device.state
            dev_segs.append(("● ", color))
            dev_segs.append((truncate(device.serial, 24), color))
            dev_segs.append((f" {label}   ", C.DIM))
    lines.append("│ " + row(dev_segs, inner) + " │")

    lines.append("├" + "─" * (w - 2) + "┤")

    items = make_items(state)
    for index, item in enumerate(items):
        selected = index == state.cursor
        marker = ("▶ " if selected else "  ", C.CYAN if selected else C.DIM)
        if item.kind == "toggle":
            segs = [
                marker,
                (pad(item.label, 10), C.BOLD + (C.CYAN if selected else "")),
                ("  ‹ ", C.DIM),
                (item.value, C.YELLOW + C.BOLD),
                (" ›", C.DIM),
            ]
            lines.append("│ " + row(segs, inner) + " │")
        elif item.key == "quit":
            segs = [
                marker,
                (item.hotkey + "  ", C.YELLOW + C.BOLD),
                (item.label, C.BOLD if selected else ""),
            ]
            lines.append("│ " + row(segs, inner) + " │")
        else:
            segs = [
                marker,
                (item.hotkey + "  ", C.YELLOW + C.BOLD),
                (pad(item.label, 18), C.BOLD if selected else ""),
            ]
            right = (item.hint, C.DIM) if item.hint else None
            lines.append("│ " + row(segs, inner, right) + " │")

    lines.append("├" + "─" * (w - 2) + "┤")

    if state.status:
        status_style = {"ok": C.GREEN, "err": C.RED}.get(state.status_kind, C.DIM)
        icon = {"ok": "✓", "err": "✗"}.get(state.status_kind, "·")
        status_segs = [(f" {icon} ", status_style), (truncate(state.status, inner - 4), status_style)]
    else:
        status_segs = [(" · ", C.DIM), ("就绪：选择一项操作后按 Enter", C.DIM)]
    lines.append("│ " + row(status_segs, inner) + " │")
    lines.append(
        "│ "
        + row([(" ↑↓ 选择   ←→ 切换   Enter 执行   1-4 直达   A 全构建   V 版本   P 私有源码   R 刷新   C 连模拟器   Q 退出", C.DIM)], inner)
        + " │"
    )
    lines.append("└" + "─" * (w - 2) + "┘")
    return "\n".join(lines)


def build_modal(title, subtitle, options, selected, width):
    """设备选择等临时弹层。"""
    w = max(46, width)
    inner = w - 4
    lines = ["┌" + "─" * (w - 2) + "┐"]
    lines.append("│ " + row([(f" {title}", C.BOLD + C.CYAN)], inner) + " │")
    lines.append("├" + "─" * (w - 2) + "┤")
    if subtitle:
        lines.append("│ " + row([(" " + subtitle, C.DIM)], inner) + " │")
    for index, option in enumerate(options):
        is_selected = index == selected
        segs = [
            ("▶ " if is_selected else "  ", C.CYAN if is_selected else C.DIM),
            (truncate(option, inner - 4), C.BOLD if is_selected else ""),
        ]
        lines.append("│ " + row(segs, inner) + " │")
    lines.append("├" + "─" * (w - 2) + "┤")
    lines.append(
        "│ " + row([(" ↑↓ 选择   Enter 确认   Esc 返回", C.DIM)], inner) + " │"
    )
    lines.append("└" + "─" * (w - 2) + "┘")
    return "\n".join(lines)


class TuiApp:
    def __init__(self, term, state, dry_run=False):
        self.term = term
        self.state = state
        self.dry_run = dry_run
        self.running = True

    # -- 主循环 ------------------------------------------------------------
    def run(self):
        self.term.enter()
        try:
            self.refresh(quiet=True)
            while self.running:
                self.draw()
                key = self.term.read_key()
                self.handle(key)
        finally:
            self.term.leave()

    def draw(self):
        sys.stdout.write("\x1b[H" + build_frame(self.state, terminal_width()) + "\x1b[J")
        sys.stdout.flush()

    def handle(self, key):
        if key is None or key == "unknown":
            return
        aliases = {"j": "down", "k": "up"}
        key = aliases.get(key, key)
        items = make_items(self.state)
        if key in ("q", "esc", "ctrl-c"):
            self.running = False
            return
        if key == "up":
            self.state.cursor = (self.state.cursor - 1) % len(items)
            return
        if key == "down":
            self.state.cursor = (self.state.cursor + 1) % len(items)
            return
        if key == "tab":
            self.state.cursor = (self.state.cursor + 1) % len(items)
            return
        if key in ("left", "right"):
            item = items[self.state.cursor]
            if item.kind == "toggle":
                self.cycle(item, 1 if key == "right" else -1)
            return
        if key == "enter":
            self.activate(items[self.state.cursor])
            return
        if key == "r":
            self.refresh(quiet=True)
            count = len(self.state.devices)
            self.state.set_status(f"已刷新设备：{count} 台在线", "ok" if count else "err")
            return
        if key == "c":
            try:
                connect_emulators()
            except Exception as exc:  # noqa: BLE001 - 连接失败只提示，不退出
                self.state.set_status(f"连接模拟器失败：{exc}", "err")
                return
            self.refresh(quiet=True)
            emulators = [d.serial for d in self.state.devices if d.kind == "emulator"]
            if emulators:
                self.state.set_status(f"模拟器在线：{'、'.join(emulators)}", "ok")
            else:
                self.state.set_status(
                    "没有发现本机模拟器：先启动模拟器，或手动 adb connect <地址>:<端口>", "err"
                )
            return
        for item in items:
            if item.hotkey and key == item.hotkey:
                self.activate(item)
                return

    def activate(self, item):
        self.state.set_status("", "info")
        if item.kind == "toggle":
            self.cycle(item, 1)
            return
        if item.key == "quit":
            self.running = False
        elif item.key == "build":
            self.run_build(item.arg)
        elif item.key == "build_all":
            self.run_build_all()
        elif item.key == "edit_version":
            self.run_edit_version()
        elif item.key == "sync_private":
            self.run_sync_private()
        elif item.key == "install_device":
            self.run_install("device")
        elif item.key == "install_emulator":
            self.run_install("emulator")

    def cycle(self, item, direction):
        if item.key == "toggle_type":
            order = list(TYPES)
            index = order.index(self.state.build_type)
            self.state.build_type = order[(index + direction) % len(order)]
        elif item.key == "toggle_flavor":
            order = list(FLAVORS)
            index = order.index(self.state.flavor)
            self.state.flavor = order[(index + direction) % len(order)]
        self.state.set_status("", "info")

    # -- 刷新设备 ----------------------------------------------------------
    def refresh(self, quiet=False):
        try:
            self.state.devices = list_devices()
            self.state.adb_error = ""
            note = ""
            if AUTO_CONNECTED:
                note = f"（自动连接 {'、'.join(AUTO_CONNECTED)}）"
            if not quiet or note:
                self.state.set_status(f"已连接 {len(self.state.devices)} 台设备{note}", "ok")
        except EnvError as exc:
            self.state.devices = []
            self.state.adb_error = str(exc)
        except subprocess.TimeoutExpired:
            self.state.devices = []
            self.state.adb_error = "adb devices 执行超时"
        except Exception as exc:  # noqa: BLE001 - 设备读取失败不应让 TUI 崩溃
            self.state.devices = []
            self.state.adb_error = f"读取设备失败：{exc}"

    # -- 动作 --------------------------------------------------------------
    def run_build(self, build_type):
        state = self.state
        self.term.leave()
        ok = execute_build(build_type, state.flavor, self.dry_run)
        self.term.wait_return()
        self.term.enter()
        self.refresh(quiet=True)
        if ok:
            state.set_status(
                f"{TYPE_LABEL[build_type]} · {FLAVOR_LABEL[state.flavor]} 构建完成", "ok"
            )
        else:
            state.set_status(f"{TYPE_LABEL[build_type]} 构建失败，详见上方输出", "err")

    def run_build_all(self):
        state = self.state
        self.term.leave()
        ok = execute_build_all(self.dry_run)
        self.term.wait_return()
        self.term.enter()
        self.refresh(quiet=True)
        if ok:
            state.set_status("全部构建完成（普通版 + 共存版 × Debug + Release）", "ok")
        else:
            state.set_status("全量构建有失败，详见上方输出", "err")

    def run_edit_version(self):
        state = self.state
        self.term.leave()
        ok, message = prompt_version_edit(self.term, self.dry_run)
        self.term.wait_return()
        self.term.enter()
        load_version(state)
        self.refresh(quiet=True)
        state.set_status(message, "ok" if ok else "err")

    def run_sync_private(self):
        state = self.state
        self.term.leave()
        try:
            ok, message = sync_private_feature_interactive(self.dry_run)
        except Exception as exc:  # noqa: BLE001 - 拉取失败只提示，不能让 TUI 崩
            ok, message = False, f"同步私有源码失败：{exc}"
        finally:
            self.term.wait_return()
            self.term.enter()
        state.set_status(message, "ok" if ok else "err")

    def run_install(self, target_kind):
        state = self.state
        self.refresh(quiet=True)
        label = "真机" if target_kind == "device" else "模拟器"
        usable = [
            device
            for device in state.devices
            if _device_state_ok(device) and device.kind == target_kind
        ]
        if not usable and target_kind == "emulator":
            # MuMu / 雷电这类模拟器不自己出现，要 adb connect 本机端口
            if connect_emulators():
                self.refresh(quiet=True)
                usable = [
                    device
                    for device in state.devices
                    if _device_state_ok(device) and device.kind == "emulator"
                ]
        if not usable:
            others = [device.label for device in state.devices if _device_state_ok(device)]
            if others:
                kinds = "、".join(sorted(set(others)))
                state.set_status(f"未检测到可用的{label}（在线的只有{kinds}）", "err")
            elif any(device.state == "unauthorized" for device in state.devices):
                state.set_status("设备未授权：请在设备上允许 USB 调试", "err")
            else:
                state.set_status(f"未检测到{label}，请先连接或启动", "err")
            return

        device = usable[0]
        if len(usable) > 1:
            options = [f"{d.serial}  {d.model or d.label}".strip() for d in usable]
            index = self.choose(f"选择{label}", f"共 {len(usable)} 台可用", options)
            if index is None:
                return
            device = usable[index]

        must_build, reason = build_required(state.flavor, state.build_type)
        self.term.leave()
        if must_build:
            print(paint(f"（{reason}，先构建）", C.DIM))
        ok = execute_install(device, state.flavor, state.build_type, must_build, self.dry_run)
        self.term.wait_return()
        self.term.enter()
        self.refresh(quiet=True)
        if ok:
            state.set_status(
                f"已安装 {TYPE_LABEL[state.build_type]} · {FLAVOR_LABEL[state.flavor]} → {device.serial}",
                "ok",
            )
        else:
            state.set_status("安装失败，详见上方输出", "err")

    def choose(self, title, subtitle, options):
        selected = 0
        while True:
            frame = build_modal(title, subtitle, options, selected, terminal_width())
            sys.stdout.write("\x1b[H" + frame + "\x1b[J")
            sys.stdout.flush()
            key = self.term.read_key()
            if key == "up":
                selected = (selected - 1) % len(options)
            elif key == "down":
                selected = (selected + 1) % len(options)
            elif key == "enter":
                return selected
            elif key in ("esc", "q", "ctrl-c"):
                return None


# ---------------------------------------------------------------------------
# 行式菜单（非 TTY / 老控制台）
# ---------------------------------------------------------------------------


def print_banner():
    print(paint("=" * 62, C.DIM))
    print(paint(" " + TITLE, C.BOLD, C.CYAN))
    print(paint("=" * 62, C.DIM))


def run_line_mode(state, dry_run=False):
    while True:
        print()
        print(
            paint(
                f" 当前：{TYPE_LABEL[state.build_type]} · {FLAVOR_LABEL[state.flavor]}"
                f"    版本：{state.version_hint()}",
                C.BOLD,
            )
        )
        online = [d for d in state.devices if _device_state_ok(d)]
        if state.adb_error:
            print(paint(f" 设备：{state.adb_error}", C.RED))
        elif online:
            print(
                " 设备："
                + "，".join(f"{d.serial}({d.label})" for d in online)
            )
        else:
            print(paint(" 设备：未检测到设备", C.DIM))
        print(f" 私有功能源码：{private_hint()}")
        print()
        print("  [1] 构建 Debug            [2] 构建 Release")
        print("  [a] 构建全部（两渠道 × 两类型）")
        print("  [v] 修改版本号 / 版本名   [p] 同步私有功能源码")
        print("  [3] 安装到真机            [4] 安装到模拟器")
        print("  [t] 切换构建类型          [f] 切换发行渠道")
        print("  [c] 连接模拟器            [d] 刷新设备    [0] 退出")
        try:
            raw = input(paint("请选择> ", C.BOLD)).strip().lower()
        except EOFError:
            return

        if raw in ("0", "q", "quit", "exit"):
            return
        if raw == "t":
            state.build_type = "release" if state.build_type == "debug" else "debug"
            continue
        if raw == "f":
            state.flavor = "coexist" if state.flavor == "standard" else "standard"
            continue
        if raw == "d":
            try:
                state.devices = list_devices()
                state.adb_error = ""
                print(paint(f"  已刷新：{len(state.devices)} 台", C.DIM))
            except EnvError as exc:
                state.devices = []
                state.adb_error = str(exc)
                print(paint(f"  {exc}", C.RED))
            continue
        if raw == "c":
            connect_emulators()
            try:
                state.devices = list_devices()
                state.adb_error = ""
            except EnvError as exc:
                state.devices = []
                state.adb_error = str(exc)
                print(paint(f"  {exc}", C.RED))
            emulators = [d.serial for d in state.devices if d.kind == "emulator"]
            if emulators:
                print(paint(f"  模拟器在线：{'、'.join(emulators)}", C.GREEN))
            else:
                print(paint("  没有发现本机模拟器（先启动模拟器，或 adb connect <地址>:<端口>）", C.DIM))
            continue
        if raw == "1":
            execute_build("debug", state.flavor, dry_run)
            continue
        if raw == "2":
            execute_build("release", state.flavor, dry_run)
            continue
        if raw == "a":
            execute_build_all(dry_run)
            continue
        if raw == "v":
            prompt_version_edit(TERM, dry_run)
            load_version(state)
            continue
        if raw == "p":
            try:
                ok, message = sync_private_feature_interactive(dry_run)
            except Exception as exc:  # noqa: BLE001 - 拉取失败只提示
                ok, message = False, f"同步私有源码失败：{exc}"
            print(paint(f"  {message}", C.GREEN if ok else C.RED))
            continue
        if raw in ("3", "4"):
            target_kind = "device" if raw == "3" else "emulator"
            label = "真机" if target_kind == "device" else "模拟器"
            usable = [
                d
                for d in state.devices
                if _device_state_ok(d) and d.kind == target_kind
            ]
            if not usable and target_kind == "emulator":
                # MuMu / 雷电这类模拟器要 adb connect 才会出现
                if connect_emulators():
                    try:
                        state.devices = list_devices()
                        state.adb_error = ""
                    except EnvError as exc:
                        state.devices = []
                        state.adb_error = str(exc)
                    usable = [
                        d
                        for d in state.devices
                        if _device_state_ok(d) and d.kind == "emulator"
                    ]
            if not usable:
                print(paint(f"  未检测到{label}。", C.RED))
                continue
            device = usable[0]
            if len(usable) > 1:
                for index, candidate in enumerate(usable, 1):
                    print(f"  [{index}] {candidate.serial}  {candidate.model or candidate.label}")
                try:
                    choice = int(input(paint("设备编号> ", C.BOLD)).strip()) - 1
                except (ValueError, EOFError):
                    print("  已取消")
                    continue
                if not 0 <= choice < len(usable):
                    print(paint("  编号无效", C.RED))
                    continue
                device = usable[choice]
            must_build, reason = build_required(state.flavor, state.build_type)
            if must_build:
                print(paint(f"  {reason}，先构建。", C.DIM))
            execute_install(device, state.flavor, state.build_type, must_build, dry_run)
            continue
        print(paint("  无效的选项", C.YELLOW))


# ---------------------------------------------------------------------------
# 命令行入口
# ---------------------------------------------------------------------------

TERM = None


def cmd_list_devices():
    try:
        devices = list_devices()
    except EnvError as exc:
        print(paint(f"[错误] {exc}", C.RED))
        return 2
    if not devices:
        print("未检测到设备。")
        return 0
    print(f"{'SERIAL':<24} {'STATE':<14} {'TYPE':<6} {'MODEL':<22} ABI")
    for device in devices:
        abi = device_abi(device) if _device_state_ok(device) else "-"
        print(
            f"{device.serial:<24} {device.state:<14} {device.label:<6} "
            f"{device.model or '-':<22} {abi}"
        )
    return 0


def cmd_list_apks():
    for flavor in FLAVORS:
        for build_type in TYPES:
            apks = list_apks(flavor, build_type)
            print(paint(f"[{FLAVOR_LABEL[flavor]} · {TYPE_LABEL[build_type]}]", C.BOLD))
            if not apks:
                print(paint("  （未构建）", C.DIM))
                continue
            for apk in apks:
                stat = apk.stat()
                print(f"  {rel(apk):<58} {human_size(stat.st_size):>9}")
    return 0


def cmd_list_version():
    try:
        code, name = read_version_info()
    except EnvError as exc:
        print(paint(f"[错误] {exc}", C.RED))
        return 2
    print(f"{name} ({code})")
    if not anchor_present(name):
        print(
            paint(
                f"[提示] {rel(STRINGS_XML)} 里没有【{name} 本次更新】锚点，check / CI 会失败",
                C.YELLOW,
            )
        )
    return 0


def cmd_set_version(args):
    try:
        change = apply_version(
            args.version_name, args.version_code, args.dry_run, args.sync_anchor
        )
    except EnvError as exc:
        print(paint(f"[错误] {exc}", C.RED))
        return 2
    except OSError as exc:
        print(paint(f"[错误] 写 {rel(BUILD_GRADLE)} 失败：{exc}", C.RED))
        return 2
    if not change.ok:
        print(paint(f"[错误] {change.error}", C.RED))
        return 2
    print_version_change(change, args.dry_run)
    return 0


def cmd_build(args):
    ok = execute_build(args.type, args.flavor, args.dry_run)
    return 0 if ok else 1


def cmd_build_all(args):
    ok = execute_build_all(args.dry_run)
    return 0 if ok else 1


def cmd_install(args):
    try:
        devices = list_devices()
    except EnvError as exc:
        print(paint(f"[错误] {exc}", C.RED))
        return 2
    target_kind = "emulator" if args.target == "emulator" else "device"
    label = "模拟器" if target_kind == "emulator" else "真机"
    usable = [d for d in devices if _device_state_ok(d) and d.kind == target_kind]
    if not usable and target_kind == "emulator":
        # MuMu / 雷电这类模拟器要 adb connect 才会出现在设备列表里
        if connect_emulators():
            try:
                devices = list_devices()
            except EnvError as exc:
                print(paint(f"[错误] {exc}", C.RED))
                return 2
            usable = [d for d in devices if _device_state_ok(d) and d.kind == "emulator"]
    if not usable:
        print(paint(f"[错误] 未检测到可用的{label}。", C.RED))
        if devices:
            print("  当前设备：" + "，".join(f"{d.serial}({d.state})" for d in devices))
        return 2
    device = usable[0]
    if len(usable) > 1:
        print(paint(f"[提示] 检测到 {len(usable)} 台{label}，使用第一台 {device.serial}。", C.YELLOW))
    must_build, reason = build_required(args.flavor, args.type)
    if must_build:
        print(paint(f"[提示] {reason}，先构建。", C.DIM))
    ok = execute_install(device, args.flavor, args.type, must_build, args.dry_run)
    return 0 if ok else 1


def cmd_sync_private(args):
    """非交互同步私有功能源码：先把 --private-* 落进 local.properties，再拉取。"""
    try:
        saved = save_private_settings(args)
    except OSError as exc:
        print(paint(f"[错误] 写 {rel(LOCAL_PROPS)} 失败：{exc}", C.RED))
        return 2
    if saved:
        print(paint(f"  已写入 {rel(LOCAL_PROPS)}：{'、'.join(saved)}", C.DIM))
    if not args.sync_private:
        # 只给了 --private-repo / --private-token：写配置就结束，不拉代码
        print(paint("  配置已保存。要立刻拉取私有源码，加 --sync-private。", C.DIM))
        return 0
    if not private_setting(PRIVATE_REPO_KEY):
        print(
            paint(
                f"[错误] 还没有配置私有仓库：加 --private-repo <owner/repo>，"
                f"或在环境变量 / local.properties 里设 {PRIVATE_REPO_KEY}。",
                C.RED,
            )
        )
        return 2
    try:
        ok, message = sync_private_feature(args.dry_run, force=args.private_force)
    except Exception as exc:  # noqa: BLE001 - 把意外统一成一句可读错误
        ok, message = False, f"同步私有源码失败：{exc}"
    print(paint(("  " if ok else "[错误] ") + message, C.GREEN if ok else C.RED))
    return 0 if ok else 1


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        prog="build_tui.py",
        description="RE:哔哩终端 构建 / 安装 TUI 工具（不带参数即进入交互界面）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            "  python build_tui.py\n"
            "  python build_tui.py --list-devices\n"
            "  python build_tui.py --build --type release --flavor coexist\n"
            "  python build_tui.py --build-all          # 两渠道 × 两类型一次构建\n"
            "  python build_tui.py --install --target emulator --type debug --flavor standard\n"
            "  python build_tui.py --list-version\n"
            "  python build_tui.py --version-name 26.10.20 --version-code 2610200 --sync-anchor\n"
            "  python build_tui.py --sync-private --private-repo owner/repo --private-token XXX\n"
        ),
    )
    parser.add_argument("--build", action="store_true", help="非交互：构建一次")
    parser.add_argument(
        "--build-all",
        action="store_true",
        help="非交互：一次构建全部四个变体（普通版 + 共存版 × Debug + Release）",
    )
    parser.add_argument("--install", action="store_true", help="非交互：安装一次（缺包会先构建）")
    parser.add_argument(
        "--target",
        choices=("device", "emulator"),
        default="device",
        help="安装目标：真机 / 模拟器（默认真机）",
    )
    parser.add_argument("--type", choices=TYPES, default="debug", help="构建类型（默认 debug）")
    parser.add_argument("--flavor", choices=FLAVORS, default="standard", help="发行渠道（默认 standard）")
    parser.add_argument("--list-devices", action="store_true", help="列出 adb 设备")
    parser.add_argument(
        "--connect",
        action="store_true",
        help="只尝试连接本机常见模拟器端口（MuMu / 雷电 / 夜神），然后列出设备",
    )
    parser.add_argument("--list-apks", action="store_true", help="列出已构建的 APK")
    parser.add_argument("--list-version", action="store_true", help="打印当前 versionName / versionCode")
    parser.add_argument(
        "--version-name",
        default=None,
        help="非交互：改 versionName（只动 app/build.gradle 那一行）",
    )
    parser.add_argument(
        "--version-code",
        type=int,
        default=None,
        help="非交互：改 versionCode（正整数）",
    )
    parser.add_argument(
        "--sync-anchor",
        action="store_true",
        help="改版本名时同时把 strings.xml 的【旧版本 本次更新】锚点一起改掉"
        "（verifyVersionConsistency 要求，不同步则 check / CI 失败）",
    )
    parser.add_argument("--no-tui", action="store_true", help="强制使用行式菜单（不用全屏 TUI）")
    parser.add_argument(
        "--sync-private",
        action="store_true",
        help="非交互：从 GitHub 私有仓库同步私有功能源码到 private/（构建前会提示状态）",
    )
    parser.add_argument(
        "--private-repo",
        default=None,
        help="私有仓库 owner/repo（写入 local.properties 的 " + PRIVATE_REPO_KEY + "）",
    )
    parser.add_argument(
        "--private-token",
        default=None,
        help="访问私有仓库的只读 token（写入 local.properties 的 "
        + PRIVATE_TOKEN_KEY
        + "，不会回显）",
    )
    parser.add_argument(
        "--private-branch",
        default=None,
        help="私有仓库分支（写入 local.properties 的 " + PRIVATE_BRANCH_KEY + "，默认分支可留空）",
    )
    parser.add_argument(
        "--private-force",
        action="store_true",
        help="private/ 已存在时也强制用私有仓库的最新内容覆盖",
    )
    parser.add_argument("--dry-run", action="store_true", help="只打印将执行的命令，不真正执行")
    return parser.parse_args(argv)


def main(argv=None):
    global TERM, USE_ANSI
    args = parse_args(argv)
    if args.build and args.install:
        print("--build 与 --install 不能同时使用")
        return 2
    if args.build_all and (args.build or args.install):
        print("--build-all 不能与 --build / --install 同时使用")
        return 2

    TERM = Term(force_tui=False if args.no_tui else None)
    USE_ANSI = bool(sys.stdout.isatty() and (os.name != "nt" or TERM._vt))
    try:
        if args.list_version:
            return cmd_list_version()
        if args.version_name is not None or args.version_code is not None:
            code = cmd_set_version(args)
            if code != 0:
                return code
            # 只改版本就到此为止；带构建 / 安装参数则继续往下走
            if not (args.build or args.build_all or args.install):
                return 0
        if args.sync_private or args.private_repo is not None or args.private_token is not None:
            code = cmd_sync_private(args)
            if code != 0 or not (args.build or args.build_all or args.install):
                return code
        if args.connect:
            connect_emulators()
            devices = list_devices(auto_connect=False)
            emulators = [
                device.serial
                for device in devices
                if device.state == "device" and device.kind == "emulator"
            ]
            if emulators:
                print(paint(f"  模拟器在线：{'、'.join(emulators)}", C.GREEN))
            else:
                print(paint("没有发现本机模拟器（先启动模拟器，或手动 adb connect <地址>:<端口>）", C.DIM))
            return cmd_list_devices()
        if args.list_devices:
            return cmd_list_devices()
        if args.list_apks:
            return cmd_list_apks()
        if args.build_all:
            return cmd_build_all(args)
        if args.build:
            return cmd_build(args)
        if args.install:
            return cmd_install(args)

        state = AppState(build_type=args.type, flavor=args.flavor)
        load_version(state)
        if TERM.tui:
            TuiApp(TERM, state, args.dry_run).run()
            return 0

        print_banner()
        try:
            state.devices = list_devices()
        except EnvError as exc:
            state.adb_error = str(exc)
            print(paint(f"[警告] {exc}", C.YELLOW))
        run_line_mode(state, args.dry_run)
        return 0
    except EnvError as exc:
        print(paint(f"[错误] {exc}", C.RED))
        return 2
    except KeyboardInterrupt:
        print()
        return 130
    finally:
        if TERM is not None:
            TERM.cleanup()


if __name__ == "__main__":
    sys.exit(main())
