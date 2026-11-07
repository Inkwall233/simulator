# 制作者登录

当前仓库原本没有平台用户体系。受限试点使用部署时配置的单个制作者账号；登录后由后端会话识别身份。设备页面的登录属于被模拟设备，不能用于平台授权。匿名制作请求会被拒绝。此账号不代表考生身份，也不提供多用户角色或项目权限。

配置 SKILLLAB_CREATOR_USERNAME 和 SKILLLAB_CREATOR_PASSWORD_HASH。后者必须是 BCrypt 哈希，不能填写明文密码。通过私有环境文件注入，不要提交密码或哈希。SKILLLAB_ADMIN_ORIGIN 必须与管理端浏览器 Origin 完全一致，本地默认 http://localhost:5174，Compose 默认 https://skilllab-admin.xyz。生产 Compose 启用 Secure、HttpOnly、SameSite=Strict 会话 Cookie；本地 HTTP 开发环境不启用 Secure。写请求必须来自配置的 Origin，退出登录立即使会话失效。

本试点会话保存在单进程内存中，后端重启需重新登录；多副本部署需接入共享会话存储。公开考生使用前还需独立的终端用户认证、项目授权、审计和生产运行域隔离。
