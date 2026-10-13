# velune 代码维护与模型接手手册

当前版本0.76-preview/code244，修订94（wire37/execution28，SQLite1）。本轮收紧自动选项点击锚点，补编辑发送准备异常收尾，修正无可见回复轮次编辑时的检查点回退；保留Rev93内置Agent、插件宿主、生图、独立收藏、历史前缀与半透明输入框。APK及隔离模拟器验收结果、已知冷启动ANR和未验证范围见HANDOFF最新条目。

先读根AGENTS.md和ARCHITECTURE.md。作者资料入口为docs/authoring-kit/START_HERE.txt，单文件说明VELUNE_EXTERNAL_AI_ONE_FILE.txt可交给外部AI；六份知识文档同时随APK打包供内置Agent读取。schema目录由docs/examples/AuthoringTools.java生成。更新知识后同步作者包和单文件合辑，再构建APK；不要把作者工具包ZIP当作可导入资源包。

## 接手流程

1. 检查工作区与分支：`git status --short`、`git log -1 --oneline`。本地变动不等于远程已同步。
2. 读本次需求对应的当前架构段，找到下表里的单一权威入口；不要从旧截图倒推模型。
3. 先区分资源配置就能完成的扩展与需要宿主代码变更的功能。状态栏通常使用调用组、输出引用、XML 卡片和 CSS 即可。
4. 修改前列清受影响数据与兼容边界。保存历史/来源/断点的变更应检查冻结版本；仅界面样式不需要改变 execution。
5. 实现后集中做最少必要检查；交付可使用版本，注明未验证的体验部分。更新当前文档与 HANDOFF，避免把每轮聊天原文重新塞进 AGENTS。

## 文件地图

下表 core 文件均在 `core/src/main/kotlin/dev/gray/companion/core/`，Android 文件在 `app/src/main/java/`。

| 需求 | 核心入口 | Android 入口/注意 |
|---|---|---|
| 新资源字段 | Models、ExtensionModels、CardModels、LibraryFormat、LibraryValidator | SqliteLibraryRepository、对应编辑器；同时检查导入/撤回/冻结资源 |
| 新行为 | Actions、ActionDescriptor/Registry、RunPlan、OutcomeValidation/Publication | 原生调用编辑器按描述符显示；不要另开执行器 |
| 提示词和宏 | PromptCompiler、MacroReferences、PromptReferenceLanguage、HistoryReferences | PromptBlocksEditor/提示词引用编辑器，必须显示实际发送结构 |
| 用户输入宏 | UserInputMacros、Actions、RuntimeSnapshots | UserMacroBehaviorEditor/UserMacroInput；只在新USER消息准入时展开，显示变换不改草稿 |
| 隔离配置工作台 | ConfigurationWorkspaces、ConfigurationContext、ConfigurationAuthoringSchema | ConfigurationToolGateway/ConfigurationWorkspacePage；独立草稿锁，提交才获取运行锁，BUSY不停止聊天 |
| 插件宿主能力 | PluginCodeExecutor、PluginCapabilities、CompletedWorlds | QuickJsPluginExecutor/PluginHostRuntime/PluginHttpTransport；PluginAccessStore和原生权限面板管理本机授权与密钥，v2禁止纯重放 |
| 资源导入与移除 | ResourceArchiveImport、ConfigurationCommands、ResourceEditCommands、ResourceUsageIndex、ResourceKindCatalog | ConfigurationAdmission/导入预览；原子保存和撤回 |
| 卡片与 XML | CardModels、XmlCardData、CardDisplays、CardMarkup、CardLayoutCommands | ui/CardResourcesEditor、CardDefinitionEditor、CardLayoutUi |
| 主正文外观 | BodyAppearanceTemplates | ui/BodyAppearanceEditor、BodyAppearanceMarkup、StyledBody |
| 全局扩展启停 | PluginParticipation、ExtensionLiveMigration、PromptPackages、CapturedResourceClosure | AppController、ui/ExtensionPages |
| 记忆 | GlobalMemory、MemoryPlugins、MemoryPromptScope、HistoryMemory、HistoryReferences、SummaryXml | ui/MemorySettingsPage、HistoryForms，保存对照必须读精确 source refs |
| 自动选项 | AutomaticOptions、AutomaticOptionsXml | AutomaticOptionsController、ui/AutomaticOptionsSettingsPage、CompanionChatPage |
| roll/分支/恢复 | ConversationPaths、MessageOperations、BranchReplay、CompletedWorlds、RuntimeSnapshots、WorkflowEngine | AppController 和 me/rerere/rikkahub/companion/ 投影 |
| 网络与模型 | ModelGateway、ModelRequestMessages、NativeProviderConfiguration | 原有 provider/凭据模块、CompanionNetworkClient；不要把 API key 写进 Library |
| 存储 | LibraryFormat、ExecutionSemantics、回执与快照模型 | dev/gray/companion/data/SqliteLibraryRepository.kt |

