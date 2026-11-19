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

## 启用在线卫星图

**开源仓库和 GitHub Releases 提供的 APK 均未附带 MapTiler API Key。** 直接安装公开 APK 可以离线使用。启用在线卫星图时，使用自己的 Key 从源码重新构建 APK，无需在手机里填写。

1. 登录 [MapTiler Cloud](https://cloud.maptiler.com/)，进入 **API keys**，创建或编辑一把供云上航程使用的 Key。免费套餐的 Key 数量以控制台为准。
2. **Name** 可填 `CloudVoyage Android`。**Allowed user-agent header** 填 `CloudVoyage-Android`（区分大小写），**Allowed HTTP Origins** 留空。保存后复制 Key。 [MapTiler API Key 配置](https://docs.maptiler.com/guides/developer/credentials/api-key/)
3. 在仓库根目录用 PowerShell 7 运行以下命令。替换路径，按上文准备自己的签名库。提示出现时粘贴 Key，输入内容会被遮盖。

   ```powershell
   $env:CLOUD_VOYAGE_MAPTILER_KEY = Read-Host '输入自己的 MapTiler API Key' -MaskInput
   try {
       .\build.ps1 -SdkRoot '<Android SDK 目录>' -JavaHome '<JDK 17 目录>' `
         -KeyStore '<自己的签名库路径>' -PasswordFile '<签名密码文件路径>'
   } finally {
       Remove-Item Env:CLOUD_VOYAGE_MAPTILER_KEY -ErrorAction SilentlyContinue
   }
   ```

4. 安装 `build/CloudVoyage.apk`，联网打开航图。出现 **MapTiler** 署名时，表示在线瓦片正在显示。网络不可用时，地图会显示内置离线底图。

Key 会随你构建的 APK 分发，安装者共享相同的 MapTiler 用量。请保留地图上的署名。同一签名可覆盖升级。自己的签名和公开版签名通常不同，直接覆盖会失败。卸载公开版会清除该安装中的本地飞行记录。

## 代码与素材

- `src/main/java/app/cloudvoyage` 包含航程、计时和存储逻辑，以及航图、值机与日志界面。
- `src/main/assets` 包含离线地球纹理和四套分时段舷窗图片。使用 `python tools/globe_detail.py <NASA 六月 21600×10800 JPEG> src/main/assets` 可重建地球纹理，需要 Pillow。
- 项目源码与文档采用 [MIT](LICENSE)。地图影像来源、服务署名与品牌标识见 [第三方素材说明](NOTICE.md)。

项目的飞行专注体验参考了 FocusFlight，客舱剖面参考了公开的波音 777-300ER 航司座位图。云上航程为独立的 Android 项目。
