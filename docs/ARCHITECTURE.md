# velune 当前架构

当前源代码：0.66-preview / code234，wire34，execution26，SQLite1。文档修订78，2026-10-12。此文件取代旧版逐层叠加的 revision 指令；历史保留于 `history/architecture-through-0.57.txt` 与 `ARCHITECTURE_HISTORY.md`。

## Revision78：闭环修复、真实界面与请求边界验收（2026-10-12）

- 已确认范围：修复九域审查正式发现与此前三个故障；实际打包运行，核对系统提示词、历史、API角色、消息合并和参数。用户另要求优先移植原生平滑输出，后明确授权找不到时自行实现；主调用生成参数默认收起。完成后提交当前项目已完成工作并发布到 origin GitHub，包括可安装包。
- 既有单一执行/存储、BODY-only、冻结分支、可见可编辑提示词及同签名约束不变。原生平滑仅影响展示与缓冲，不能延迟最终内容、改变模型接收速度或重新接入旧 ChatService。原生Android源码/历史未找到适用实现，按补充授权采用展示端逐帧合并完整快照与追加后缀淡入；不以自写实现冒充移植。
- 重新生成：区分原始恢复检查点与获准执行的策略覆盖，保留强校验。删除候选由 ConversationPaths 统一处理相关归档与活动记录，不扁平化其他提示词分支。核心契约调整先报 root。
- 安全与服务商：消除请求/响应正文日志与明文轮询密钥副本，补齐实际生效覆盖连接的 HTTPS 准入。长SSE按空闲超时取消，不用隐藏120秒总上限。截断状态必须保留并明确展示；普通正文的保存与依赖完整结构的节点分开定策，不一律丢弃所有 max_tokens 文本。
- 审查其余项：摘要实际输入处理的来源追踪与冻结输入保持一致；配置命令删除复用真实引用保护并校验最终候选，允许事务内先修引用再删除；工作台切换字段保护未暂存输入。性能观察仅在实测关联或移植需要时定向处理。
- 展示交接：实际APK发现 pending→accepted 身份变化导致HTML高度重置和滚动跳回。物理输出以 session/turn/workflow/node/slot/occurrence 稳定标识；候选 UIMessage ID 与分支选择仍独立。投影在发布交接时去重 pending/history，正文采用同一 Compose 调用位置及 tileId。候选变化只更新片段并清除淡入，不重载相同HTML外壳；无需延迟或强制滚底。
- 实施分工：execution78完成再生成/候选删除、摘要来源与输出投影；transport78完成网络安全/结束状态、工作台字段草稿；native_smoothing78完成来源调查、默认折叠和展示缓冲/淡入/HTML交接；root负责控制器及引擎接线、配置删除保护、界面集成、验收、版本与发布。全部遵循单一执行与存储边界。
- 验收顺序：复用已证实缺陷probe，集中运行受影响契约测试；隔离模拟器运行实际APK，检查历史编辑按钮、生成参数默认折叠、粉色HTML流式/结束/候选切换；合成配置与本地受控端点截获真实序列化请求，对照编辑器预览和声明顺序/角色/参数。必要时沿用用户已授权的最小真实模型请求，不记录密钥。修复具体失败后只重跑相关检查；发布前检查提交内容、同签名、请求证据与可安装包。
- 已完成验收：定向测试、APK构建和同签名安装通过；隔离API36模拟器与本地合成HTTPS验证实际消息角色/内容/合并、BODY-only和参数省略。历史宏、默认折叠、字段草稿、36段正文收尾和截断提示已实测。候选身份及切换采用定向投影/核心测试证据；实体手机体验和真实云模型未验证。详见当前HANDOFF。

## Revision77：0.64 实测故障与独立审查

