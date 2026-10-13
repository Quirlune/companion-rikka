# velune

Android 对话与角色工作流应用，复用 RikkaHub 原生前端，使用独立 Kotlin 核心管理提示词、调用、分支、扩展、卡片与记忆。

当前版本：**0.76-preview / versionCode244**，修订94（wire37/execution28，SQLite1）。集成独立 Agent 工作台、离线作者资料、BizyAir 插图、暂停继续、历史纯文本前缀、独立收藏副本及统一日志；输入框半透明，三点悬浮卡片直接展示重要插件前端，正文文字与插图可分别设置宽度。构建与测试记录以 [最新交接](docs/HANDOFF.md) 为准。

[下载最新版 APK](https://github.com/Quirlune/companion-rikka/releases/tag/v0.76-preview)；[外部 AI 作者包](https://github.com/Quirlune/companion-rikka/releases/tag/v0.75-preview)沿用0.75版。将作者包或其中的完整中文文案交给支持文件阅读的 AI，可指导它制作本应用可导入的资源 ZIP；生成文件与运行脚本取决于平台能力。作者包本身不是扩展，不能直接导入应用。

兼容包名和资源格式继续使用 `companion`；应用名称为 velune。插件联网目前独立直连，不继承应用代理。Agent 模型推理需要登录/联网；系统结束应用进程后，不自动重放工具或付费请求。实际手机滚动、闪退与服务端兼容性仍应结合设备体验与日志判断。

## 给模型的入口

- **交给外部 AI**：[自包含作者说明包](docs/authoring-kit/)，含完整中文文案、真实格式、示例和 ZIP 构建工具。
- **理解架构与美化**：[当前架构导览](docs/AUTHORING_ARCHITECTURE.txt) · [正文与卡片美化语法](docs/APPEARANCE_GUIDE.txt)。这两份也可通过内置 Agent 的 knowledge 工具读取。
- **制作扩展**：[AI 导入与扩展指南](docs/AI_IMPORT_GUIDE.txt) → [机器字段参考](docs/AI_IMPORT_API_REFERENCE.json) → [完整示例与工具](docs/examples/)。
- **编写联网插件**：[插件宿主 API v2](docs/PLUGIN_API.md)，或在配置工作台查询 `pluginCapabilities`。
- **修改程序**：[AGENTS.md](AGENTS.md) → [当前架构](docs/ARCHITECTURE.md) → [开发维护手册](docs/DEVELOPMENT.md)。
- **转到 Codex 云端开发**：[云端接手说明](docs/CODEX_CLOUD.md)。
- **查实际交付证据**：[HANDOFF](docs/HANDOFF.md)，以最近条目为准。历史笔记不是当前设计规范。

主调用负责正文与独立正文外观。扩展关联调用组，调用组包含调用与独立卡片；卡片从输出提取字段，可放正文、侧栏、输入框上方或顶部菜单。记忆和自动选项是有专用设置页的额外扩展，提示词仍在完整编辑器内可见可改。

## 构建

准备 JDK21、项目要求的 Android SDK，初始化子模块，再使用仓库内 Gradle wrapper。详细版本和最少检查见 [DEVELOPMENT](docs/DEVELOPMENT.md)。

```sh
git clone --recurse-submodules https://github.com/Quirlune/companion-rikka.git
cd companion-rikka
bash ./gradlew :core:test --tests 'dev.gray.companion.core.SummaryXmlTest'
bash ./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat`。本地与云端默认 debug 签名可能不同，不能保证覆盖安装；不要为升级而卸载有数据的应用。普通开发不需要真实模型密钥。

## 来源

基于 [RikkaHub](https://github.com/rikkahub/rikkahub)，保留上游历史与 AGPL-3.0 许可证。初始上游提交 `4391d5a5e4c9dd19f4713e3a759b4f6b325ae58b`。原介绍保留在 [UPSTREAM_README](docs/UPSTREAM_README.md)。上游发布工作流归档于 `.github/upstream-workflows/`，不要顺手启用发布。
