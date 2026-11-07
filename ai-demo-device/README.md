# SkillLab 摄像机/NVR 演示设备

这是一个本地运行的虚构设备 Web 控制台，用来验证 AI 工作台的远程浏览、接口采集和 Mock 草稿流程。它不会连接真实设备；API 状态只保存在 Node 进程内，重启服务会重置。

## 启动

在仓库根目录运行 `start-all.ps1`。脚本会启动此服务，并在 `ai-demo-device/device-package.zip` 生成可上传的静态包。

演示设备地址为 `http://127.0.0.1:19090`。在 AI 工作台连接时请使用这个完整 origin；`localhost` 与 `127.0.0.1` 不同，不能互换。

也可以单独启动：

```powershell
cd ai-demo-device
npm start
```

## 演示账号

- 用户名：`admin`
- 密码：`demo123`

## 页面与接口

- 登录：`POST /api/login`
- 当前会话：`GET /api/session`
- 设备参数：`GET /api/device`、`PUT /api/device`
- 录像列表：`GET /api/recordings?range=7d`
- 录像播放：`POST /api/recordings/{id}/play`
- 退出：`POST /api/logout`

这些 API 仅用于本地仿真验证。工作台会暂停需要审批的写请求；在接口采集区确认后，演示页面会继续执行。

## 上传包

工作台创建项目后，上传 `device-package.zip`。这个 ZIP 是静态页面成果物；本地演示服务器另外提供同路径的 API，因此 Worker 可以浏览和采集接口。当前 MVP 尚未把上传的 ZIP 自动部署到 Simulator。