- 用户确认优先修复正文闪动、历史宏编辑页无法点击、自动选项 Assistant 末尾报错；九域多模型审查继续只读，其他审查发现不混入本轮修复。
- 正文仍由 StyledBody → InertHtmlView 显示；流式片段保留可复用 DOM 节点，16000dp 高度上限以下禁止 WebView 内部滚动，超过上限保持可访问尾部。HTML 正文不随 loading 改变 SelectionContainer 分支或启用尺寸动画；高度回调使用当前候选状态。自然内容测高及用户 CSS 保留。
- 历史选择只允许一个弹窗状态：资源浏览器选择完成后先清理旧目标，关闭回调不再复活旧面板。
- 自动选项已有自定义提示词不在加载或自动请求时改写。已识别的 Assistant 末尾错误显示“修复并重试”，用户点击后由 AppController 在现有互斥和保存路径重新核验当前会话/来源，追加普通可编辑 USER 生成块，验证最终消息为 USER、保存成功后再生成。无隐藏提示词、无协议迁移。
- 写入归属：root负责 AutomaticOptions/core/controller、版本、集成与文档；fix064_history_click负责提示词弹窗；fix064_body_flicker负责 HtmlMacroDisplay、ChatMessage、选项按钮文案及既有渲染测试。其余审查代理仅写各域报告。
- 最少验收：修改涉及的既有定向测试、针对确定审查疑点的少量合成 core probe、一次 APK 集成构建。无真实模型请求或用户数据库修改；设备闪动与点击体验不以静态检查代替。

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

## 2026-10-10 / 0.58 维护决策
- 目标：导入 Gray-G11 主调用、弗洛洛角色及两份世界书来源；复用 XML 状态卡；简化身份管理并支持角色绑定默认身份。
- 资源链：CharacterCard.boundGroupIds → CallGroup.boundPackageIds → 原生 prompt.inject → MainCall 接收槽。世界书分类属于包/组，不将世界书正文固化在主调用。
- 预设：特殊标记条目保留来源 role，其他条目及历史投影 ASSISTANT；保留原始历史来源角色与 BODY-only 记忆，避免重复替换/摘要。具体标记以文件调查证据为准。
- 身份：角色可选 boundPersonaId；新聊天优先角色绑定，其次现有全局默认/当前聊天回退。已有聊天只使用保存的 personaId，恢复聊天不重新应用角色默认。宏复用 user.name/user.prompt。
- UI：侧栏用户入口 → 身份列表 → 点击编辑；新建按钮；名称、头像、设定为编辑核心；切换当前聊天独立明确操作。
- 兼容：新增可空字段仅影响新聊天选择，wire31；当前聊天运行形状不包含角色默认绑定，execution仍为23。资源捕获补齐已捕获角色绑定的身份；删除检查真实live/收藏/可恢复分支引用，不遍历已自带资源的冻结世界。
- 验收：4项身份测试与1项真实ZIP/注入/记忆/触发集成测试通过，另一个状态ZIP真实导入通过，APK构建成功。无真实模型请求、无生产数据库写入、无 Git 发布。主观界面与生成效果留用户体验。

## 2026-10-10 / 0.59 编辑器工作基线
- 目标：宏在非编辑态显示蓝底展开内容，光标进入该宏时显示语法；保留 //，光标命中宏时打开该处详情/操作，普通文本处打开插入目录。
- 交互：详情首层为内容预览、编辑来源、引用设置、替换；低频操作收在更多。沿用源导航/原始文本偏移与保存路径，不把预览写入提示词。
- 模型调用 PromptBlock 不再提供 slug 命名入口或新建宏入口；保留历史字段/必要解码兼容，调用结果与提示词包仍可引用。
- 预览必须复用 MacroResolver，自动选项局部协议/数量/历史使用编辑中配置，无法获取上下文时明确空值/不可用；不调用模型、不修改聊天或发布结果。
- 分工：主代理 PromptTemplateField/PromptReferenceChips/MacroCompletionMenu 与集成；slug代理 PromptBlocksEditor 和核心命名调查/必要兼容；预览代理新建预览适配文件及 AutomaticOptionsSettingsPage 的局部上下文。共享文件修改先同步。
- 验收：必要定向编辑器偏移/触发检查与APK编译；用户体验验收，不重复前轮全部测试。现有0.58未提交改动保留；不提交推送。

