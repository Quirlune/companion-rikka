# 在 Codex 云端电脑继续开发 velune

核查日期：2026-10-10。本页说明 Codex Cloud 开发环境，不是应用部署服务器，也不是把 APK 放网盘。

## 已核实的项目状态

- GitHub 私有仓库：`https://github.com/Quirlune/companion-rikka`，默认分支 main。
- 本轮开始时，本地 HEAD 和远程 HEAD 均为 `1c7036d14389d94336ba7c1da1c6f9ce1242533d`，提交内版本为 **0.42-preview/code202**。
- 本地工作区实际已到 **0.57-preview/code225**，开始检查时有246项变动（修改130、删除24、未跟踪92）；后续文档变动会增加这个数。
- 因此“已有 GitHub 仓库”不代表最新源码已上云。只关联旧远程仓库会从旧版开始。
- 本轮只核查仓库并准备文档，尚未提交/推送本地积累，也未创建/发布 Codex Cloud 环境。此记录不是云端已成功配置的证据。

## 推荐交接方式

1. 先审查并同步当前源码和文档到上述私有仓库。应包含未跟踪的 Kotlin 文件、删除的旧磁贴文件以及新文档，不能只推送这轮 README。保持原仓库地址也可以使用 velune 应用名，无需重命名远程。
2. 在 Codex/ChatGPT 桌面或网页新任务选择 **Work in → Cloud → Select environment → Create environment**，选择这个 GitHub 仓库。如果要求授权，授予这个私有仓库所需访问。
3. 让环境准备会话按下面的“设置指令”安装并验证依赖。确认抓取的是同步后的提交，不是0.42。
4. 阅读实际设置与检查结果，保存，然后 **Publish**。看到 **Environment published** 才表示新环境可用于任务。
5. 从已发布环境开始任务。代码与文档由仓库接手；聊天历史、手机数据库、Windows SDK 和本地签名不会自动变成云文件。

官方来源：[Codex Cloud](https://learn.chatgpt.com/docs/cloud)、[Cloud environments](https://learn.chatgpt.com/docs/environments/cloud-environments)。按当前官方文档，新任务使用已发布环境的独立工作区；设置改动需重新发布，已有任务保留各自状态。旧版 Cloud 的 setup/maintenance 说明是另一套入口，不应与新版混用。

## 可直接给环境准备会话的指令

```text
准备 Quirlune/companion-rikka（应用名 velune）的 Android/Kotlin 开发环境。
先读根 AGENTS.md、docs/ARCHITECTURE.md、docs/DEVELOPMENT.md。
核对 app/build.gradle.kts 是0.57-preview/code225或更新的明确版本；若仍为0.42，停止并报告尚未同步，不在旧源码上继续。
初始化固定 Git 子模块；安装 JDK21、Gradle wrapper所需依赖和构建文件声明的 Android SDK。
当前 app compile SDK37 minor2，library API37；核对 sdkmanager 中 platforms;android-37.0 与 platforms;android-37.2。
只安装实际需要的组件；网络需要 GitHub、Gradle、Google Maven/Android SDK、Maven Central、JitPack（以实际下载域名为准）。
不要读取或要求真实模型 API key；不要更改包名、版本、业务代码或降级工具链来伪造构建通过。
先运行一个相关核心测试，再尝试 :app:assembleDebug；报告实际命令、失败原因与构建产物。
把可复现的安装过程记录在环境 Install script；此 Android 项目普通核心开发无需后台服务。
说明云端 debug APK的签名是否与原安装一致，无法取得原签名时不得声称可直接覆盖。
最终列出环境已具备能力和未验证部分；未实际运行 Android 模拟器就不要声称界面已验收。
```

## 给后续开发任务的开场指令

```text
继续维护 velune。先读 AGENTS.md、ARCHITECTURE 和 DEVELOPMENT；制作扩展再读 AI_IMPORT_GUIDE 与 API_REFERENCE。
从当前仓库事实出发，历史记录不作为新功能默认架构。
完成下面的需求，优先复用现有资源、调用和卡片；提示词必须完整可编辑。
保护现有分支、宏历史、记忆来源和断点。仅做必要定向检查，交付实际结果与未验证项。
本次需求：<填写这里>
```

## 适合放云端的部分

源码编辑、资源包制作、核心单元测试、编译和代码审查都可作为云端准备目标。Android 模拟器、硬件加速、截图交互是否能用，需在所选环境实际验证；不把它当作已获得的能力。手机体验仍可用构建出的 APK 验收。

不要上传聊天数据库、API key、调试日志、`.local`、`.env`、签名文件、local.properties 和 Windows工具链。网络秘密通过环境提供的功能管理；普通资源示例不含它们。源码保存继续使用 Git，环境保存状态不能代替提交与备份。
