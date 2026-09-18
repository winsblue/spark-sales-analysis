# 可重复验证脚本（回归测试 / 对账样例）

这个目录放的是**可以反复执行的对账与回归检查**，用来在改动实现前先钉住行为、改完之后自证。

> 设计原则：**先有可重复的检查，再改实现**。所有脚本都返回明确的退出码，
> 可以直接串进发布流程（`0 = 全部通过`，非 0 = 有问题）。

---

## 1. `recon-olist.py` —— Olist 真实数据四级对账

```bash
python scripts/verify/recon-olist.py
python scripts/verify/recon-olist.py --db sales_analysis --user root --password 123456
```

**校验内容**：`原始表 → 转换后 ODS CSV → DWD → ADS` 四级的行数与金额逐级对齐，
并输出：

| 章节 | 内容 |
| --- | --- |
| 【1】 | 原始层事实（行数、Σprice、Σfreight、Σpayment_value） |
| 【2】 | 转换层（分组数、Σpay_amount） |
| 【3】 | DWD 层（行数、全部/有效金额、唯一订单数、异常留痕行数） |
| 【4】 | ADS 层（趋势/类目/overview 的 GMV 与订单量） |
| 【5】 | 订单状态映射对照（含**每一步的映射依据**） |
| 【6】 | 排除规则与分母口径（决定所有比率的分母） |
| 【7】 | 「转换后规则通过率」与「原始数据质量」的区别 |

**产物**：`recon-olist-report.txt`（报告），退出码 `0/1`。

**关键结论（当前实测）**

| 层级 | 行数 | 金额 |
| --- | --- | --- |
| 原始 items | 112,650 | Σprice **13,591,643.70** |
| 转换后 CSV | 102,425（= 分组数） | Σpay_amount 13,591,643.70 |
| DWD | 102,425 | 全部 13,591,643.70 / 有效 **13,494,400.74** |
| ADS | 趋势/类目/overview | 均 **13,494,400.74** |

> 99,441 笔订单里有 **775 笔没有商品行**，完全不进 DWD →
> **所有指标的分母是 98,666（有商品行的订单数），不是 99,441**。

---

## 2. `run-minimal-repurchase.py` —— 最小复购回归测试

```bash
# 跑作业 + 数据库断言
python scripts/verify/run-minimal-repurchase.py
# 顺带验证接口（需要后端已启动，且指向同一个测试库）
python scripts/verify/run-minimal-repurchase.py --skip-etl --api http://127.0.0.1:8080
```

**样例数据**（`minimal-repurchase/`，4 行，独立测试库 `sales_verify_minimal`）

| 时间 | 订单 | 用户 | 金额 | 状态 |
| --- | --- | --- | --- | --- |
| 2026-01-01 | A1 | U1 | 100 | 已完成 |
| 2026-01-01 | A2 | U2 | 200 | 已完成 |
| 2026-01-02 | B1 | U1（**复购**） | 100 | 已完成 |
| 2026-01-02 | B2 | U3 | 50 | 已取消（不计入有效） |

**为什么用这个样例**：它同时覆盖了两个最容易写错的口径

1. **同一用户跨两天复购** → 区间去重用户数 = **2**（U1/U2），
   而"每天去重值相加" = 2 + 1 = **3** —— 若总览按后者算就错了；
2. **已取消订单不计入有效** → U3 不是买家，有效 GMV = **400**（不含 B2 的 50）。

**断言覆盖**：DWD 行数/订单数/有效订单/有效 GMV/去重用户、ADS 趋势行数与 Σgmv、
overview 的 GMV 与用户数、接口层 4 种日期范围（不传 / 单日 ×2 / 跨两天）+ 趋势点数 + 明细 total。

**当前实测：20/20 通过。**

---

## 3. 其它相关检查

| 检查 | 命令 | 说明 |
| --- | --- | --- |
| 三种计算模式 | `scripts\run-etl.cmd --mode=df` / `--mode=rdd` / `--mode=both` | 看作业日志是否保留两条比较记录、异常表是否有数据、两轮结果是否一致 |
| 打包自检 | `powershell -ExecutionPolicy Bypass -File scripts\package-dist.ps1` | 交付包必须含 `.git` 历史、且不含 `node_modules`/`data/raw` 等非交付内容；不满足则退出码 1 |
| 口径自检 | `scripts\run-etl.cmd --import-real` | 每次导入都会打印两条金额口径自检（同单同品不同价、数量×单价与实付的差异） |

---

## 4. 环境依赖

- Python：`C:\Users\19351\venvs\data-analysis`（已装 pandas）
- MySQL 客户端：`C:\MySQLServer5.5\bin\mysql.exe`
- 脚本内路径目前按本机约定写死，换机器时用命令行参数覆盖（`--host/--port/--user/--password/--db`）