- 实际验收：3项光标映射/截断测试通过，APK构建及同签名验证通过。自动选项相关15项通过，1项并发冲突旧用例等待超时未解决，详见HANDOFF；没有真实设备或模型验证。

## 2026-10-10 / 0.60 工作基线
- 目标：自动选项改为右侧紧凑悬浮条目，仅条目自身有背景，保留标题展开/填入与生成设置入口；不得大平面遮挡/压缩正文输入区。
- 正文：复用既有 Markdown 渲染，增加粗体/斜体/引号内容颜色设置；用户希望灰色强调、橙金引号。设置放在连接与界面→聊天与显示，遵循现有 UI 偏好持久化；不改正文源文本、请求或历史。
- 边界：core/执行版本/资源wire不变，既有正文外观和原生Markdown均需考虑，代码片段不作引号高亮。颜色可恢复默认，有简短预览。
- 分工：主代理负责 CompanionChatPage 自动选项、AutomaticOptionsSettingsPage 布局文案、版本/交付；正文代理负责 Markdown/颜色设置及其 UI 偏好路径，含 OriginalFrontendHost 必要入口。不得覆盖0.58/0.59既有修改。
- 验收：仅针对实际颜色解析/正文渲染必要检查及最终APK编译；主观体验交用户，无模型请求、无生产数据修改、无Git发布。

- 用户后续决定：本轮不打包，仅源代码及必要检查。MD颜色入口改放正文外观中，仍为全局显示偏好；不改主调用资源模型。
- 提示词条目移除位置/深度UI，栏目仅触发条件；打开编辑器时将旧位置/深度归一到列表顺序的草稿，只有保存才写回，取消保留旧数据；历史宏继续在其列表位置展开。不重写冻结历史或变更旧执行语义。

- 本轮实际检查：正文渲染8项定向测试与Kotlin编译通过；无打包/安装/设备体验验证。颜色入口最终为正文外观页内展开，三项颜色为全局即时保存偏好。

## 2026-10-10 / 0.60 紧凑聊天补充
- 用户要求：顶部渐变模糊、正文不减少、尝试全屏；输入框删展开按钮并上移发送、缩小高度；自动选项减少常驻提示，无选项右侧浮现生成按钮。继续不打包。
- 复用 Haze 渐变模糊，companion 顶部操作栏48dp，16dp渐变尾部；正文列表的顶部空间改成内容padding，滚动可进入模糊区域，非companion布局不变。
- 仅聊天页隐藏系统栏，边缘手势临时显示，离开页面恢复先前可见性与行为；保留安全区域与输入法处理。
- textOnly 输入采用模型/输入/发送横排，最多3行后滚动；保留停止、长按发送不回答与编辑历史行为。自动选项仅条目、加载指示或右侧生成/重试按钮，错误改短暂Snackbar，设置通过既有设置页。
- 分工：主代理顶部/全屏/自动选项，compact_input代理仅ChatInput。最少验收：集成Kotlin编译，不新增低价值模拟测试，不打包；实际模糊/全屏手感待用户体验。

- 紧凑聊天实际检查：Kotlin编译与差异检查通过；未打包、未安装、未做设备模糊/系统栏/键盘视觉验收。

- 后续用户明确要求打包：0.60/code228 APK已构建并验证同签名；之前不打包约束已被此次请求替代，设备体验仍待用户验收。

