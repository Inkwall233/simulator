# 设备模拟器制作与系统运行实施计划

> **供执行代理使用：** 必须逐项使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 实施。以下复选框用于追踪进度。

**目标：** 制作者通过上传设备包、采集、AI 辅助建模、验证和启用，在 SkillLab 内运行具有隔离状态的固定模拟器版本。

**架构：** Java 保存证据、不可变行为修订、验证报告、可用版本和运行租约；制作 Worker 负责采集、AI 建议及验证；独立 origin 上的平台 Service Worker 用固定解释器执行 v2 行为模型，IndexedDB 保存单次运行状态。仿真器壳负责授权启动和结束，不调用 AI。先完成受限制作人试点，不开放匿名考生。

**技术栈：** Java 8 / Spring Boot 2.7 / H2 / JUnit；Node.js / Playwright / Service Worker / IndexedDB；Vue 3 / TypeScript / Vite。

**设计依据：** `docs/superpowers/specs/2026-11-07-simulator-authoring-design.md`

## 全局约束

- AI 只在制作阶段调用；使用阶段不得调用模型或真实设备。
- 原始 ZIP 不可变；v1 草稿继续可读，v2 使用新协议和新版本，不修改旧载荷。
- 每次运行独立 origin 和状态；刷新保留，结束后重开重置。设备登出不清除设备配置。
- 关键读写必须有采集证据并验证通过；待确认异常推断可经明确确认启用，来源标记持续保留。
- 未匹配接口失败关闭，绝不转发真实设备或伪造成功；所有真实设备写操作逐次审批。
- 不新增练习、引导、考试评分、任务完成逻辑，也不声称真实 AucSeek 已适配。
- 现有未提交代码和本机 H2/包数据属于工作区现状；先审查，绝不通过重置、清理或批量暂存覆盖。每项仅提交自身文件。
- 本地试点沿用 `*.localhost`；生产独立可注册运行域、泛域名 DNS/TLS、出站 ACL 和终端用户身份接入不在此轮开放范围内。

## 审查重点

以下五种易遗漏的输入/失败情况必须由所属任务测试锁定：

1. 请求正文缺失、截断或脱敏时，规则不能从占位值更新状态（任务 2、3）。
2. 同优先级重叠规则、危险对象键和双重 URL 编码，必须在保存或执行前拒绝（任务 1、3）。
3. Service Worker 终止、多标签并发写和 IndexedDB 配额失败，不能丢失成功提交或假报成功（任务 4）。
4. 运行租约过期或明确结束后，即使页面仍开着，也不能继续执行 Mock；新运行不能读取旧 origin 存储（任务 5）。
5. 外部绝对 URL、未识别 API、跨域伪造消息和无授权版本启动，不得访问真实设备或平台凭证（任务 5、6、8）。

## 文件与职责

- `schemas/simulator-behavior-v2.schema.json`、`schemas/examples/simulator-behavior-v2.example.json`：v2 协议与共用样例；现有 v1 schema 保留。
- `ai-worker/src/behavior-contract.js`、`behavior-interpreter.js`：校验及纯函数规则执行；`preview-mock-sw.js` 迁移为薄请求适配层。
- `ai-worker/src/preview-bootstrap.js`、`preview-runtime-store.js`：接管等待、运行实例 IndexedDB 状态和事务。
- `ai-worker/src/capture-evidence.js`：受限采集、脱敏与证据归档；`server.js` 保留路由接线和现有浏览会话逻辑，不在大文件内继续堆业务。
- `skilllab-admin/src/main/java/com/skilllab/admin/ai/` 新增 `AiEvidence*`、`AiSimulatorVersion*`、`AiValidation*`、`AiRun*`：持久化及对应服务；现有 `AiSimulatorDraftService` 处理 v1 兼容与 v2 修订。
- `web-admin/src/views/ai-workbench/` 新增五个制作视图，`AiWorkbenchView.vue` 只承载项目上下文与视图切换；`web-admin/src/api/ai-workbench.ts` 提供类型化请求。
- `web-simulator/src/views/SimulatorRunView.vue` 与路由：受限试点的普通运行壳；原 `/runtime` 演示页保持独立。
- `ai-worker/test/`、`skilllab-admin/src/test/` 及必要的 Playwright 端到端脚本：各项的回归测试。

---

### Task 1: 审查原型并固定 v2 协议

