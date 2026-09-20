# -*- coding: utf-8 -*-
"""
功能测试脚本 —— 覆盖 TC-001 ~ TC-018 中可通过 API 验证的用例。

需要人工在界面上验证的（TC-009 / 011 / 012 / 016 / 017）不在本脚本范围。
需要停止容器构造异常的（TC-010）与需要直接查 Kafka/HDFS 的（TC-003 / 005）
由配套的 bash 步骤完成。

用法（在 deploy\\test 目录下）：
    python functional-test.py                       # 打印结果
    python functional-test.py -o 功能测试记录.md     # 同时写 Markdown
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost"
RESULTS = []


def record(cid, title, expect, actual, ok, note=""):
    RESULTS.append({"id": cid, "title": title, "expect": expect,
                    "actual": actual, "ok": ok, "note": note})
    print("  %s %-6s %-34s %s" % (
        "\033[32m[PASS]\033[0m" if ok else "\033[31m[FAIL]\033[0m",
        cid, title, actual))


def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=20) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:
        return 0, str(e).encode()


def jload(raw):
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--output", default="")
    args = ap.parse_args()

    print()
    print("=" * 74)
    print("  功能测试  ——  %s" % BASE)
    print("=" * 74)

    _, b = call("POST", "/api/auth/login",
                {"username": "operator", "password": "123456"})
    token = jload(b).get("token")
    _, b = call("POST", "/api/auth/login",
                {"username": "admin", "password": "admin123"})
    admin = jload(b).get("token")
    if not token:
        print("  [致命] 无法登录，终止")
        return 1

    # ---------------- TC-001 设备注册上线 ----------------
    print("\n【TC-001】设备注册接入\n")
    _, b = call("GET", "/api/devices", token=token)
    devices = jload(b) or []
    online = [d for d in devices if d.get("status") == "ONLINE"]
    types = {d.get("deviceType") for d in devices}
    record("TC-001", "设备注册接入（首次心跳建台账）",
           "设备存在、状态 ONLINE、类型正确",
           "%d 台，在线 %d 台，类型 %s" % (len(devices), len(online), sorted(types)),
           len(devices) >= 6 and len(online) >= 6
           and types == {"DRONE", "ROBOT_DOG"})

    # ---------------- TC-002 心跳持续上报 ----------------
    print("\n【TC-002】心跳与在线状态上报\n")
    first = {d["deviceNo"]: d.get("heartbeatCount") for d in devices}
    print("  (等待 12 秒，观察心跳计数是否增长…)")
    time.sleep(12)
    _, b = call("GET", "/api/devices", token=token)
    devices2 = jload(b) or []
    second = {d["deviceNo"]: d.get("heartbeatCount") for d in devices2}
    grew = [k for k in first if first.get(k) is not None
            and second.get(k) is not None and second[k] > first[k]]
    sample = list(first.items())[:1]
    record("TC-002", "心跳持续上报（计数递增）",
           "12 秒内心跳计数增长",
           "%d/%d 台增长（示例 %s: %s→%s）" % (
               len(grew), len(first),
               sample[0][0] if sample else "-",
               sample[0][1] if sample else "-",
               second.get(sample[0][0]) if sample else "-"),
           len(grew) >= len(first) * 0.8)

    # ---------------- TC-004 任务指令下行 ----------------
    print("\n【TC-004】平台下发任务指令给设备\n")
    target = online[0]["deviceNo"] if online else None
    task_id = None
    if target:
        _, b = call("POST", "/api/tasks?autoDispatch=true",
                    {"taskType": "SPECIAL", "deviceNo": target,
                     "area": "功能测试区", "description": "TC-004 验证下行链路"},
                    token=token)
        d = jload(b)
        task_id = d.get("taskId")
        record("TC-004", "平台下发任务指令给设备",
               "200 且状态 DISPATCHED（指令已投递）",
               "HTTP 200, taskId=%s, status=%s, msgId=%s" % (
                   task_id, d.get("status"), d.get("msgId")),
               bool(task_id) and d.get("status") in ("DISPATCHED", "RUNNING", "FINISHED"))
    else:
        record("TC-004", "平台下发任务指令给设备", "200", "跳过（无在线设备）", False)

    # ---------------- TC-006 任务记录入库 ----------------
    print("\n【TC-006】保存任务记录\n")
    if task_id:
        _, b = call("GET", "/api/tasks/" + task_id, token=token)
        d = jload(b)
        t = d.get("task", {})
        record("TC-006", "任务记录入库并可查询",
               "按编号可查到完整台账",
               "taskId=%s, device=%s, 回执 %d 条" % (
                   t.get("taskId"), t.get("deviceNo"), len(d.get("acks", []))),
               t.get("taskId") == task_id)
    else:
        record("TC-006", "任务记录入库并可查询", "-", "跳过", False)

    # ---------------- TC-007 按类型检索告警（ES） ----------------
    print("\n【TC-007】按告警类型检索（Elasticsearch）\n")
    _, b = call("GET", "/api/alarms/search?type=ENV", token=token)
    hits = jload(b)
    hits = hits if isinstance(hits, list) else []
    all_env = all(str(h.get("alarmType", "")).upper() == "ENV" for h in hits) if hits else False
    record("TC-007", "按类型检索告警（走 ES）",
           "返回记录且类型与筛选一致",
           "返回 %d 条，类型一致=%s" % (len(hits), all_env),
           len(hits) > 0 and all_env)

    # ---------------- TC-013 设备离线时阻止下发 ----------------
    print("\n【TC-013】设备离线时阻止下发\n")
    # 新建一台设备作为探测对象 —— 没有仿真端为它发心跳，状态必为 OFFLINE。
    # 注意：设备编号由平台自动生成（忽略请求中传入的值），
    #       因此必须从响应里取回真正的编号，不能用自己想的名字。
    _, b_new = call("POST", "/api/devices",
                    {"deviceType": "DRONE", "deviceName": "离线测试机"}, admin)
    probe_no = jload(b_new).get("deviceNo")
    if not probe_no:
        record("TC-013", "设备离线时阻止下发", "400 + 提示设备离线",
               "跳过（探测设备创建失败）", False)
    else:
        time.sleep(1)
        st, b = call("POST", "/api/tasks?autoDispatch=true",
                     {"taskType": "SPECIAL", "deviceNo": probe_no,
                      "area": "离线测试", "description": "应被阻止"}, token=token)
        msg = jload(b).get("message", "")
        record("TC-013", "设备离线时阻止下发",
               "拒绝下发并提示原因（不返回 500）",
               "设备 %s → HTTP %d, %s" % (probe_no, st, msg[:42]),
               st == 400 and "离线" in msg)
        call("DELETE", "/api/devices/" + probe_no, token=admin)   # 清理探测设备

    # ---------------- TC-015 批量指派复核 ----------------
    print("\n【TC-015】批量指派机器狗复核\n")
    _, b = call("GET", "/api/alarms?status=PENDING&page=1&size=3", token=token)
    pending = jload(b).get("items", [])
    if len(pending) >= 3:
        ids = [a["alarmId"] for a in pending[:3]]
        _, b_tasks_before = call("GET", "/api/tasks?taskType=REVIEW&page=1&size=1", token=token)
        before = jload(b_tasks_before).get("total", 0)

        st, b = call("PUT", "/api/alarms/handle-batch",
                     {"action": "REVIEW", "alarmIds": ids,
                      "handleBy": "功能测试", "remark": "TC-015"}, token)
        handled = jload(b).get("handled", 0)

        time.sleep(2)
        _, b2 = call("GET", "/api/tasks?taskType=REVIEW&page=1&size=20", token=token)
        after_items = jload(b2).get("items", [])
        new_ids = [t["taskId"] for t in after_items][:3]
        distinct = len(set(new_ids)) == len(new_ids)

        record("TC-015", "批量处置生成独立任务",
               "3 条告警 → 3 个不同 taskId",
               "处置 %d 条，新增任务 %s，唯一=%s" % (handled, new_ids, distinct),
               handled == 3 and distinct and len(new_ids) >= 3)
    else:
        record("TC-015", "批量处置生成独立任务", "3 条待处理告警",
               "跳过（待处理告警仅 %d 条）" % len(pending), False)

    # ---------------- TC-018 影像预览与下载 ----------------
    print("\n【TC-018】影像预览与下载\n")
    _, b = call("GET", "/api/media?page=1&size=5", token=token)
    items = jload(b).get("items", [])
    ok_dl = False
    detail = "跳过（无影像）"
    for it in items:
        fid = it.get("fileId")
        st, raw = call("GET", "/api/media/%s/download" % fid, token=token)
        if st == 200 and len(raw) > 0:
            ok_dl = True
            detail = "下载 %s 成功，%d 字节" % (fid, len(raw))
            break
        detail = "下载 %s 返回 HTTP %d" % (fid, st)
    record("TC-018", "影像预览与下载（元数据+文件）",
           "200 且返回文件字节",
           detail, ok_dl)

    # ---------------- 汇总 ----------------
    passed = sum(1 for r in RESULTS if r["ok"])
    total = len(RESULTS)
    print()
    print("=" * 74)
    print("  合计 %d 条，通过 %d，失败 %d" % (total, passed, total - passed))
    print("=" * 74)
    if total - passed:
        print("\n  未通过：")
        for r in RESULTS:
            if not r["ok"]:
                print("    %s %s → %s" % (r["id"], r["title"], r["actual"]))
    print()

    if args.output:
        lines = [
            "# 功能测试记录（API 可验证部分）", "",
            "> 执行时间：%s" % time.strftime("%Y-%m-%d %H:%M:%S"),
            "> 执行方式：`deploy/test/functional-test.py`", "",
            "| 用例 | 测试标题 | 预期结果 | 实际结果 | 状态 |",
            "| :---: | :--- | :--- | :--- | :---: |",
        ]
        for r in RESULTS:
            lines.append("| %s | %s | %s | %s | %s |" % (
                r["id"], r["title"], r["expect"], r["actual"],
                "✅" if r["ok"] else "❌"))
        lines += ["", "**合计 %d 条，通过 %d，失败 %d**" % (total, passed, total - passed), ""]
        with open(args.output, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        print("  记录已写出：%s\n" % args.output)

    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
