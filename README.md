# 云上航程 · CloudVoyage

把一段专注时间变成一趟航班。选航线、挑座位、撕下登机牌，然后在跟随飞机的三维航图或舷窗前开始专注。支持 Android 8.0 及以上的手机和平板。

[下载 APK](../../releases/latest) · [MIT 许可证](LICENSE)

## 功能

- **定时与自由航班** — 定时模式可从半小时、一小时、两小时和三小时开始，也可自定义。自由模式在设置的最长时间内累计专注时长，降落时据此估算目的地。
- **三维航图与舷窗** — 规划时在地图上点选机场。飞行时镜头跟随飞机，拖动或缩放后停手五秒自动回位。全屏可用，舷窗景色随手机当地时间切换。
- **模拟值机** — 在包含头等舱、商务舱和经济舱的客机剖面上选座，查看座位与机翼的位置，再撕下登机牌起飞。
- **本地飞行日志** — 按日、周、月或指定日期查看专注区间、累计时长和历史机票。
- **手机与平板布局** — 规划和飞行页按可用窗口宽度切换单栏与双栏。窄屏可横向浏览放大的座位图。

## 安装

从 GitHub Releases 下载 APK 后，用 Android 的文件管理器安装。系统可能会要求允许该文件管理器安装应用。专注记录保存在设备本机。公开 APK 开箱使用离线航图，在线卫星图可在自行构建时启用。

应用包名为 `app.cloudvoyage`。覆盖安装要求签名证书一致。更换签名后，卸载旧版会清除该安装中的本地专注记录。

## 从源码构建

环境为 Windows、PowerShell 7、JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。应用使用原生 Android Views、Canvas 与 SQLite。

```powershell
.	est.ps1 -JavaHome '<JDK 17 目录>'

.uild.ps1 -SdkRoot '<Android SDK 目录>' -JavaHome '<JDK 17 目录>' `
  -KeyStore '<签名库路径>' -PasswordFile '<签名密码文件路径>'
```

构建脚本使用名为 `cloudvoyage` 的签名别名，默认生成 `build/CloudVoyage.apk`。可以用 JDK 自带的 `keytool` 创建自己的签名库，密码文件只放签名库密码。签名库与密码文件应存放在仓库外。

```powershell
& '<JDK 17 目录>/bin/keytool.exe' -genkeypair -storetype PKCS12 `
  -keystore '<签名库路径>' -alias cloudvoyage -keyalg RSA -keysize 3072 -validity 10000
```

测试覆盖专注结算、历史分组、航线投影、地图跟随和窗口布局规则。各机型的实际排版与触控可通过真机或 Android 模拟器验证。

## 可选的在线卫星图

构建前设置进程级环境变量 `CLOUD_VOYAGE_MAPTILER_KEY`，应用便会请求 MapTiler 的 `satellite-v2` 瓦片。MapTiler 控制台中的 Allowed user-agent header 可填 `CloudVoyage-Android`，Allowed HTTP Origins 留空。Key 会随 APK 分发，所有安装者共用该 Key 的用量。网络不可用时，地图使用内置离线底图。 [MapTiler API Key 配置](https://docs.maptiler.com/guides/developer/credentials/api-key/)

## 代码与素材

- `src/main/java/app/cloudvoyage` 包含航程、计时和存储逻辑，以及航图、值机与日志界面。
- `src/main/assets` 包含离线地球纹理和四套分时段舷窗图片。使用 `python tools/globe_detail.py <NASA 六月 21600×10800 JPEG> src/main/assets` 可重建地球纹理，需要 Pillow。
- 项目源码与文档采用 [MIT](LICENSE)。地图影像来源、服务署名与品牌标识见 [第三方素材说明](NOTICE.md)。

项目的飞行专注体验参考了 FocusFlight，客舱剖面参考了公开的波音 777-300ER 航司座位图。云上航程为独立的 Android 项目。