找文件用 `rg --files`，查调用方用 `rg`。上表是入口，不保证每项只改一个文件。当前 UI 的类名可能保留 Companion/Rikka，这是兼容与复用，不等于界面名称未更新。

## 构建环境

| 项目 | 当前要求/来源 |
|---|---|
| JDK | 21，core 的 jvmToolchain(21)，目标字节码17 |
| Gradle | wrapper 9.6.0，见 gradle/wrapper/gradle-wrapper.properties |
| AGP / Kotlin | 9.4.0 / 2.4.10，见 gradle/libs.versions.toml |
| Android app compile | API37、minor2；本地包 platforms;android-37.2 |
| Android library compile | API37；本地包 platforms;android-37.0 |
| 运行最低 | Android26；target37 |
| 本地已用 Build Tools | 36.0.0；未在工程中固定版本，跟随 AGP 实际解析要求 |
| 子模块 | material3/material-color-utilities，按仓库固定提交初始化 |

版本以构建文件为准。不要为云端缺包擅自降级 AGP/SDK 或改功能代码。不存在必须自行编译的已启用 CMake 任务时，不要照旧文档盲装任意 NDK。

```sh
git submodule update --init --recursive
java -version
# 使用已安装 SDK，在云环境设置 ANDROID_HOME（不要写个人 Windows 路径）
sdkmanager --list_installed
# 如缺失，先通过 sdkmanager --list 确认可用 ID，再安装：
sdkmanager 'platform-tools' 'platforms;android-37.0' 'platforms;android-37.2' 'build-tools;36.0.0'
bash ./gradlew --version
```

SDK 许可由环境所有者按安装工具流程接受。SDK 路径可用环境变量或被忽略的 local.properties；不要覆盖已有签名配置。Gradle 缓存未准备时不要传 `--offline`。代理环境 Maven/Google/JitPack 下载失败应报告实际缺失依赖与网络策略，不能宣称云构建通过。

## 最少验收

```sh
# 示例：只验证本次触及的摘要契约
bash ./gradlew :core:test --tests 'dev.gray.companion.core.SummaryXmlTest' --console=plain
# 示例：卡片数据与归属
bash ./gradlew :core:test --tests 'dev.gray.companion.core.XmlCardDataTest' --tests 'dev.gray.companion.core.CardResourceIntegrationTest'
# 示例：选项与开关
bash ./gradlew :core:test --tests 'dev.gray.companion.core.AutomaticOptionsXmlTest' --tests 'dev.gray.companion.core.GlobalExtensionSwitchTest'
# 需要 Android 安装包时：
bash ./gradlew :app:assembleDebug --console=plain
```

APK 默认位于 `app/build/outputs/apk/debug/app-debug.apk`。Windows 将命令前缀替换成 `gradlew.bat`。以上不是每次都要跑的清单，按改动选择。文档示例用 docs/examples 中的真实导入工具检查，不需要真实模型。目录生成和具体命令见扩展指南。

已有本地0.57证据：29项定向测试通过；Android构建、安装、菜单卡片、全局开关和真实模型选项验证。它不证明未来云镜像、所有手机、所有分支和摘要长对话都已验证。新回归仅针对本次新增风险或实际失败。

## 正确的改动方式

- 加字段：增加兼容默认值 → 校验 → 编辑器 → 导入/编辑/撤回 → 使用索引与捕获闭包 → 对应测试 → 重新生成字段参考。不要只改 UI 后让字段保存丢失。
- 改行为：使用现有 ActionRegistry 与接受发布路径；清楚声明副作用、读写目标、可重放性。已接受效果不能在恢复时重复执行。
- 改可见性：复用 CardDisplays/参与判定，不在单一组件里藏掉内容却仍偷偷调用模型。
- 改提示词：编辑真实的 PromptBlock/历史选择；不能同时保留另一份隐藏拼接协议。
- 改 roll：对照 messages、state、results、macroHistory、来源与未来分支，不只比较屏幕正文。
- 改错误处理：区分网络失败、模型格式错误、配置错误和过期候选。用户修提示词后可再试，不吞错误、不保存假成功结果。
- 改 UI：先分类与路由归属，再用简短文字说明；原生资产走快捷入口，不叠加一层自定义表单。