**文件：** 新建 `schemas/simulator-behavior-v2.schema.json`、`schemas/examples/simulator-behavior-v2.example.json`、`ai-worker/src/behavior-contract.js`、`ai-worker/test/behavior-contract.test.js`；修改 `skilllab-admin/src/main/java/com/skilllab/admin/ai/AiSimulatorDraftService.java`、对应 Java 测试。

**接口：** `validateBehaviorV2(payload): normalizedPayload`；Java `createDraftV2(projectId, request)` 和 `getDraft(projectId, draftId)` 保持 v1 读取。导入 v1 是显式操作 `importV1(projectId, draftId)`，输出新 v2 草稿而非修改旧记录。

- [ ] 对照 `git diff` 和已存在的预览/Mock/制作代码记录可复用及冲突点，确认没有覆盖用户改动；固定 v2 数据形状与 v1 显式导入映射。
- [ ] 先写失败测试：v2 样例合法，v1 原记录不变；同优先级同方法/路径且条件可重叠时拒绝；未知操作符、`__proto__` 等危险键、过深对象、超限规则拒绝；双重编码路径不产生另一个匹配结果。
- [ ] 运行 `npm test --prefix ai-worker` 与 Java 测试，确认新增用例先失败且失败原因正确。
- [ ] 实现 schema、归一化与后端校验；禁止任意脚本/函数，显式区分缺失和 null；共同样例覆盖条件、证据、状态、响应和验证用例。旧草稿不能被自动声明为可用版本。
- [ ] 重跑两套测试和 `git diff --check`，确认通过；仅暂存本任务文件并提交 `feat(simulator): define v2 behavior contract`。

### Task 2: 保留有序采集证据并保护敏感字段

**文件：** 新建 `ai-worker/src/capture-evidence.js`、`ai-worker/test/capture-evidence.test.js`、Java `AiEvidenceEntity.java`、`AiEvidenceRepository.java`、`AiEvidenceService.java` 及测试；修改 `ai-worker/src/server.js`、`AiApiDraftService.java`、`AiWorkbenchController.java`。

**接口：** Worker `selectEvidence(event, scope): EvidenceRecord | null`；Java `listEvidence(projectId, sessionId)` 与按 ID 读取。`EvidenceRecord` 包含有序 ID、方法/路径、获准字段、响应样例、状态、脱敏/截断标记；不含秘密原值。

- [ ] 先写失败测试：多次同一路径读写仍有独立有序观察；只有显式允许的请求字段被保存；密码、Cookie、Authorization、token 类字段始终拒绝；64 KiB 截断标记阻止相关值进入规则依据；请求正文缺失与已脱敏值不能被当作确定状态输入。
- [ ] 测试真实设备写请求仍必须逐次批准；模型请求只收到脱敏证据，不包含包内或响应中的指令作为系统消息。
- [ ] 运行 Worker/Java 目标测试确认失败；实现采集、持久化与 API，现有 API 草稿汇总继续工作，新增证据引用指向真实记录 ID。
- [ ] 重跑目标测试和 `npm test --prefix ai-worker`、Java `mvn test`；仅暂存本任务文件并提交 `feat(ai): persist scoped capture evidence`。

### Task 3: 确定性行为解释器与条件分支

**文件：** 新建 `ai-worker/src/behavior-interpreter.js`、`ai-worker/test/behavior-interpreter.test.js`；修改 `ai-worker/src/preview-mock-sw.js`、`ai-worker/src/behavior-contract.js`。

**接口：** `evaluateBehavior(model, request, state): { response, nextState, ruleId, evidence }` 为同步纯函数；`request` 只包含归一化 method/path/query/body，状态由调用方负责提交。条件支持相等、存在、类型、集合成员、数值范围及 `all/any/not`。

- [ ] 先写失败测试：同一路径未登录/已登录、有效/无效配置返回不同响应；开启保存后读回，关闭再保存后读回；失败响应不变更状态；缺字段、null、类型不符、脱敏占位值、恶意对象键、重复编码及超限请求均失败关闭。
- [ ] 测试等优先级歧义在保存前被拒绝，运行时未知接口返回不可用且绝不转发；响应渲染或状态操作失败时原状态不变。
- [ ] 运行新增测试确认失败；实现纯函数及 v2 契约限制，Service Worker 只完成请求转换、调用和响应映射；v1 预览保持受限兼容。
- [ ] 重跑 Worker 全套测试和浏览器内 Mock 测试；仅暂存本任务文件并提交 `feat(simulator): interpret conditional behavior`。