## 2026-10-11 / 调用编辑器可复现性基线
- 目标：调用组首页直接呈现调用；一个调用包含一个或多个行为在界面上明确可见。行为类型、添加/切换/编辑/删除入口正常显示，用户能从空配置复现弗洛洛4类提示词注入。
- 术语：调用=Workflow，行为=WorkflowNode，行为类型=actionId；前端不再并列“动作/节点/行为”混淆。底层命名和序列化保持兼容。
- 调用页先给平坦行为总览（类型、来源→目标或结果），选择行为进入同页明确编辑区，返回总览；新增行为先选类型，不默认生成模型行为再让用户找隐藏开关。默认主模型保持单主体约束。
- 清理常规编辑入口“显示到聊天/显示位置”、折叠“复杂流程”、重复管理方式。保留已有字段解码与冻结执行，不批量删除旧数据或暗改旧visible值；新建非默认行为不可见。
- 提示词注入：来源选择与目标选择应可直接操作，主调用无当前聊天时也能定位已配置目标/从库选择；注入标识与技术参数可归高级，不隐藏关键来源/接收槽/顺序/时机。保存沿用既有草稿与命令。
- 分工：主代理 CallEditor.kt 行为列表与类型切换/创建；组首页代理 MainCallEditor.kt；注入代理 ProcessingEditors.kt及必要专用新文件。跨文件接口由主代理整合。现有未提交工作保留。
- 验收：必要类型切换/兼容定向检查与Kotlin编译，不做模型请求/生产库写入/Git发布。本次先代码交付，不自动打包；设备体验未验证须说明。

- 补充决定：新建普通调用先展示空行为列表，由用户选择行为类型；行为内部返回不触发丢弃草稿提示，仅离开调用触发。底层旧字段保留兼容。两项类型切换/独立注入定向检查通过，未做设备体验验收。

- 用户随后要求打包：0.61/code229 构建及原签名验证通过，wire31/execution23不变；未安装或做设备体验验收。分类字段按用户决定保留，不另行扩展。

## 2026-10-11 / Vertex 服务账号修复
- 已确认：模型列表误用项目自建模型集合及 Gemini Developer 响应字段；区域调用统一使用全局主机；连接 UI 丢弃错误状态。按 Google 官方 Publisher Model Garden 列表协议修复目录，区域推理使用对应主机，global 保持全局地址。
- 仅改原 GoogleProvider、服务账号错误及连接提示，凭据存储/资源执行不变；目录不代表项目或区域已获调用权限，不自动换区。必要验收为拦截 HTTP 的离线服务账号/分页/错误定向测试和 App 编译，不使用用户密钥或真实付费请求。

## 2026-10-12 / 参数归属、接收槽与断点撤回基线（工作中）
- 用户已确认四项：提供商页只保留连接与模型分类/能力；具体请求参数归调用行为；参数条目不添加就不发送；统一接收槽；断点时删除触发本轮的User消息，原聊天恢复发送前，不复制分支。
- 参数边界：不得仅隐藏提供商UI而继续继承旧temperature/topP/maxTokens/reasoning；明确区分未配置与显式OFF/AUTO。原冻结运行/候选按既有版本恢复，当前请求按新契约，版本及wire是否变化在实现前由主代理确认。分类中的REASONING、图像/音频输入输出能力等保留。真实HTTP400可能另有原因，不宣称参数是唯一原因。
- 提供商适配必须以官方资料和当前实现为据，尤其OpenAI/兼容、Google/Vertex、Anthropic的思考档位/预算；避免未设置仍注入默认字段，必要协议字段除外。不用用户凭据作真实请求。
- 接收槽：保留现有被ID引用的槽及内容，不用标题近似匹配批量删槽；去除补齐入口引入第二套的行为。已有重复需安全区分引用/内容再合并，原聊天冻结快照不改。
- 断点撤回：仅用户指定的触发User楼层；同一session ID，利用运行前检查点恢复消息/宏/状态/记忆/运行记录等完整世界，丢弃本轮未完成候选与恢复记录；并发正在运行时不可竞态删除。不能用单纯pendingRun=null伪装回退。历史已接受前缀保持。
- 文件归属：主代理处理提供商参数编辑入口与调用参数条目UI（CompanionProviderPages/ModelCallDefaultsEditor/ProcessingEditors等）；transport代理处理请求合并与供应商参数序列化/网关（模型契约变更先提议）；recovery代理处理MessageOperations/AppController/CompanionChatPage/RecoveryUi相关撤回；slots代理处理DefaultPromptSlots/MainCallEditor及定向槽测试。共享文件更改先协调。
- 验收：针对已见缺陷的最少参数请求体/接收槽/撤回测试，由各归属执行一次；主代理集成编译，不重复测试或自动扩大回归。不写用户库、不Git发布。设备体验留用户验收。