## 兼容与签名

应用可见名称改为 velune，但 `dev.gray.companion`、`companion.config.v1`、`companion.bundle.v1` 仍是实际协议。变更它们会造成不同应用或导入不兼容。

本地交付使用 `.local/signing/debug.keystore`（忽略目录）维持签名。云构建若没有同一密钥，会生成不同 debug 签名；该包不能直接覆盖已有数据的安装。普通维护先在云端做代码/核心测试，最终兼容包可回本地签名构建。需要云端签名时由用户通过受控秘密配置提供，不能提交密钥或自动卸载原应用。

## 文档更新规则

README 只写当前入口和产品版本；ARCHITECTURE 只写当前边界与重要决策；DEVELOPMENT 写维护路径；AI_IMPORT_GUIDE 写作者契约；API_REFERENCE 用工具从实际 serializer 生成。HANDOFF 记录交付与限制。历史迁到 history，避免大量补丁段互相覆盖。

机器参考不是完整 JSON Schema，也不保证业务有效性。扩展至少要通过真实解码、ConfigurationCommands.preview/apply 和当前 LibraryValidator；不以 json.loads 成功冒充导入成功。

### Revision76 validation (2026-10-12)
Source wire33/execution26, no packaging. Focused tests: ModelRequestMessagesTest5, HistoryTextReferenceTest4, AutomaticOptionsTest3, AutomaticOptionsEditingTest5, GoogleVertexTransportTest5, OpenRouterErrorDetailTest1, RikkaGatewayErrorTest1, BodyAppearanceMarkupTest9, PromptTemplateHistoryRoutingTest3 (36 total passed). App Kotlin compilation passed; regenerated authoring catalog. Initial test assertions corrected and rerun only affected checks. Authorized synthetic HTTP evidence: Haiku5.5 rejects assistant tail400 and accepts explicit user tail200; AI Studio gemini-3.8-flash single/multiple System paths200, with one transient503. Not mobile end-to-end verification. Device UI/rendering acceptance remains pending; no APK or production database changes.

### Revision77 validation and packaging (2026-10-12)
0.65-preview/code233, wire33/execution26, SQLite1. User-confirmed priority fixes: history-reference picker modal overlap; streamed HTML internal scrolling/DOM replacement/loading subtree and candidate-height callback; explicit “修复并重试” stores a visible editable USER prompt block before retrying automatic options. Existing customized blocks are preserved; no hidden prompt or load-time rewrite.
Focused verification passed: AutomaticOptionsEditingTest5, BodyAppearanceMarkupTest9, PromptTemplateHistoryRoutingTest3 (17). External synthetic review probes passed3, reproducing two execution defects and one resource-deletion protection bypass; passing probes mean the unfixed defects were confirmed. APK assembleDebug passed in the same integration run. No repeated broad regression, real provider calls, production database access, installation or physical touch/visual verification.
Independent multi-model review completed nine domains with root assessment: 1 P1 + 9 P2 remaining findings, plus two unquantified performance observations. Only the three user-confirmed faults were changed. Highest priority remains extension-policy overlay versus regeneration commit validation; privacy/credential copies and effective-provider HTTPS validation follow. Reports and synthetic probes are in the chat audit artifacts; device flicker and click feel remain user acceptance items.

## 修订85维护约束
内置Agent知识来自 docs/AGENT_GUIDE.txt、AI_IMPORT_GUIDE.txt、PLUGIN_API.md、AI_IMPORT_API_REFERENCE.json，由构建同步到assets。资料变化编辑源文件，不能手抄一份assets导致漂移。knowledge工具只允许固定ID和有界读取/搜索。插图提示词使用通用块编辑器，wire37保留null旧字段；execution27不变。
HTML卡片与正文共用滚动/测高/复用边界；普通卡片没有内滚动条，超长回退保持可访问。菜单动作必须有图标与无障碍名称，最新BODY自动选项与按来源重绘插图接入真实控制器；第三方图标动作注册API仍未开放。自动选项去重包含编译上下文，不能被后到的其他调用取消后永久吞掉。候选重生成定位对应USER，保留原候选与控制器异常边界。

修订90日志：AppEventLog是有界本机事件日志，最近256条、每条420字符，后台原子持久化；不采集完整提示词/模型响应/凭据。UiToaster适配共享LocalToaster，普通提示只记录，有实际action才保留toast。主聊天/模型/辅助调用/Agent/配置/导航接入，异常红闪由TopBar共享已提示序号控制。日志窗口保留CrashHandler系统退出与崩溃报告。
