# 需求覆盖报告

[需求规格](SRS.md)有 16 项功能需求、4 项非功能需求和 6 个用户故事。下表将 20 项需求对应到实现和本次候选验收。多个需求可共享一个断言。[环境与计数口径](TEST_COVERAGE.md)

测试简称为 [E2E 接口回归](../scripts/verify_e2e.py)、[Fix API 专项接口](../scripts/verify_acceptance_fixes.py)、[Chrome 浏览器回归](../scripts/browser_walkthrough.py)、[Fix UI 专项浏览器](../scripts/browser_acceptance_fixes.py)、[U 规则单元测试](../server/test/af/RulesTest.java)及 [I 生命周期集成](../scripts/verify_lifecycle.py)。方括号内为脚本场景号。

| 需求 ID | 用户故事 | 设计位置 | 实现位置 | 具体测试或断言 | 实现状态 | 本次验证结果 | 剩余缺口 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| FR-01 | US-01 | D-01 | [Auth](../server/src/af/Auth.java)、[Server](../server/src/af/Server.java) | E2E [1] 退出失效、[10] 非成员；I-02；Fix API 附件 403 | 已实现 | 注册、登录、退出及成员权限通过 | 公网部署和其他会话客户端未测 |
| FR-02 | US-01 | D-01 | [Projects](../server/src/af/Projects.java) | E2E [8] 邀请；Chrome [3–4,7] 切换和入组；Fix UI 离组；Fix API 离组后保留贡献 | 已实现 | 四会话与离组历史通过 | 项目负责人移交超出首版 |
| FR-03 | US-02 | D-02 | [Tasks.create](../server/src/af/Tasks.java)、[Projects.revisePlan](../server/src/af/Projects.java) | Fix API 两级余额、降低至 3 分、并发写；Fix UI 从原表单修订并保存 7 分任务 | 已实现 | 两级 10 分提示 3／4，修订后可保存 | 大型项目密集并发未测 |
| FR-04 | US-02 | D-02 | [Tasks.reassign / editAuthors](../server/src/af/Tasks.java) | E2E [7b] 交接保存 Ben/Cara 60/40、贡献使用冻结份额；Fix UI 前后差异 | 已实现 | Dan 协调，旧 10 分按 6／4 入账 | 已入账任务保持冻结，后续工作另立任务 |
| FR-05 | US-03 | D-03 | [Tasks.submit / attachment](../server/src/af/Tasks.java)、[Server](../server/src/af/Server.java) | Fix API 文件名、空白、链接、无效／超限 PDF、同名版本字节；Fix UI 上传失败保留草稿 | 已实现 | 有效正文和两份独立 PDF 通过权限与保真检查 | 质量问答使用正文，PDF 原文由成员打开 |
| FR-06 | US-03 | D-03 | [Tasks.confirm / vote](../server/src/af/Tasks.java) | E2E [5,8] 全员署名并确认、重复投票；Chrome [6] 两人组提示 | 已实现 | 全员匿名票收齐时仍等个人确认；两人组提示出现 | 大规模成员未测 |
| FR-07 | US-03 | D-03 案例 | [Tasks.finalizeIfComplete](../server/src/af/Tasks.java#L792) | U-03、U-04；E2E [2–3] 早到反对、v2 冻结分值、唯一计分 | 已实现 | v1 未通过且 0 分，v2 通过后唯一入账 | 未做故障注入 |
| FR-08 | US-03 | D-03 状态表 | [Tasks.withdraw](../server/src/af/Tasks.java)、[Projects.leave](../server/src/af/Projects.java) | Fix API 空原因／非作者／迟到票、并发终态；Fix UI 草稿、撤回、离组；I-09 | 已实现 | 显式撤回保留旧版，重提重新收集；老状态留存 | 并发快照的获胜操作取决于到达顺序 |
| FR-09 | US-06 | D-07 | [Contribution.calculate](../server/src/af/Contribution.java#L48)、[Reports](../server/src/af/Reports.java) | U-01/U-02、I-01、E2E [4,12]、[独立复算](../scripts/check_contributions.py)、Fix API 14 分 | 已实现 | 60/100、Alice 15/60、Dan 0/60、空组份额及 14/14 通过 | 未用长周期大项目验证数值累加 |
| FR-10 | US-04 | D-04 | [Tasks.addSupport](../server/src/af/Tasks.java)、[Projects.board](../server/src/af/Projects.java) | E2E [7] 未解决支持显示受阻、I-05 中途求助 | 已实现 | 成员行及任务可见受阻与所需支持 | 支持状态组合未穷举 |
| FR-11 | US-04 | D-04 | [Reminders.evaluateTask](../server/src/af/Reminders.java#L73)、[Tasks.events](../server/src/af/Tasks.java) | U-05；I-02 至 I-07 含延迟创建、23/24h、求助、刷新、重启、交接隐私；E2E [9] | 已实现 | 到阈值只升级沉默任务；原首次时间和收件人留存 | 跨时区设备未实测 |
| FR-12 | US-04 | D-04 | [Projects.messages](../server/src/af/Projects.java)、[app.js](../dist/assets/app.js#L889) | Fix UI 自动收到一次、草稿／选择／阅读位置、旧请求 | 已实现 | 两会话在 10 秒内收取一次，并打开关联任务 | 网络抖动及多设备实机未测 |
| FR-13 | US-04 | D-04 | [Tasks.requestExtension / decideExtension](../server/src/af/Tasks.java) | E2E [7] 申请、批准与旧截止；Chrome [2] 引用定位延期；Fix API 举报后延期 | 已实现 | 保存旧日期、原因和批准后的日期 | 多请求审批顺序未穷举 |
| FR-14 | US-05 | D-05 | [Reports.create / recoverPlan](../server/src/af/Reports.java) | E2E [7b] 原涉事人回避；Fix UI 案件详情；I-08／I-11 | 已实现 | 原作者陈述成功，Dan 可复核；同时间戳历史还原后重启一致 | 旧资料不足时保守限制复核 |
| FR-15 | US-06 | D-06 | [Evidence.resolveTarget / criteriaAnswer](../server/src/af/Evidence.java) | Fix API 月壤光谱、同名、相似及 v1/v2；Fix UI 点击引用；E2E [11] 跨项目 | 已实现 | 无匹配返回空引用，选中引用打开具体版本；回退模式 | 真实本机模型未实测 |
| FR-16 | US-05、US-06 | D-07 | [Reports.exportHtml](../server/src/af/Reports.java)、[app.js](../dist/assets/app.js) | E2E [2,12] 失败版本无举报与独立复算；Chrome [3] 预览下载；Fix API 撤回附件 | 已实现 | 预览、下载与附录复算通过 | 实际授课方的材料格式未验收 |
| NFR-01 | US-03、US-06 | D-03、D-06、D-07 | [Tasks](../server/src/af/Tasks.java)、[Evidence](../server/src/af/Evidence.java)、[Reports](../server/src/af/Reports.java) | E2E [2,11–12] 四会话匿名字段、任务群聊唯一标记断言；Fix API 成员版次、公告和导出字段 | 已实现 | 个人操作提示与公共结果通过检查，问答不含聊天或匿名票明细 | 两人组仍有推断风险，界面在投票前提示 |
| NFR-02 | US-02、US-03 | D-02、D-03 | [Db.write](../server/src/af/Db.java)、[Schema](../server/src/af/Schema.java) | E2E [8] 两路投票与重复请求；Fix API 终票对撤回、两路分配 | 已实现 | 竞态单终态、唯一计分、分配合计 7 分 | 无压力或长时测试 |
| NFR-03 | US-01、US-03 | D-01、D-03、D-04 | [dashboard.css](../dist/dashboard.css)、[app.js](../dist/assets/app.js) | Chrome [8] 无横向溢出；Fix UI 1440×950 与 390×844 | 已实现 | 桌面及模拟手机操作通过 | 实体手机、Safari 和 Firefox 未验证 |
| NFR-04 | US-01、US-06 | 组件与数据边界 | [Server](../server/src/af/Server.java)、[Db.init](../server/src/af/Db.java)、[Evidence.status](../server/src/af/Evidence.java) | 干净启动健康检查；I-08 至 I-11 迁移与重启；Fix API 检索回退 | 已实现 | 固定依赖下载并启动，SQLite 完整性检查通过，模式为回退 | 真实模型和生产部署未验证 |

## 交付前追踪抽查

- **FR-07** · [验收轮次](DESIGN.md#验收轮次) → [收齐与终态判定](../server/src/af/Tasks.java#L792) → [早到反对和终态断言](../scripts/verify_e2e.py#L207) → 本次 v1 先待确认、收齐后未通过，v2 通过只计一次。
- **FR-09** · D-07 → [分配和汇总](../server/src/af/Contribution.java#L40) → [独立明细复算](../scripts/check_contributions.py) → 本次项目 60%、Alice 15 分且 25%、Dan 0 分且 0%。
- **FR-11** · D-04 → [24 小时规则](../server/src/af/Reminders.java#L143) → [规则 U-05](../server/test/af/RulesTest.java) 和 [集成 I-03 至 I-07](../scripts/verify_lifecycle.py) → 本次第 23 小时仍私下，重启无重置，第 24 小时仅沉默任务升级。