### Task 4: 单次运行状态的原子持久化

**文件：** 新建 `ai-worker/src/preview-runtime-store.js`、`ai-worker/test/runtime-store.test.js`；修改 `ai-worker/src/preview-mock-sw.js`、`ai-worker/src/preview-bootstrap.js`、现有 `ai-worker/test/preview.test.js`。

**接口：** `withRunState(runId, initialState, evaluate): Promise<{response, revision}>`；同一运行的 IndexedDB 读写事务内同步执行任务 3 的纯函数，返回提交后响应；新运行 ID 创建新数据库/origin。

- [ ] 先写失败测试：保存后刷新及 Service Worker 重启保留值；两个标签并发写按事务序列化；模拟配额/事务中止不返回成功且不改变状态；新运行绝不继承旧状态，设备登出只更新登录字段。
- [ ] 运行 Node 与 Playwright 浏览器测试确认失败；实现 IndexedDB 事务、初始化、修订号与错误映射；事务中不得等待网络或模型。
- [ ] 重跑 `npm test --prefix ai-worker` 及端到端浏览器用例，检查状态和响应一致；仅暂存本任务文件并提交 `feat(simulator): persist isolated run state`。

### Task 5: 候选版本登记与获授权运行租约

**文件：** 新建 Java `AiSimulatorVersionEntity.java`、`AiSimulatorVersionRepository.java`、`AiRunEntity.java`、`AiRunRepository.java`、`AiRunService.java` 及测试；新建 `ai-worker/src/runtime-host.js`、`ai-worker/test/runtime-host.test.js`；修改 `AiWorkbenchController.java`、`AiPreviewService.java`、`ai-worker/src/server.js`、Compose 与运行域配置。

**接口：** Java `registerCandidate(draftId): CandidateView`、`startRun(versionId, authenticatedPrincipal): RunView`、`heartbeat(runId, principal): LeaseView`、`endRun(runId, principal): void`；正常启动仅允许已启用版本，验证服务另持受限内部凭证启动候选实例。Worker `resolveRun(host): ActiveRun | null` 从持久化注册/租约元数据获取，不依赖预览内存 Map。

- [ ] 先写失败测试：未授权、未知、候选或停用版本不能通过普通入口启动；只有验证内部凭证可运行候选；跨项目/跨使用者操作拒绝；停用阻止新运行但已有运行仍可结束；过期或结束后仍打开的页面请求失败；重启 Worker 后有效运行可恢复。
- [ ] 浏览器测试两个运行 origin、Cookie、IndexedDB、缓存和 Service Worker 相互隔离；新运行 ID 不重用；伪造 Host、保留路径、跨域消息被拒绝；外部绝对 URL 与未知 API 不访问真实设备。
- [ ] 运行目标测试确认失败；实现持久化登记及权限适配，缺少终端用户身份时仅已认证制作者试运行。默认 30 秒续期、10 分钟无活动过期、最长 8 小时；显式结束立即失效并尽力清理。
- [ ] 重跑 Java/Worker/Playwright 测试；检查 Compose 解析及本地 `*.localhost` 行为；仅暂存本任务文件并提交 `feat(simulator): manage authorized isolated runs`。

### Task 6: 工作台五视图与人工确认

**文件：** 修改 `web-admin/src/views/AiWorkbenchView.vue`、`web-admin/src/api/ai-workbench.ts`、`web-admin/package.json`；新建 `web-admin/src/views/ai-workbench/{PackageStep,CaptureStep,BehaviorStep,ValidationStep,VersionStep}.vue` 及必要的状态模块与 Vitest + Vue Test Utils 组件测试；修改 Java `AiSimulatorDraftService.java`、`AiWorkbenchController.java`。

**接口：** 项目内制作上下文包含 `projectId, packageId, scopeId, captureId, draftId`；`proposeBehavior` 返回候选及证据/差异，不保存；`confirmBehavior` 创建新不可变 v2 修订。取消提案不修改旧修订。

