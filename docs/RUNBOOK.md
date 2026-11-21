# 开发者手册

## 构建与运行

需要 JDK 21+、Bash、curl、可用的 Maven Central，测试另需 Python 3.10+、Playwright 和 Chrome。Java 虚拟线程用于 HTTP 请求；干净检出时用 `java -version`、`javac -version` 检查 PATH。按 [README](../README.md#本地试用) 启动，检查 `curl -fsS http://127.0.0.1:8765/api/health`。构建脚本把固定版本 Gson 2.11.0 和 SQLite JDBC 3.46.1.3 下载到 `server/lib/`，编译到 `build/classes/`。网页从 `dist/` 直接提供。

| 路径 | 用途 |
| --- | --- |
| `dist/` | 手写登录页、工作区、样式、JS 和图片占位。 |
| `server/src/af/` | HTTP 路由、业务规则、SQLite 模式与迁移、演示数据。 |
| `server/test/af/` | 无 HTTP 和数据库依赖的规则单元测试。 |
| `scripts/` | 运行与时钟辅助、端到端及浏览器验收、独立复算。 |
| `docs/` | 定稿、需求、设计、手册及覆盖报告。 |

`AF_PORT` 默认 8765，服务绑定 127.0.0.1。`AF_DATA_DIR` 可指定 SQLite 目录；`AF_CLOCK_FILE` 可指定模拟时钟文件，存在时每次读取。没有时钟文件时可用 `AF_FIXED_CLOCK` 或系统时间。演示标志用 `AF_SEED_DEMO=1` 或传 `--seed-demo`，正常使用请省略该标志。正常运行可清除进程环境中的所有测试用 `AF_*` 配置再启动。

## 数据与迁移

已有库升级前停止本机服务，在停止状态下备份数据库；在线备份请使用 SQLite 的备份 API 来保持 WAL 一致性。用同版本的代码在独立目录指向备份副本，验证项目、历史版本和贡献后再安排实际升级。

启动时 `Db.init` 检查旧版 `submissions` 模式并事务化重建，保留 ID 和既有状态，已计分记录优先用 `task_scores` 补齐冻结分值。历史全员署名轮次维持历史确认规则。随后建立缺失表，从可核实的原约定、交接及日期事件为旧举报补快照。缺失信息有标记，相关复核向成员关闭。启动后用样例账号检查旧版与导出，再重启复查。公开自动化使用独立程式生成的旧模式夹具。

## 隔离验收

每次从候选提交建独立检出。回归脚本会读写自己检出根目录的 `data/`，因此测试数据目录和时钟文件必须与该检出的布局对应。`reset-demo.sh` 会删除所在检出的 `data/af.db` 及其 WAL、SHM 文件，只在下面建立的临时副本运行。

```bash
RUN_DIR=$(mktemp -d)
git clone --no-local . "$RUN_DIR/checkout"
git -C "$RUN_DIR/checkout" checkout --detach "$(git rev-parse HEAD)"
cd "$RUN_DIR/checkout"
./scripts/build.sh
./scripts/test_unit.sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r scripts/requirements-browser.txt
AF_PORT=18771 ./scripts/reset-demo.sh
AF_PORT=18771 AF_DATA_DIR="$PWD/data" AF_CLOCK_FILE="$PWD/data/clock.txt" \
  AF_SEED_DEMO=1 ./scripts/start.sh
```

在另一个终端进入同一个临时检出，启动虚拟环境后按此顺序执行。

```bash
export AF_BASE_URL=http://127.0.0.1:18771
curl -fsS "$AF_BASE_URL/api/health"
python scripts/verify_e2e.py
python scripts/check_contributions.py
python scripts/browser_walkthrough.py
python scripts/verify_acceptance_fixes.py
python scripts/browser_acceptance_fixes.py
python scripts/verify_lifecycle.py --port 18772
```

后面的浏览器用例读取接口回归建立的 Pair Sprint 和 Contribution Math 100 数据。专项生命周期脚本独立启动 Java、重启并升级合成旧库。结果、截图和可复算导出在临时检出的 `build/verification/`。测试结束后停止该检出的服务。

## 故障定位

- **构建失败。** 查 Java 版本与 Maven Central 的 TLS／网络访问。构建时没有 Java 手工复制依赖步骤。
- **健康检查失败。** 核实端口未被占用，启动日志的 DB、静态资源与时钟路径都指向当前检出。查看命令行的请求与 Java 异常。
- **账号或计时用例失败。** 确认测试库是新建的、初始化时钟为 `2026-11-21T10:00:00Z`，并在启动服务器前设定。API 检查使用独立 cookie jar，Chrome 检查使用独立上下文。
- **Chrome 测试失败。** 检查已安装 Chrome stable、Python 导入的是本虚拟环境的 Playwright。浏览器测试执行页面与真实 Java API；查看 `build/verification/` 的结果和截图。
- **问答显示回退。** 默认使用确定性检索。`AF_AI_ENDPOINT` 可指向本地 OpenAI 兼容 HTTP 地址，配合 `AF_AI_MODEL`；需要密钥时仅在该进程环境传 `AF_AI_API_KEY`。端点失败时结果标明回退。`AF_AI_MODE=test-double` 只用于测试标记。真实模型、真实设备与生产部署的验证范围见[测试覆盖](TEST_COVERAGE.md)。
