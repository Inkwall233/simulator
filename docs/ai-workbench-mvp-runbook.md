# AI 工作台 MVP 启动手册

## 部署前配置

1. 复制 deploy/ai-workbench.env.example 到仓库根目录的私有文件（例如 .env.ai），替换 SKILLLAB_AI_ADMIN_KEY 和 AI_WORKER_SHARED_SECRET 为两个不同的随机长密钥。
2. 将 AI_ALLOWED_TARGET_ORIGINS 改成明确授权访问的设备 origin，例如 http://192.168.1.20,https://nvr.lab.example。值必须是精确 origin，不包含路径。设备在内网时，Worker 容器所在主机必须能路由到设备。
3. 设备如依赖 WebSocket，先审查其用途，再将相应 HTTP/HTTPS origin 加入 AI_ALLOWED_WEBSOCKET_ORIGINS。默认阻止 WebSocket；显式放行后，消息内容不经过逐次写操作审批。
4. 可选配置 AI_MODEL、AI_OPENAI_API_KEY 和兼容的 AI_OPENAI_CHAT_COMPLETIONS_URL。不配置时，项目、ZIP 上传、人工浏览和接口采集仍可使用，自然语言 AI 动作会返回“模型未配置”。
5. 确认模型服务的数据处理策略符合设备信息与流量的保密要求。模型密钥只放在私有部署环境变量中。

## 启动

从仓库根目录执行：

    docker compose --env-file .env.ai -f deploy/docker-compose.yml -f deploy/docker-compose.ai.yml up -d --build

AI Worker 只在 Compose 网络内暴露，不映射宿主机端口。Java 后端使用 H2 文件数据库保存项目和会话元数据；静态包保存在同一个持久化数据卷。该默认存储适合单机 MVP，不应作为多副本生产数据库或对象存储的替代品。

## 使用

1. 构建并发布 web-admin 后打开 /ai-workbench。
2. 输入 SKILLLAB_AI_ADMIN_KEY。
3. 创建设备项目，上传包含 index.html 的 ZIP。
4. 输入白名单内的真实设备 URL 并连接。HTTP 设备会显示明文传输提示。
5. 在远程浏览器画面中点击页面；需要输入时先点设备页目标输入框，再在下方输入框发送文本。输入内容不会被放进工作台时间线或传给 AI。
6. 非只读请求和路径疑似修改配置的 GET 请求默认暂停；在接口采集区逐次批准或拒绝。
7. 采集到接口后点击“生成 Mock 草稿”，检查接口映射与响应样例。上传包后点击“创建 Simulator 草稿”生成不可变的 v1 版本；可以预览或下载 JSON。当前版本还没有把该 JSON 加载进 Simulator。
8. 关闭会话会清理 Worker 的浏览器上下文和临时 Cookie；闲置超过配置时间后自动结束。

## 本版边界

- 当前版本完成项目元数据、ZIP 接收与受限检查、隔离浏览器会话、人工接管、请求清单、脱敏 JSON 响应样例、逐次写请求确认和基于观察流量的持久化 API Mock 草稿。管理员可以把 API 草稿与一个静态包版本组合成不可变的 SimulatorDraft v1，并按版本读取或下载。
- 模型动作只支持每条自然语言指令触发至多一个受限浏览器动作；没有执行任意代码的工具。
- 当前浏览器会话和网络事件存放在 Worker 内存中；Worker 重启后会话终止，已持久化的项目、包、会话记录、API 草稿和 SimulatorDraft 版本仍保留。ZIP 和 SimulatorDraft 尚未部署/加载到 Simulator；Overlay 执行、Mock 运行时接入、回放与发布属于后续里程碑。
- 仅配置应用层域名白名单还不构成生产网络隔离。正式开放前，应对 Worker 出站流量做网络层限制，尤其要阻断云元数据、Docker 管理面和未授权私网目标；仅通过可信管理员配置 AI_ALLOWED_TARGET_ORIGINS。
- H2 文件库和本地包卷是单机 MVP 默认值。生产部署需接入备份、监控、访问控制和适合多实例的数据库/对象存储。

## 本地开发

Windows 本地开发可从仓库根目录运行 `start-all.ps1`，或双击 `start-all.bat`。脚本会生成摄像机/NVR 演示设备静态 ZIP，启动演示设备、三个前端、skilllab-admin 后端和 AI Worker；仅在 Worker 当前 Playwright 版本的 Chromium 或 Headless Shell 缺失时才会下载浏览器。

首次运行时，脚本会把 `deploy/ai-workbench.env.example` 复制为仓库根目录的 `.env.ai` 并停止启动。编辑该文件中的 `SKILLLAB_AI_ADMIN_KEY`、`AI_WORKER_SHARED_SECRET` 和 `AI_ALLOWED_TARGET_ORIGINS` 后重新运行。使用本地演示设备时，白名单和工作台 URL 都填 `http://127.0.0.1:19090`；两个密钥应不同。设备 origin 必须与连接 URL 的协议、主机和端口完全匹配，不要包含路径。`.env.ai` 已加入 `.gitignore`。

管理端入口为 `http://localhost:5174/ai-workbench`，Vite 会将 `/api` 请求代理到本机 8080 端口。演示设备地址为 `http://127.0.0.1:19090`，账号 `admin`，密码 `demo123`；上传包为 `ai-demo-device/device-package.zip`。启动脚本会检查各服务的健康响应，端口已有其他程序占用时不会误报为就绪。执行 `start-all.ps1 -Stop` 可停止这些服务。若改测真实内网设备，应确认运行 Worker 的电脑能访问设备；不要把白名单扩大为整个私网段。