- 契约决定：新 execution24，wire31不变；ModelProfile.reasoning 与传输 reasoningLevel 可空，null 表示不发送。execution≤23 的冻结准备与请求体保留旧合并/序列化语义；新请求忽略提供商旧生成默认值与自定义请求体覆盖。模型工具本轮保持原契约。
- 参数UI：API模型页移除调用默认值和自定义请求体编辑，保留能力、端点覆盖、请求头及工具；行为中通过添加/移除四项生成参数控制发送，思考解析单独展示。旧profile数据保留以支持冻结执行，不再作为新请求默认值。
- 槽清理决定：不加载时静默删除；主调用草稿页显式清理完整默认配置且无引用的空副本，已有自定义槽/有内容或引用的槽保留，保存才提交。
- 最终验收：22项相关检查通过（core17、供应商请求体4、模型编辑1），应用Kotlin编译通过，字段参考已重生成。旧测试版本号断言与一处跨模块smart cast已修正；只重跑失败项。未打包、安装、调用真实API或修改用户数据库。旧断点若缺少可证明的发送前世界，明确拒绝撤回；新断点保存完整发送前Session。

- 后续按用户要求完成0.63-preview/code231打包及原签名验证；未安装，设备与真实API体验待用户验收。

## 2026-10-12 / 用户输入宏与配置工作台基线（Revision75）
- 用户已确认范围：创建用户宏行为；输入已登记的 /快捷词 后ASCII空格收起为待执行标签，删除末尾空格还原，发送时展开并只保存普通USER文本。提升现有Agent与人类配置操作体验；先做隔离读取/起草/校验/原子提交，不接入真实常驻Agent、@路由或侧栏。
- 宏语义：创建行为只登记定义；固定文本、已有值引用或从指定已有值随机选一项，均在新消息发送时展开。预览/打字不抽取、不写状态；历史编辑、网络重试、断点恢复和重新生成不再次执行输入宏。未知快捷词普通文本，展开失败保持草稿、不发送半成品。前端采用原始文本加显示变换，末尾触发空格保留为可删除字符，不藏零宽执行代码。
- 工作台复用ConfigurationCommands、ExtensionFieldRegistry、ResourceUsageIndex、ConfigurationAdmission及原SQLite事务，不增加第二套执行器或数据库。多个独立内存草稿，base与draft不可变；成功暂存才推进草稿revision，固定preview绑定revision；提交在AppController mutex内对最新库做资源级CAS与复核，不用全库revision阻止无关聊天进展。
- 工作台允许运行中读取/起草；提交遇忙返回可重试BUSY，不调用会stopAndJoin的updateResources。应用只改活资源，保留聊天/断点/冻结世界；已冻结分支不冒称自动使用新配置。草稿生命周期限controller，界面说明重启不保留并可复制命令。
- Agent可发现性：提供资源目录、稳定ID字段描述/读写、行为目录、受限BODY会话上下文、结构化诊断和累计变更；不默认导出全库、模型凭据或可能含秘密的连接/native配置。人类入口提供选资源、字段小编辑、粘贴命令、检查和应用，错误保留输入。
- 文件归属：root负责AppController所有集成、工作台UI/导航、宏行为表单和文档；user_macro_contract负责宏core定义/行为/出版/快照/解析与必要测试（契约批准后实施）；agent_workspace_design负责工作台core/context/定向测试，禁止写AppController及宏core共享文件；输入框代理待分配，仅ChatInput及其显示/补全助手，CompanionChatPage连接由root处理。
- 检查集中到集成：只验证宏触发/退格/发送冻结及状态回退、工作区隔离/批次失败/CAS/旧预览/保留会话关键契约，再应用编译。设备输入法体验留用户验收；不真实请求、不操作用户库、不Git发布、不自动打包。
- 宏契约决定：wire32/execution25。WorkflowNode.userMacro持有UserMacroDefinition(shortcut,value)，value模式TEXT/REFERENCE/RANDOM_REFERENCE，定义通过user.macro.create接受回执写入Session.userMacros并纳入RuntimeSnapshot/RunRecovery/RunResult。重复快捷词覆盖；同条新消息同一个快捷词只求值一次。TEXT为字面文本，引用为单个完整标量引用，随机先验证候选再选一项。第一个版本仅当前聊天，无一次性消费或权重。
- 工作台API：ConfigurationWorkspaces负责open/stageCommands/stageField/preview/commitPlan/discard/exportCommands；ConfigurationContext提供受限目录/字段/行为/模型摘要/BODY聊天上下文及冻结会话资源。工作台只编辑character/mainCall/package/group/persona/tag/extension，不开放profile/connection或select全局设置。root的AppController用独立workspaceMutex处理草稿，仅commit再获取运行准入mutex。多个草稿互不覆盖，不抢占运行。
- 实际审计与取舍：原配置命令已有资源级前值校验、批次校验与撤回，字段注册表已有稳定目标；缺的是可发现上下文、持久于一次交互的隔离草稿及不打断主运行的应用入口。复用这些能力，保留原导入器；新增ConfigurationToolGateway与人类工作台共享controller写路径，不从工具调用updateResources以免停止生成。ConfigurationAuthoringSchema由真实资源serializer生成类型描述，不另维护手写schema。
- 最终分工：workspace代理提供core隔离/上下文/schema；macro代理提供登记与状态传播；frontend代理提供宏输入、宏表单及工作台页面/导航；root整合controller准入、JSON工具入口、实时上下文投影、字段差异展示、文档与最终检查。实际常驻Agent、@消息分流、侧栏及网络服务仍属后续工作。
- 实际验收：UserInputMacrosTest 3项与ConfigurationWorkspaceTest 5项通过；应用Kotlin编译通过，修复首轮编译发现的引用选择器具名参数及中文字符串插值问题。字段参考已重生成（wire32/execution25，86类型/18行为）。未打包、安装、真实API请求或生产库修改；手机输入法、标签退格与工作台视觉交互待用户体验。草稿不跨进程保存；同资源并发冲突不自动合并，需要基于新草稿重新调整。

