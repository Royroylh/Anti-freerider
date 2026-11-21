# Anti-freerider

Anti-freerider 帮课程小组回答三个问题。现在谁负责什么？哪项工作需要跟进？结项时，每个人的贡献如何复算？

小组先约定任务的负责人、作者份额、分值和验收标准，再提交固定版本。其他组员匿名验收，系统按通过版本的署名和分值生成可复算报告。公告栏可查看检查点和求助。组员也能在群聊关联任务、申请延期、交接负责人、人工举报，或通过证据问答打开相关成果。一次验收未通过不会自动举报个人。

[产品定稿](docs/PLAN.md) · [SRS](docs/SRS.md) · [设计说明](docs/DESIGN.md) · [用户手册](docs/USER_MANUAL.md) · [开发者手册](docs/RUNBOOK.md) · [需求覆盖](docs/REQUIREMENTS_COVERAGE.md) · [测试覆盖](docs/TEST_COVERAGE.md)

## 本地试用

需要 JDK 21 或更新版本、Bash、curl，首次构建需要访问 Maven Central。在新的检出中运行以下命令。

```bash
./scripts/build.sh
AF_SEED_DEMO=1 ./scripts/start.sh
```

打开 [本机登录页](http://127.0.0.1:8765/)。可用 `alice@demo.edu` 登录，密码为 `demo-pass-123`。另开独立浏览器会话，以 `ben@demo.edu`、`cara@demo.edu` 或 `dan@demo.edu` 登录同一小组。演示数据只在空库时生成。已有工作请继续使用原数据；需要可重复的演示时，在独立检出按[隔离测试步骤](docs/RUNBOOK.md#隔离验收)重建。

## 分工到结项

1. **分工。** 建项目和分支，邀请队友，约定检查点、截止日期、预期成果、作者份额、标准与分值。交接可仅更换负责人，编辑未来署名是另一个操作。
2. **固定版本提交。** 粘贴成果正文或上传一份最多 1 MiB 的 PDF。每次提交冻结成果、标准、分值、作者和本轮资格。
3. **匿名验收。** 作者亲自确认，其他组员匿名投票。全组共同署名时，每人还需另投一票。收齐前显示「待确认」。仍在组内的本版作者可说明原因并撤回尚未结束的整轮。
4. **可复算报告。** 每项任务最多入账一次。报告按冻结份额分配已验收分值，并附版本与原始计分数据。例如项目 60/100 = 60%，Alice 在 20 分任务占 75%，得 15 分，占全组已验收分值的 15/60 = 25%。

网页直接使用 `dist/` 中的手写 HTML、CSS 和 JavaScript。Java 服务监听本机回环地址，默认 SQLite 路径为 `data/af.db`。本次验收在桌面和手机 Chrome 视口运行，问答使用证据检索回退。

## 版权

This project, no matter the idea or the programs, is protected by copyright.
