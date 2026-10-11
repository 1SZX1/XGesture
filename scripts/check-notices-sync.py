#!/usr/bin/env python3
"""校验两份第三方声明文件是否逐行一致。

为什么需要这个脚本
------------------
`THIRD_PARTY_NOTICES.md` 有两份副本，内容必须一致：

    THIRD_PARTY_NOTICES.md                              ← 仓库里给人看的
    app/src/main/assets/licenses/third_party_notices.md  ← App 内「开源许可」页读的
                                                          （ThirdPartyNoticesScreen）

它们**没有任何同步机制**——既没有生成步骤，也没有校验。真事（两次都发生过）：

  1. 加 QuickDash 条目时只改了仓库根那份 → 那份声明在 App 里**根本看不到**；
  2. 补 Quick Search 的搜索面板归属时，又一次只改了一份。

两次都是事后才发现。所以把"两份必须一致"变成一条可执行检查。

用法
----
    python scripts/check-notices-sync.py            # 校验；不一致时打印差异
    python scripts/check-notices-sync.py --quiet    # 只返回退出码

退出码
------
    0 = 两份一致
    1 = 两份不一致（差异已打印）
    2 = 文件缺失等环境错误

说明：比较前会把 CRLF 收成 LF，避免行尾差异掩盖真正的内容差异（行尾由
`scripts/normalize-eol.py` 单独负责）。
"""

from __future__ import annotations

import argparse
import difflib
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ROOT_COPY = REPO_ROOT / "THIRD_PARTY_NOTICES.md"
ASSET_COPY = REPO_ROOT / "app" / "src" / "main" / "assets" / "licenses" / "third_party_notices.md"

MAX_REPORTED_DIFF_LINES = 24


def read_lines(path: Path) -> list[str]:
    """按行读取；比较前统一行尾（只影响比较，不改文件）。"""
    data = path.read_bytes().replace(b"\r\n", b"\n")
    return data.decode("utf-8", "replace").split("\n")


def main() -> int:
    # 输出容错：diff 会打印文件内容，Windows 控制台默认 GBK 编码，
    # 遇到编码不了的字符不该让整个检查崩掉（真机踩过：打印 ✅ 直接 UnicodeEncodeError）。
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")

    parser = argparse.ArgumentParser(description="校验两份第三方声明文件是否逐行一致")
    parser.add_argument("--quiet", action="store_true", help="只返回退出码，不打印细节")
    args = parser.parse_args()

    missing = [path for path in (ROOT_COPY, ASSET_COPY) if not path.is_file()]
    if missing:
        for path in missing:
            print(f"[错误] 找不到 {path}", file=sys.stderr)
        return 2

    root_lines = read_lines(ROOT_COPY)
    asset_lines = read_lines(ASSET_COPY)
    root_rel = ROOT_COPY.relative_to(REPO_ROOT)
    asset_rel = ASSET_COPY.relative_to(REPO_ROOT)

    if root_lines == asset_lines:
        if not args.quiet:
            print(f"[ok] 两份第三方声明一致（{len(root_lines) - 1} 行）")
            print(f"     {root_rel}")
            print(f"     {asset_rel}")
        return 0

    if not args.quiet:
        print("[!!] 两份第三方声明文件不一致：")
        print(f"     {root_rel}  {len(root_lines) - 1} 行")
        print(f"     {asset_rel}  {len(asset_lines) - 1} 行")
        print()
        diff = list(
            difflib.unified_diff(
                root_lines,
                asset_lines,
                fromfile=str(root_rel),
                tofile=str(asset_rel),
                lineterm="",
                n=1,
            )
        )
        for line in diff[:MAX_REPORTED_DIFF_LINES]:
            print(f"     {line}")
        if len(diff) > MAX_REPORTED_DIFF_LINES:
            print(f"     ...另有 {len(diff) - MAX_REPORTED_DIFF_LINES} 行差异未显示")
        print()
        print("请把同样的改动应用到两份文件（保持逐行一致）。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
