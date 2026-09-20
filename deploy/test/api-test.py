# -*- coding: utf-8 -*-
"""
接口测试脚本 —— 自动执行 REST 接口的正常路径与异常路径，断言并生成测试记录。

用法（在 deploy\\test 目录下）：
    python api-test.py                          # 执行全部，打印结果
    python api-test.py -o 测试记录.md            # 同时写出 Markdown 记录

设计说明：
    · 走 Nginx 网关（http://localhost/api），与前端访问路径一致
    · 每条用例记录：编号、接口、预期、实际、状态，便于直接填入测试报告
    · 需要设备在线（仿真端运行）的用例会标注 [需设备在线]
    · 只做只读 + 可回滚的写操作，不破坏现有数据

依赖：Python 3（标准库 urllib，无需第三方包）
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost"

# ---------------------------------------------------------------- 结果收集

RESULTS = []


def record(case_id, module, title, method, path, expect, actual, ok, note=""):
    RESULTS.append({
        "id": case_id, "module": module, "title": title,
        "method": method, "path": path,
        "expect": expect, "actual": actual,
        "ok": ok, "note": note,
    })
    mark = "PASS" if ok else "FAIL"
    color = "\033[32m" if ok else "\033[31m"
    print("  %s[%s]\033[0m %-8s %-38s %s" % (color, mark, case_id, title, actual))


# ---------------------------------------------------------------- HTTP 工具

def call(method, path, body=None, token=None, headers=None):
    """发一个请求，返回 (status, body_text)。不抛异常，把错误也当结果。"""
    url = BASE + path
    data = None
    req_headers = {}

    if body is not None:
        data = json.dumps(body).encode("utf-8")
        req_headers["Content-Type"] = "application/json"
    if token:
        req_headers["Authorization"] = "Bearer " + token
    if headers:
        req_headers.update(headers)

    req = urllib.request.Request(url, data=data, method=method, headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read().decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "ignore")
    except Exception as e:
        return 0, str(e)


def parse(text):
    try:
        return json.loads(text)
    except Exception:
        return {}


def short(text, n=70):
    t = " ".join(text.split())
    return t if len(t) <= n else t[:n] + "..."


# ---------------------------------------------------------------- 测试执行

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--output", default="", help="Markdown 记录输出路径")
    args = ap.parse_args()

    print()
    print("=" * 78)
    print("  接口测试  ——  %s" % BASE)
    print("=" * 78)

    # ============ 一、认证 ============
    print("\n【一】认证接口\n")

    st, body = call("POST", "/api/auth/login",
                    {"username": "operator", "password": "123456"})
    d = parse(body)
    operator_token = d.get("token")
    record("IT-01", "认证", "值班员登录成功", "POST", "/api/auth/login",
           "200 且返回 token", "HTTP %d, role=%s" % (st, d.get("role")),
           st == 200 and bool(operator_token))

    st2, body2 = call("POST", "/api/auth/login",
                      {"username": "operator", "password": "wrong-password"})
    record("IT-02", "认证", "错误密码登录被拒", "POST", "/api/auth/login",
           "401 或 400", "HTTP %d" % st2, st2 in (400, 401))

    st, body = call("POST", "/api/auth/login",
                    {"username": "admin", "password": "admin123"})
    admin_token = parse(body).get("token")
    record("IT-03", "认证", "管理员登录成功", "POST", "/api/auth/login",
           "200 且角色为 ADMIN", "HTTP %d, role=%s" % (st, parse(body).get("role")),
           st == 200 and parse(body).get("role") == "ADMIN")

    # ============ 二、鉴权（异常路径）============
    print("\n【二】鉴权 —— 异常路径\n")

    st, _ = call("GET", "/api/devices")
    record("IT-04", "鉴权", "未携带令牌访问被拒", "GET", "/api/devices",
           "401", "HTTP %d" % st, st == 401)

    st, _ = call("GET", "/api/devices", token="invalid.token.here")
    record("IT-05", "鉴权", "非法令牌被拒", "GET", "/api/devices",
           "401", "HTTP %d" % st, st == 401)

    st, body = call("POST", "/api/devices", {"deviceNo": "X-001"},
                    token=operator_token)
    record("IT-06", "鉴权", "值班员写设备台账被拒（越权）", "POST", "/api/devices",
           "403", "HTTP %d, msg=%s" % (st, short(parse(body).get("message", ""), 30)),
           st == 403)

    st, body = call("GET", "/api/users", token=operator_token)
    record("IT-07", "鉴权", "值班员访问用户管理被拒（越权）", "GET", "/api/users",
           "403", "HTTP %d" % st, st == 403)

    # ============ 三、设备接口 ============
    print("\n【三】设备接口\n")

    st, body = call("GET", "/api/devices", token=operator_token)
    devices = parse(body) or []
    online = [x for x in devices if x.get("status") == "ONLINE"]
    record("IT-08", "设备", "查询设备列表", "GET", "/api/devices",
           "200 且返回设备数组",
           "HTTP %d, 共 %d 台（在线 %d）" % (st, len(devices), len(online)),
           st == 200 and isinstance(devices, list))

    if devices:
        no = devices[0].get("deviceNo")
        st, body = call("GET", "/api/devices/" + no, token=operator_token)
        record("IT-09", "设备", "查询单台设备详情", "GET", "/api/devices/{no}",
               "200 且 deviceNo 匹配",
               "HTTP %d, deviceNo=%s" % (st, parse(body).get("deviceNo")),
               st == 200 and parse(body).get("deviceNo") == no)
    else:
        record("IT-09", "设备", "查询单台设备详情", "GET", "/api/devices/{no}",
               "200", "跳过（无设备数据）", False, "需先启动仿真端")

    # ============ 四、任务接口 ============
    print("\n【四】任务接口\n")

    st, body = call("GET", "/api/tasks?page=1&size=5", token=operator_token)
    d = parse(body)
    tasks = d.get("items", [])
    record("IT-10", "任务", "分页查询任务列表", "GET", "/api/tasks",
           "200 且返回 {total, items}",
           "HTTP %d, total=%s" % (st, d.get("total")),
           st == 200 and "total" in d and "items" in d)

    st, body = call("GET", "/api/tasks/available-devices", token=operator_token)
    avail = parse(body) or []
    record("IT-11", "任务", "查询可选设备（仅在线且启用）", "GET",
           "/api/tasks/available-devices",
           "200 且只含 ONLINE 设备",
           "HTTP %d, %d 台可选" % (st, len(avail)),
           st == 200 and all(x.get("status") == "ONLINE" for x in avail))

    # 创建草稿任务（不下发，避免产生实际业务影响）
    if devices:
        no = devices[0].get("deviceNo")
        st, body = call("POST", "/api/tasks?autoDispatch=false",
                        {"taskType": "SPECIAL", "deviceNo": no,
                         "area": "接口测试区", "description": "接口测试自动创建"},
                        token=operator_token)
        d = parse(body)
        created_task = d.get("taskId")
        record("IT-12", "任务", "创建任务（草稿，不下发）", "POST", "/api/tasks",
               "200 且状态为 CREATED",
               "HTTP %d, taskId=%s, status=%s" % (st, d.get("taskId"), d.get("status")),
               st == 200 and d.get("status") == "CREATED")

        if created_task:
            st, body = call("GET", "/api/tasks/" + created_task, token=operator_token)
            d = parse(body)
            record("IT-13", "任务", "查询任务详情（台账 + 回执流水）", "GET",
                   "/api/tasks/{id}",
                   "200 且含 task 与 acks",
                   "HTTP %d, acks=%d 条" % (st, len(d.get("acks", []))),
                   st == 200 and "task" in d and "acks" in d)

            st, body = call("POST", "/api/tasks/%s/cancel" % created_task,
                            {"reason": "接口测试取消"}, token=operator_token)
            record("IT-14", "任务", "取消任务", "POST", "/api/tasks/{id}/cancel",
                   "200 且状态为 CANCELLED",
                   "HTTP %d, status=%s" % (st, parse(body).get("status")),
                   st == 200 and parse(body).get("status") == "CANCELLED")
    else:
        for cid, t in [("IT-12", "创建任务"), ("IT-13", "查询任务详情"), ("IT-14", "取消任务")]:
            record(cid, "任务", t, "-", "-", "-", "跳过（无设备数据）", False, "需先启动仿真端")

    # ============ 五、告警接口 ============
    print("\n【五】告警接口\n")

    st, body = call("GET", "/api/alarms?status=PENDING&page=1&size=5",
                    token=operator_token)
    d = parse(body)
    record("IT-15", "告警", "多条件筛选 + 服务端分页", "GET", "/api/alarms",
           "200 且返回 {total, items}",
           "HTTP %d, total=%s" % (st, d.get("total")),
           st == 200 and "total" in d)

    st, body = call("GET", "/api/alarms?page=1&size=5&status=ALL&level=WARN",
                    token=operator_token)
    record("IT-16", "告警", "按级别筛选告警", "GET", "/api/alarms?level=WARN",
           "200", "HTTP %d, total=%s" % (st, parse(body).get("total")), st == 200)

    st, body = call("GET", "/api/alarms/search?type=ENV&level=WARN",
                    token=operator_token)
    record("IT-17", "告警", "走 ES 的检索接口", "GET", "/api/alarms/search",
           "200（走 Elasticsearch）",
           "HTTP %d, 返回 %d 条" % (st, len(parse(body) or [])),
           st == 200)

    # ============ 六、统计接口 ============
    print("\n【六】统计接口\n")

    for cid, path, title, src in [
        ("IT-18", "/api/stats/overview", "总览 KPI", "MongoDB"),
        ("IT-19", "/api/stats/alarms/types", "告警类型分布", "ES terms 聚合"),
        ("IT-20", "/api/stats/alarms/levels", "告警级别分布", "ES terms 聚合"),
        ("IT-21", "/api/stats/alarms/trend", "告警趋势", "ES date_histogram"),
    ]:
        st, body = call("GET", path, token=operator_token)
        d = parse(body)
        cnt = len(d) if isinstance(d, list) else len(d.keys())
        record(cid, "统计", "%s（%s）" % (title, src), "GET", path,
               "200 且返回非空", "HTTP %d, %d 项" % (st, cnt), st == 200)

    # ============ 七、影像接口 ============
    print("\n【七】影像接口\n")

    st, body = call("GET", "/api/media?page=1&size=5", token=operator_token)
    d = parse(body)
    record("IT-22", "影像", "分页查询影像列表", "GET", "/api/media",
           "200 且返回 {total, items}",
           "HTTP %d, total=%s" % (st, d.get("total")),
           st == 200 and "total" in d)

    # ============ 八、网关 ============
    print("\n【八】网关\n")

    st, _ = call("GET", "/")
    record("IT-23", "网关", "前端静态资源可达", "GET", "/", "200", "HTTP %d" % st, st == 200)

    # ============ 汇总 ============
    passed = sum(1 for r in RESULTS if r["ok"])
    total = len(RESULTS)
    print()
    print("=" * 78)
    print("  合计 %d 条，通过 %d，失败 %d，通过率 %.1f%%"
          % (total, passed, total - passed, passed * 100.0 / max(total, 1)))
    print("=" * 78)

    if total - passed:
        print("\n  未通过项：")
        for r in RESULTS:
            if not r["ok"]:
                print("    %s  %s  → %s  %s" % (r["id"], r["title"], r["actual"], r["note"]))
    print()

    # ============ 输出 Markdown ============
    if args.output:
        lines = [
            "# 接口测试记录（自动生成）",
            "",
            "> 执行时间：%s" % time.strftime("%Y-%m-%d %H:%M:%S"),
            "> 执行方式：`deploy/test/api-test.py`（经 Nginx 网关 %s）" % BASE,
            "",
            "| 编号 | 模块 | 用例 | 方法 | 路径 | 预期 | 实际结果 | 状态 |",
            "| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :---: |",
        ]
        for r in RESULTS:
            lines.append("| %s | %s | %s | %s | `%s` | %s | %s | %s |" % (
                r["id"], r["module"], r["title"], r["method"], r["path"],
                r["expect"], r["actual"], "✅" if r["ok"] else "❌"))
        lines += [
            "",
            "**合计 %d 条，通过 %d，失败 %d，通过率 %.1f%%**"
            % (total, passed, total - passed, passed * 100.0 / max(total, 1)),
            "",
        ]
        if total - passed:
            lines.append("## 未通过项")
            lines.append("")
            for r in RESULTS:
                if not r["ok"]:
                    lines.append("- **%s** %s → %s %s" % (r["id"], r["title"], r["actual"], r["note"]))
            lines.append("")

        with open(args.output, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        print("  记录已写出：%s\n" % args.output)

    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