- [ ] 先写失败测试：五视图前后往返保留上下文；真实设备和模拟预览标签可辨；真实设备写操作审批不因切换视图绕过；AI 提案先展示规则差异及证据和推断标记，取消无持久化副作用。
- [ ] 测试模型未配置时仍可手工编辑和验证；结构化规则/状态编辑为默认，JSON 为高级入口；保存错误、并发版本冲突、缺失证据及项目切换不串数据。
- [ ] 安装与当前 Vue/Vite 兼容的 Vitest、Vue Test Utils、jsdom；运行组件和后端测试确认失败；实现视图和类型化 API，保留原页面已可用的上传、采集、截图及预览能力，不在界面中混入练习配置。
- [ ] 运行 `npm run build --prefix web-admin`、组件测试和 Java 测试，Playwright 检查桌面及 390px 移动视口无重叠；仅暂存本任务文件并提交 `feat(admin): guide simulator authoring workflow`。

### Task 7: 工程验证报告与启用门禁

**文件：** 新建 Java `AiValidationEntity.java`、`AiValidationRepository.java`、`AiValidationService.java` 及测试；新建 `ai-worker/src/simulator-validator.js`、`ai-worker/test/simulator-validator.test.js`；修改 `AiSimulatorVersion*`、`AiWorkbenchController.java`、管理端验证和版本视图。

**接口：** `validateDraft(projectId, draftId): ReportView` 固定包/行为/派生物/解释器哈希；`enableVersion(projectId, draftId, reportId, acknowledgement): VersionView` 仅接收同一修订的通过报告及明确非阻断风险确认。

- [ ] 先写失败测试：初始读取、保存、读回、可用时反向修改、刷新和新运行重置均可断言；前置登录失败、外部请求、未匹配关键 API、状态串用和脚本错误形成阻断报告；异常推断仅形成非阻断警告。
- [ ] 测试 AI 不能自认断言正确，制作者确认期望值；报告关联精确哈希，修订/包/运行时变化后旧报告不能启用新候选；并发重复启用和无风险确认均拒绝。
- [ ] 运行目标测试确认失败；实现受限声明式 Playwright 回放、网络封锁、报告持久化及状态门禁，不执行模型生成脚本。报告页面显示证据、失败定位及修复入口。
- [ ] 重跑 Worker/Java 测试和管理端构建；仅暂存本任务文件并提交 `feat(simulator): validate and enable immutable versions`。

### Task 8: 系统仿真器入口与端到端验收

**文件：** 新建 `web-simulator/src/views/SimulatorRunView.vue`、`web-simulator/src/api/simulator-runs.ts` 与 Playwright E2E；修改 `web-simulator/src/router/index.ts`、`ai-worker/src/runtime-host.js`、运行部署配置、`docs/simulator-authoring.md`。

**接口：** `/simulators/:versionId` 是受限试点入口，调用任务 5 的授权启动/心跳/结束 API；嵌入独立 origin。旧 `/runtime` 演示入口保持原样。

- [ ] 先写失败 E2E：仅获授权制作者可以打开启用版本；普通使用页面无 AI/规则/评分；跨域消息必须匹配 origin、source、run ID、schema；结束立即不可继续保存；重新启动回到初始状态。
- [ ] 使用仓库演示 ZIP 完整走通上传、读写采集、制作、验证、启用和启动；启动后关闭 AI 模型与真实设备服务，重复开启/关闭/读回并刷新；两个运行相互隔离。没有真实 AucSeek 包时明确不验证其兼容性。
- [ ] 运行 E2E 确认先失败；接通新路由、生命周期和文档。测试桌面/移动布局、无空白画面、Service Worker 已接管、无横向溢出和跨域拒绝。
- [ ] 运行 `npm test --prefix ai-worker`、`mvn test`（`skilllab-admin`）、`npm run build --prefix web-admin`、`npm run build --prefix web-simulator`、E2E、`git diff --check`；仅暂存本任务文件并提交 `feat(simulator): launch system-hosted versions`。

## 交付复核

- [ ] 对照设计文档逐条核查：上传与采集、AI 仅制作、证据标记、条件状态、五视图、验证门禁、系统内隔离运行、退出重置、安全限制及无练习逻辑。
- [ ] 做全分支复核，尤其检查共享密钥/H2 试点边界、Service Worker 接管顺序、IndexedDB 原子性、未知请求和外部出站行为。
- [ ] 报告实际验证命令和结果，保留所有原有未提交工作及数据；不得把受限试点描述为可公开考生使用的生产部署。
