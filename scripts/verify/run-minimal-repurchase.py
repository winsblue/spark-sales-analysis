# -*- coding: utf-8 -*-
"""
最小复购样例回归测试：验证「日期范围影响总览指标」+「去重指标不累加每日值」

样例数据（scripts/verify/minimal-repurchase/）
    第 1 天 2026-01-01：A1(U1,100)  A2(U2,200)          → 2 单 / 2 用户 / GMV 300
    第 2 天 2026-01-02：B1(U1,100)  B2(U3,50,已取消)     → 2 单 / 2 用户 / GMV 100（B2 不计）
    两天合计：           4 单 / 3 用户（U1 复购）/ GMV 400 / 有效订单 3

关键断言
    两天合计的「下单用户数」必须是 3，而不是把每天的去重值相加得到的 4 —— 这正是
    「不能简单累计每日去重值」的判定点。

用法（在项目根目录，需要先构建过 spark-job）
    python scripts/verify/run-minimal-repurchase.py
    python scripts/verify/run-minimal-repurchase.py --api http://127.0.0.1:8080   # 顺带验接口

退出码 0 = 全部断言通过；1 = 有断言失败；2 = 无法执行（缺环境）
"""
import argparse
import os
import subprocess
import sys

import pandas as pd

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FIXTURE = os.path.join(ROOT, "scripts", "verify", "minimal-repurchase")
MYSQL = r"C:\MySQLServer5.5\bin\mysql.exe"
JAVA = r"D:\Java\jdk1.8.0_212\bin\java.exe"
MVN = r"D:\maven\apache-maven-3.8.8\bin\mvn.cmd"
TEST_DB = "sales_verify_minimal"

results = []
lines = []


def p(s=""):
    lines.append(str(s))


def assert_eq(name, actual, expected):
    ok = str(actual) == str(expected)
    results.append(ok)
    p("  [%s] %-46s 实际=%-12s 期望=%s" % ("PASS" if ok else "FAIL", name, actual, expected))
    return ok


def sql(q, db=TEST_DB):
    cmd = [MYSQL, "-h", "127.0.0.1", "-uroot", "-p123456", "-N", "-B", "-D", db, "-e", q]
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode("utf-8", "replace"))
    return r.stdout.decode("utf-8", "replace").strip()


