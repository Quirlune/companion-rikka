# velune

Android 对话与角色工作流应用，复用 RikkaHub 原生前端，使用独立 Kotlin 核心管理提示词、调用、分支、扩展、卡片与记忆。

当前验收版本：**0.66-preview / versionCode234**（wire34/execution26，SQLite1），修订78。九域审查发现与此前三项故障已纳入修复；原生输出平滑采用用户授权的替代实现，在展示端逐帧合并完整快照，并为追加文字淡入（未找到适用的原版 Android 实现）。APK 构建、同签名安装及隔离 API36 模拟器中的应用到本地合成 HTTPS 请求与关键界面验收通过；真实云模型和实体手机体验未验证。兼容包名和资源格式继续使用 `companion`；应用名称为 velune。

## 给模型的入口

- **制作扩展**：[AI 导入与扩展指南](docs/AI_IMPORT_GUIDE.txt) → [机器字段参考](docs/AI_IMPORT_API_REFERENCE.json) → [完整示例与工具](docs/examples/)。
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
