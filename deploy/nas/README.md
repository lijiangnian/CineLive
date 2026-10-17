# 极空间家庭更新服务

本方案不需要公网端口或 VPN。当前家庭 NAS：192.168.31.67。请在路由器固定这个 IP。

1. 在极空间创建 `CineLive更新` 目录，将 compose.yaml、default.conf 和 www 目录放进去。
2. 极空间 Docker 中创建 Compose 项目，选择本目录的 compose.yaml，启动。
   如有终端，也可在此目录运行 `docker compose up -d`。
3. 将发布包里的 APK 和专用的 NAS manifest.json 放入 www。
4. 确保容器对 www 目录及文件有读取权限（目录 755、文件 644；极空间文件夹权限中允许容器读取）。
5. 在家中手机浏览器打开 `http://192.168.31.67:18080/manifest.json`；应看到 JSON 版本信息，而不是网页登录页。
6. APP 设置 → 版本 → 更新地址。主要地址默认为 GitHub，备用地址默认为本 NAS。
   只想使用 NAS 时，将主要地址改成上述地址，并清空备用地址。

后续更新：先上传所有新版本 APK，再最后替换 manifest.json。不能只上传 APK；清单包含版本、包型、大小和 SHA-256。
请勿将 GitHub manifest.json 原样用于 NAS；其中 APK 下载地址指向 GitHub，需要使用发布工具生成的 NAS 清单。
版本号必须递增，APK 必须沿用签名。更新不需要重启容器。

故障：Docker 镜像拉取失败时，需先通过极空间支持的镜像导入方式取得 nginx 镜像；不是 APP 的源维护代理在给 NAS 翻墙。
IP 变更时，同时修改 compose 端口绑定和 APP 地址。请勿把这个只读服务映射到公网。
本次只能验证配置与清单结构，云端无法访问家中 NAS；必须完成第 5 步才能确认实际部署成功。