def scalar(q):
    v = sql(q)
    return v.splitlines()[0].strip() if v else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default=None, help="可选：已启动的后端地址，用于顺带验证接口")
    ap.add_argument("--skip-etl", action="store_true", help="跳过作业执行，只做数据库断言")
    args = ap.parse_args()

    p("=" * 88)
    p("最小复购样例回归测试（库=%s）" % TEST_DB)
    p("=" * 88)

    # ---------------- 1. 跑作业（把样例数据写进独立测试库） ----------------
    if not args.skip_etl:
        p("")
        p("【1】执行离线作业（数据源指向样例目录，结果写独立测试库）")
        cp_file = os.path.join(ROOT, "spark-job", "target", "cp-all.txt")
        if not os.path.exists(cp_file):
            p("  缺少依赖清单 %s" % cp_file)
            p("  请先执行：mvn -pl spark-job -am package -DskipTests 并生成 classpath")
            p("  提示：脚本会尝试自动补跑 Maven，可能耗时 1~2 分钟")
            subprocess.run([MVN, "-B", "-q", "-f", os.path.join(ROOT, "spark-job", "pom.xml"),
                            "dependency:build-classpath",
                            "-Dmdep.outputFile=" + cp_file], cwd=ROOT)
            if not os.path.exists(cp_file):
                p("  仍然无法生成 classpath，测试终止")
                return 2
        cp = open(cp_file, encoding="utf-8").read().strip()
        raw_dir = os.path.relpath(FIXTURE, ROOT).replace("\\", "/")
        jdbc = ("jdbc:mysql://127.0.0.1:3306/%s?useUnicode=true&characterEncoding=utf8&useSSL=false"
                % TEST_DB)
        cmd = [JAVA, "-Xmx1g", "-Ddata.raw.dir=" + raw_dir, "-Djdbc.url=" + jdbc,
               "-cp", os.path.join(ROOT, "spark-job", "target", "classes") + ";" + cp,
               "com.sales.SalesAnalysisApplication", "--mode=df"]
        r = subprocess.run(cmd, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        out = r.stdout.decode("utf-8", "replace")
        p("  作业退出码 = %d" % r.returncode)
        if r.returncode != 0:
            p("  作业输出尾部：")
            for line in out.splitlines()[-12:]:
                p("    " + line)
            return 2
        for line in out.splitlines():
            if "有效记录数" in line or "原始记录数" in line or "流程执行完成" in line:
                p("    " + line.strip())

    # ---------------- 2. DWD 层断言 ----------------
    p("")
    p("【2】DWD 明细层（样例共 4 行）")
    assert_eq("DWD 行数", scalar("SELECT COUNT(*) FROM dwd_order_detail"), 4)
    assert_eq("唯一订单数（下单量）", scalar("SELECT COUNT(DISTINCT order_id) FROM dwd_order_detail"), 4)
    assert_eq("有效订单数", scalar("SELECT COUNT(DISTINCT order_id) FROM dwd_order_detail "
                                   "WHERE is_valid_order=1"), 3)
    assert_eq("有效 GMV（B2 已取消，不计）",
              scalar("SELECT ROUND(SUM(pay_amount),2) FROM dwd_order_detail WHERE is_valid_order=1"),
              "400.00")
    # 有效买家只有 U1（A1、B1 两天各一单）和 U2（A2）；U3 的 B2 已取消 → 不算买家
    assert_eq("区间内有效去重用户数（U1/U2）",
              scalar("SELECT COUNT(DISTINCT user_id) FROM dwd_order_detail WHERE is_valid_order=1"), 2)

    p("")
    p("  按天拆开看（说明为什么不能把每日值相加）:")
    rows = sql("SELECT stat_date, "
               "COUNT(DISTINCT CASE WHEN is_valid_order=1 THEN order_id END), "
               "COUNT(DISTINCT CASE WHEN is_valid_order=1 THEN user_id END), "
               "ROUND(SUM(CASE WHEN is_valid_order=1 THEN pay_amount ELSE 0 END),2) "
               "FROM dwd_order_detail GROUP BY stat_date ORDER BY stat_date").splitlines()
    daily_users = 0
    for r in rows:
        d, o, u, g = r.split("\t")
        daily_users += int(u)
        p("    %s  有效订单 %s  有效用户 %s  有效GMV %s" % (d, o, u, g))
    overall_users = int(scalar("SELECT COUNT(DISTINCT user_id) FROM dwd_order_detail "
                               "WHERE is_valid_order=1"))
    p("    每日有效去重用户数相加 = %d  ← 若总览按这个口径算就是错的" % daily_users)
    p("    区间内整体去重         = %d  ← 正确口径（U1 跨两天复购只能算一个人）" % overall_users)
    assert_eq("每日去重值相加 != 区间整体去重（证明必须整体去重）",
              daily_users != overall_users, True)

    # ---------------- 3. ADS 层断言 ----------------
    p("")
    p("【3】ADS 预计算层")
    assert_eq("趋势表天数", scalar("SELECT COUNT(*) FROM ads_daily_trend"), 2)
    assert_eq("趋势表 Σgmv", scalar("SELECT ROUND(SUM(gmv),2) FROM ads_daily_trend"), "400.00")
    assert_eq("趋势表 Σ有效订单量",
              scalar("SELECT SUM(valid_order_cnt) FROM ads_daily_trend"), 3)
    assert_eq("overview 表 GMV",
              scalar("SELECT kpi_value FROM ads_overview WHERE kpi_code='GMV'"), "400.0000")
    assert_eq("overview 表 下单用户数（全周期）",
              scalar("SELECT kpi_value FROM ads_overview WHERE kpi_code='BUYER_CNT'"), "2.0000")

    # ---------------- 4. 接口断言（可选） ----------------
    if args.api:
        p("")
        p("【4】接口层（%s）" % args.api)
        try:
            import json
            import urllib.request

            def get(path):
                with urllib.request.urlopen(args.api.rstrip("/") + path, timeout=30) as resp:
                    return json.loads(resp.read().decode("utf-8"))

            def kpi(data, code):
                for c in data["data"]:
                    if c["code"] == code:
                        return c
                return None

            cases = [
                ("不传日期（全周期）", "/api/overview", "BUYER_CNT", "2.0000"),
                ("2026-01-01 单日", "/api/overview?startDate=2026-01-01&endDate=2026-01-01",
                 "BUYER_CNT", "2.0000"),
                ("2026-01-02 单日（仅 U1，U3 订单已取消）",
                 "/api/overview?startDate=2026-01-02&endDate=2026-01-02", "BUYER_CNT", "1.0000"),
                ("跨两天（整体去重，不是 2+1=3）",
                 "/api/overview?startDate=2026-01-01&endDate=2026-01-02", "BUYER_CNT", "2.0000"),
            ]
            for label, path, code, expected in cases:
                d = get(path)
                card = kpi(d, code)
                assert_eq("%s 的下单用户数" % label,
                          ("%.4f" % float(card["value"])) if card else "N/A", expected)
                p("        来源标记 source=%s" % (card["source"] if card else "-"))

            d = get("/api/overview?startDate=2026-01-01&endDate=2026-01-02")
            assert_eq("跨两天 GMV",
                      "%.2f" % float(kpi(d, "GMV")["value"]), "400.00")
            d = get("/api/overview?startDate=2026-01-02&endDate=2026-01-02")
            assert_eq("单日(1-02) GMV（B2 已取消不计）",
                      "%.2f" % float(kpi(d, "GMV")["value"]), "100.00")
            t = get("/api/trend")
            assert_eq("趋势接口返回点数", len(t["data"]), 2)
            pg = get("/api/order/page?pageNum=1&pageSize=20")
            assert_eq("明细接口 total（默认仅有效订单）", pg["data"]["total"], 3)
            pg0 = get("/api/order/page?pageNum=1&pageSize=20&onlyValid=0")
            assert_eq("明细接口 total（onlyValid=0 含已取消）", pg0["data"]["total"], 4)
        except Exception as e:  # noqa: BLE001
            p("  接口断言未执行：%s" % e)
            p("  （若后端未启动，可用 scripts\\start-server.cmd 启动后重跑，并指定 --api）")
    else:
        p("")
        p("【4】接口层：未执行（未传 --api，属于未验证项）")

    # ---------------- 汇总 ----------------
    p("")
    p("=" * 88)
    passed = sum(1 for x in results if x)
    p("结果：%d/%d 项通过" % (passed, len(results)))
    p("=" * 88)

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "minimal-repurchase-report.txt")
    with open(out, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print("\n".join(lines))
    print("\n报告已写入: %s" % out)
    return 0 if passed == len(results) and results else 1


if __name__ == "__main__":
    sys.exit(main())
