# velune 当前架构

基线：0.57-preview / code225，wire30，execution23，SQLite1。文档修订68，2026-10-10。此文件取代旧版逐层叠加的 revision 指令；历史保留于 `history/architecture-through-0.57.txt` 与 `ARCHITECTURE_HISTORY.md`。

## 边界与数据流

```text
原生编辑器 / 导入预览
    → ConfigurationCommands / ResourceEditCommands / LibraryValidator
    → AppController → SqliteLibraryRepository

用户消息 / 事件
    → 已捕获资源与执行版本 → RunPlan → WorkflowEngine
    → InvocationPreparation → ModelGateway 或 ActionHandler
    → OutcomeValidation → OutcomePublication → 接受回执 / Session
    → CompanionChatPage 的原生消息显示 + CardDisplays
```

`core/src/main/kotlin/dev/gray/companion/core/` 不依赖 Android，掌握资源、宏、历史、计划、执行、回执、恢复和分支。`app/src/main/java/dev/gray/companion/` 负责应用生命周期、数据库、模型传输与原生编辑器。`me/rerere/rikkahub/companion/` 将这些状态投影到复用的 Rikka UI。

原 Rikka 模型/前端代码仍有复用价值，不因“看起来旧”就整体删除；但原 ChatService 与 Room 聊天存储不能成为当前对话的另一个执行或保存通道。

## 资源归属

| 资源 | 职责 | 不应承担 |
|---|---|---|
| CharacterCard | 角色资料、开场、绑定资源 | 全局记忆配置 |
| MainCall | 主回复及相关流程、正文外观 | 所有扩展的卡片容器 |
| CallGroup | 可复用流程与 `cards` | 第二套会话数据库 |
| ConfigurationExtension | 关联资产、入口、全局启停、可选代码 | 为原生资产再做一套隐藏提示词的表单 |
| PromptPackage | 可编辑提示词资源 | 模型执行器 |
| Session | 本聊天消息、已接受结果、来源、记忆与分支 | 所有聊天共享的摘要 |

“原生／额外”是设置界面的分类。原生扩展使用资产快捷入口，额外扩展如记忆和选项使用专门设置页。代码扩展目前只有受限 JavaScript 执行契约，并不提供外部页面插件接口。

## 卡片与正文

新卡片在调用组定义。来源是现有宏/输出引用，完整响应经通用 XML 或手动字段规则提取，多个显示元素可引用同一字段，CSS 控制外貌。XML 标准示例生成稳定路径和元素；实际回复不任意增改显示结构。字段不是全局宏，CSS 不执行字段插值。

`CardRegion` 包含 CHAT、SIDEBAR、INPUT、MENU。顶部三点只列可用 MENU 卡片，点击后显示同一渲染器的卡片。楼层范围、显隐、允许调整布局和适配配置由现有卡片规则管理。已接受的楼层值必须保持其来源版本，不能取今天的宏重画昨日状态。

MainCall 只在界面保留 `bodyAppearanceTemplate`：单框 HTML/<style>，一个局部 `{{正文}}` 插入点。它不接入项目宏系统，不复用复杂卡片配置。默认空／占位模板保留原生正文渲染；自定义外观不更改保存正文、History 或 CoT。

旧 `MainCall.cards` 活资源经 `ExtensionLiveMigration` 幂等迁入扩展组，保留卡片 ID 与原主调用范围。已冻结世界不被递归重写。旧 `card.define` 与必要存档读取保留；不新建旧磁贴入口或运行路径。

## 扩展生命周期

启停是全局设置，扩展列表卡片内显示总开关。资源加载与展示复用 PluginParticipation/PromptPackages/CardDisplays 的共同判定。共享调用组至少有一个启用拥有者即可使用；独立手工绑定资源不因某个扩展关闭被全部删除。

关闭扩展停止相关后续调用及显示；已有消息、回执、摘要和分支保留。AppController 仅安全取消与受影响扩展相关的运行。工作流自己的关闭状态不会被总开关擅自改开。历史完全缺少的新扩展不会强行注入已冻结世界，当前限制是新建聊天使用。

## 记忆、选项与 XML

全局只有一套 `GlobalMemoryConfig`；每个 Session 保存自己的 `historyMemory`。小摘要读 BODY-only History，大摘要聚合小摘要，阈值均按原对话楼层覆盖计算，用户和助手各一层。来源范围与实际提示词读取共同决定保存的来源引用。

execution23 的小／大摘要必须提取配置 XML 标签中的非空文本后才发布。缺失、重复、未闭合或非法 XML 报 OUTPUT_FORMAT，不保存本次结果；旧输出正则不能提前剥去标签。配置的协议块在提示词编辑器可见，标签通过 `state.memory_summary_xml_tag` 注入该次上下文，不污染聊天 state。旧冻结执行版本保留旧解释。

自动选项独立于正文和持久消息，使用最近上下文生成临时候选。每项内容标签与可选标题标签可配置，协议通过可见 `state.automatic_options_protocol` 引用。严格校验所需标签与数量；源正文、配置改变后旧候选作废。标题模式先展开再填入输入框，填入不发送；保留草稿替换/追加判定。

卡片 XML、摘要 XML、选项 XML 是三个不同契约：卡片支持嵌套路径与列表；摘要要求一个文本结果；选项按配置匹配多项。它们都限制外部实体/DTD，但不能为了“统一”而抹平各自语义。XML 只能保证结构，不证明模型理解正确。

## 执行、恢复与历史

只有主调用默认正文使用流式预览。完整接受前的文本不发布宏、回执或记忆；最终处理统一校正。History 的模型正文和展示正文都是 BODY 投影，思考单独留档并由原生界面折叠。

原提示词和候选 AI 回复按对应关系保留。roll 的新候选不读取被替换回复及后续分支引入的状态；恢复旧候选同时恢复其完整世界。用户编辑发送生成同一聊天内的提示词变体，显式“创建分支”才创建单独聊天。`CompletedWorlds`、`CapturedResourceClosure`、`ConversationPaths` 是必须连同来源与引用一起考虑的边界。

断点恢复保留已接受效果，只继续未完成部分。配置结构漂移必须显式报错；不能因当前编辑器更新就偷偷重算历史。新增序列化字段须兼容默认值；wire 变化与 execution 变化分别决定。SQLite schema 不跟着产品版本无条件增加。

## 本轮维护决策

2026-10-10 / 文档修订68：清理当前入口的历史叠加说明，保留原文作历史证据；扩展样例通过真实导入器验证，机器目录从 serializer 生成；仓库端接手说明使用可移植路径。未修改应用运行逻辑。源码已提交/推送与云环境已发布必须分别报告，不能以本地文档准备代替成功部署。