## 2026-10-12 / 请求边界与编辑体验修复（Revision76，源码完成）
- 用户已确认：排查自动选项OpenRouter 400；修正文HTML/CSS闪烁并说明内边距；提示词条目触发条件下新增“其他设置”及相邻消息合并开关；整理展开层级；恢复主回复生成参数可达入口。本轮源代码与最少检查，不自动打包。
- 请求：已知core会合并相邻同角色消息。PromptBlock增加默认true的mergeAdjacentMessages；关闭时该条目产出的消息不与前后消息合并，通过ChatMessage同名投影标记传至预览/候选/恢复，不能在最终网关再次合并。结构化历史按该块设置传递，空文本仍不产生消息。wire33/execution26，旧execution<=25忽略新标记保持原语义；并不关闭HistoryConfig已有历史压缩/汇总，也不保证服务商自身不归一消息。
- 正文：保持WebView文档与CSS外框稳定，流式更新只替换正文节点；定位实际外层尺寸动画的影响后做窄修，不修改用户模板风格。CSS padding控制正文到CSS边框；宿主气泡和列表边距另说明，不盲目把所有留白归到CSS。
- UI：共用紧凑中性折叠标题、展开箭头与细分界；条件组/条件规则可辨识，常规操作与删除操作分离，避免层层厚卡/大缩进。生成参数仍归node.generation，恢复入口不恢复已移除的profile默认参数继承。
- 自动选项：检查现有参数投影、BODY-only边界、OpenRouter实际请求及安全错误正文。实际检查确认旧路径已通过新建BODY消息去除reasoningSnapshot，不存在所猜测的泄漏。最少真实请求证实Haiku5.5拒绝末尾ASSISTANT预填充（400），追加明确USER生成请求后200；旧内建与新默认模板增加可见生成请求，已有自定义块不暗改，提供可操作错误。用户本轮明确提供临时key并授权实际尝试，仅root对OpenRouter执行最少小请求，不将凭据写入仓库/文件/交付物，不导出用户聊天内容。
- 归属：root负责Models/ExecutionSemantics/LibraryFormat/PromptCompiler/ModelRequestMessages、PromptBlocksEditor、实际HTTP验证及docs；body_flicker76负责StyledBody/BodyAppearanceMarkup/HtmlMacroDisplay与必要窄ChatMessage动画接线（先同步）；openrouter400_76负责AutomaticOptions及controller/RikkaGateway/OpenAI transport诊断与修复；editor_hierarchy76负责EditorPrimitives/TriggerEditor/MainCallEditor/ProcessingEditors，CallEditor窄接线先同步。跨边界/契约变化先交root决定。
- 验收：复用现有定向测试，仅消息边界（预览与最后发送且旧版本保留）、已见自动选项400请求体、正文稳定shell必要检查；最终集成编译一次。主观视觉/输入体验交用户，不为模拟覆盖扩大回归。

