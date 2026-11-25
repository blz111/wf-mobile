<div align="center">

# Warframe 生存

**通过 Shizuku 在 Android 虚拟屏上运行 Warframe，预览画面并自动确认遗物。**

[运行要求](#运行要求) · [使用指南](#使用指南) · [源码构建](#源码构建) · [问题反馈](https://github.com/blz111/wf-mobile/issues)

</div>

## 功能

| 功能 | 说明 |
| --- | --- |
| 虚拟屏 | 将 Warframe 启动到 1280 × 720 的独立虚拟屏。主屏锁定时，后台续跑取决于设备系统和 Shizuku 权限。 |
| GPU 预览 | 主页面显示小屏，轻触可放大到横屏。放大后可手动操作，支持多指触摸。 |
| 识别核桃 | 每 5 秒检查遗物选择页，装备当前选中的遗物，再识别并确认装备弹窗。 |
| 手动优先 | 自动点击前会等所有手指抬起，再重新检查画面。 |
| 开核桃数 | 可设置本次检测的数量上限。确认装备且页面关闭后记 1 次，达标后结束检测。 |
| 截图与震动 | 保存原尺寸 PNG，或单独关闭和恢复 Warframe 的震动。 |

## 运行要求

| 项目 | 要求 |
| --- | --- |
| Android | Android 14 或以上（API 34+） |
| Shizuku | 13 或以上，通过无线调试或 ADB 启动，并授权给助手 |
| Warframe | 安装 Android 版，识别使用简体中文界面 |

## 使用指南

1. 安装助手 APK。打开应用，点 **连接 Shizuku** 完成授权。
2. 点 **启动 Warframe**。轻触小屏可放大并手动操作，点右上角小叉回到主页面。
3. 勾选 **识别核桃**。需要限制数量时，填写 **开核桃数**，填 0 表示不限。
4. 点 **开始任务**。助手在看到 **装备以执行任务** 时点击它，确认框出现后再点 **是**。
5. 点 **停止** 可随时结束页面检测。点 **关闭游戏** 会结束虚拟屏上的游戏并释放虚拟屏。

主页面的 **保存截图** 会将 1280 × 720 PNG 保存到相册的 `Pictures/WarframeSurvival`。**关闭游戏震动** 只修改 Warframe 的震动设置，再点 **恢复游戏震动** 可重新启用。

## 源码构建

准备 JDK 17 或以上和 Android SDK 36，并将 `ANDROID_HOME` 指向 SDK 目录。Windows PowerShell 下运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

调试 APK 生成在 `app/build/outputs/apk/debug/app-debug.apk`。Google Maven 访问受限时，可指定镜像：

```powershell
.\gradlew.bat :app:assembleDebug '-PandroidMavenMirror=https://maven.aliyun.com/repository/google'
```

## 实现参考

- [MAA-Meow](https://github.com/Aliothmoon/MAA-Meow) 提供了 Shizuku 用户服务、虚拟屏、GPU 预览和触摸转发的实现参考。
- [Shizuku](https://github.com/RikkaApps/Shizuku) 为助手提供经过用户授权的 Android 系统接口。

Warframe 官方限制无人值守的自动游玩，可能对相关账号采取处罚。[Warframe 官方说明](https://support.warframe.com/hc/en-us/articles/200287090-How-to-deal-with-Cheaters-AFKing-trolling)
