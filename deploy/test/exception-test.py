# -*- coding: utf-8 -*-
"""
异常状态码复验脚本。

【为什么需要单独一个脚本】
    接口测试（api-test.py）覆盖的是"正常路径 + 少量鉴权异常"，
    它**不覆盖各业务模块的校验失败路径**。而系统测试中的缺陷
    （IT-02 登录失败返回 500）恰恰出在这些路径上。

    本脚本逐条验证"业务校验失败"与"资源不存在"时的 HTTP 状态码是否正确：
        401 认证失败 / 400 业务校验失败 / 404 资源不存在 / 200 正常对照

【背景】项目原先所有业务校验失败都返回 500（31 处 throw 中只有 1 处被正确处理）。
    修复方案见 common/GlobalExceptionHandler.java：
        业务异常 → 400 / 认证异常 → 401 / 资源不存在 → 404
        服务端内部错误不拦截，保持 500（如实暴露故障）

    修复过程中还发现：第一轮替换漏掉了 `.orElseThrow(() -> new XxxException(...))`
    这种写法，导致"下发不存在的任务"仍返回 500 —— 本脚本的"资源不存在"分组
    就是为了守住这类漏网。

用法（在 deploy\\test 目录下）：
    python exception-test.py                       # 打印结果
    python exception-test.py -o 异常复验记录.md     # 同时写 Markdown

依赖：Python 3（标准库），需要系统处于运行状态。
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost"
RESULTS = []


def record(cid, title, method, path, expect, actual, ok, msg=""):
    RESULTS.append({"id": cid, "title": title, "method": method, "path": path,
                    "expect": expect, "actual": actual, "ok": ok, "msg": msg})
    print("  %s %-6s %-24s 期望 %-3d 实际 %-3d  %s" % (
        "\033[32m[PASS]\033[0m" if ok else "\033[31m[FAIL]\033[0m",
        cid, title, expect, actual, msg[:36]))


def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=20) as r:
            return r.status, r.read().decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "ignore")
    except Exception as e:
        return 0, str(e)


def jget(text, key, default=""):
    """安全取 JSON 字段 —— 响应非 JSON（如服务未启动）时返回默认值，不抛异常。"""
    try:
        return json.loads(text or "{}").get(key, default)
    except Exception:
        return default


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--output", default="")
    args = ap.parse_args()

    print()
    print("=" * 78)
    print("  异常状态码复验  ——  %s" % BASE)
    print("=" * 78)

    st, b = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
    admin = jget(b, "token")
    if not admin:
        print()
        print("  [无法开始] 管理员登录失败（HTTP %d）。请检查：" % st)
        print("    ① Docker Desktop 是否已启动")
        print("    ② 容器是否在运行： docker compose --profile sim up -d")
        print("    ③ 网关是否可达：   curl http://localhost/")
        print()
        print("  原始响应： %s" % (b[:120] if b else "(空)"))
        return 1

    # ---------------- 认证失败 → 401 ----------------
    print("\n【认证失败】应返回 401\n")
    for cid, title, body in [
        ("EX-01", "登录-密码错误", {"username": "operator", "password": "wrong-password"}),
        ("EX-02", "登录-用户不存在", {"username": "no-such-user", "password": "x"}),
    ]:
        st, resp = call("POST", "/api/auth/login", body)
        msg = jget(resp, "message")
        record(cid, title, "POST", "/api/auth/login", 401, st, st == 401, msg)

    # ---------------- 业务校验失败 → 400 ----------------
    print("\n【业务校验失败】应返回 400\n")
    cases_400 = [
        ("EX-03", "新增用户-用户名重复", "POST", "/api/users",
         {"username": "admin", "password": "123456", "realName": "x", "role": "OPERATOR"}),
        ("EX-04", "新增用户-密码太短", "POST", "/api/users",
         {"username": "ex-tmp-01", "password": "123", "realName": "x", "role": "OPERATOR"}),
        ("EX-05", "删除内置管理员", "DELETE", "/api/users/admin", None),
        ("EX-06", "修改不存在的用户", "PUT", "/api/users/no-such-user", {"realName": "x"}),
        ("EX-07", "新增设备-缺设备类型", "POST", "/api/devices", {}),
        ("EX-08", "下发不存在的任务", "POST", "/api/tasks/NOT-EXIST/dispatch", None),
        ("EX-09", "取消不存在的任务", "POST", "/api/tasks/NOT-EXIST/cancel", None),
        ("EX-10", "任务详情-不存在", "GET", "/api/tasks/NOT-EXIST", None),
    ]
    for cid, title, m, p, body in cases_400:
        st, resp = call(m, p, body, admin)
        msg = jget(resp, "message")
        record(cid, title, m, p, 400, st, st == 400, msg)

    # ---------------- 资源不存在 → 404 ----------------
    print("\n【资源不存在】应返回 404\n")
    for cid, title, m, p in [
        ("EX-11", "设备详情-不存在", "GET", "/api/devices/NOT-EXIST"),
        ("EX-12", "影像下载-不存在", "GET", "/api/media/NOT-EXIST/download"),
    ]:
        st, resp = call(m, p, None, admin)
        msg = jget(resp, "message")
        record(cid, title, m, p, 404, st, st == 404, msg)

    # ---------------- 正常路径对照 → 200 ----------------
    print("\n【正常路径对照】应返回 200\n")
    st, _ = call("GET", "/api/devices/UAV-001", None, admin)
    record("EX-13", "设备详情-存在", "GET", "/api/devices/UAV-001", 200, st, st == 200, "正常对照")

    # ---------------- 汇总 ----------------
    passed = sum(1 for r in RESULTS if r["ok"])
    total = len(RESULTS)
    print()
    print("=" * 78)
    print("  合计 %d 条，通过 %d，失败 %d" % (total, passed, total - passed))
    print("=" * 78)
    if total - passed:
        print("\n  未通过：")
        for r in RESULTS:
            if not r["ok"]:
                print("    %s %s → 期望 %d 实际 %d  %s"
                      % (r["id"], r["title"], r["expect"], r["actual"], r["msg"]))
    print()

    if args.output:
        lines = [
            "# 异常状态码复验记录（自动生成）", "",
            "> 执行时间：%s" % time.strftime("%Y-%m-%d %H:%M:%S"),
            "> 执行方式：`deploy/test/exception-test.py`", "",
            "| 编号 | 场景 | 方法 | 路径 | 预期 | 实际 | 响应消息 | 状态 |",
            "| :---: | :--- | :--- | :--- | ---: | ---: | :--- | :---: |",
        ]
        for r in RESULTS:
            lines.append("| %s | %s | %s | `%s` | %d | %d | %s | %s |" % (
                r["id"], r["title"], r["method"], r["path"],
                r["expect"], r["actual"], r["msg"] or "—",
                "✅" if r["ok"] else "❌"))
        lines += ["", "**合计 %d 条，通过 %d，失败 %d**" % (total, passed, total - passed), ""]
        with open(args.output, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        print("  记录已写出：%s\n" % args.output)

    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