- 后续用户决定：文本历史支持原范围与格式编辑，使用 `.as:text`，展开为 `Assistant: 内容\nUser: 内容` 等普通字符串，服从所在提示块唯一外层role；不会把文字标签转API消息。显式消息历史保留原结构。旧 `history.text/raw` 在新execution26保持TEXT类型并输出标签，旧execution<=25保留原文本语义（text不加标签、raw使用小写标签）；新.as字段旧版本拒绝。文本投影忽略API角色覆盖/整批消息合并以保留来源标签，仍尊重范围、来源、摘要和文本包装。
- 与合并开关相关的Google发送缺口：全部SYSTEM依次序列化为systemInstruction.parts，避免关闭合并后只发送第一条；单SYSTEM、IMAGE既有路径不变。HTML流式更新使用稳定shell+正文片段，外框不重建，流式助手正文取消宿主尺寸动画；手机视觉效果待实际体验。

- 实际验收：36项相关定向测试通过（core17、ai6、app13），应用Kotlin编译通过；字段目录重生成wire33/execution26。首轮修正了测试期望未计入显式生成请求、宏校验测试误传完整花括号、CSS first-child误中字符串断言，并补齐运行时旧版.as门控。未扩大回归、未打包/安装，设备视觉效果仍待体验。
- 用户后续授权AI Studio 3.8 Flash小请求验证：Models接口确认gemini-3.8-flash；单System请求200返回OK，多段systemInstruction.parts首次503繁忙、一次重试200返回SECOND，成功调用合计205 tokens。仅原生REST最小合成内容；未从手机发起完整聊天请求，无用户聊天/密钥入文件。

### Revision76 packaging follow-up (2026-10-12)
User requested packaging after source acceptance. Built 0.64-preview/code232, wire33/execution26 with :app:assembleDebug successfully (revision76-package.log). Existing signature verified against delivered 0.63: SHA256 certificate 8501445aff7bae47a3b69a8576a289aadfcd56a38a0ea38c28d548af2a60ba43. Package dev.gray.companion, minSdk26, arm64-v8a/x86_64. Delivered outputs/velune-0.64-preview.apk (99,407,776 bytes), SHA256 a13e51428987e1e3b4360f1397b8520a7009045b2f5237c7d8b0110aec3ea51b. Reused 36 focused passing checks; no repeat tests, installation, device visual verification, database mutation or Git publication. This supersedes prior no-packaging statements for Revision76.
