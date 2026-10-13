# velune 当前架构

## Revision93：打包验收与外部AI作者包（2026-10-13，用户确认，含GitHub同步）

- 交付0.75-preview/code243，保持wire37/execution28/SQLite1及原应用身份/签名，集成截至Revision92的源码。构建后在隔离模拟器验安装、启动和新增关键交互；已有定向单元测试结果复用，不重复扩大回归。失败按具体证据修复。
- 外部AI作者包独立于APK与可导入扩展包：提供完整中文操作文案、真实字段参考、模板、标准库ZIP构建工具与能力边界。源码与真实导入器为事实来源；文档和JSON解析不能替代真实预览/应用校验。
- root拥有版本/构建/集成测试/文档基线/Git发布；authoring93拥有docs/AI_IMPORT_GUIDE.txt勘误及工作目录的作者包。集中真实导入验证后交付，禁止夹带秘密、运行数据库或机器路径。完成后提交当前功能源码及相关文档到既有GitHub仓库，发布需以实际结果为准。

- 本轮追加已确认：移除生图常驻进度横条；Companion输入默认半透明且移除底部整块实色背景，用户blur/glass配置保留，继续禁止WebView Haze采样。三点使用原Popup生命周期承载现有MENU CardRegionDisplay前端，保留收藏/日志，不新增插件schema或任意菜单动作API。
- 正文排版：HTML宿主插图width100%/height:auto，无480px默认限高；原生插图同样取消固定限高。示例仅对非插图的正文直子元素施加88%文字宽度，外层100%且无水平padding；不强行突破用户旧模板的窄祖先，保留作者CSS覆盖权。
- 知识源统一：新增AUTHORING_ARCHITECTURE.txt/APPEARANCE_GUIDE.txt，经构建同步成六份固定ID离线知识；外部作者包与单TXT使用相同文档及字段目录。新ID architecture-guide / appearance-guide，仍有限读取与搜索，不开放任意路径。

## Revision92：历史纯文本发言方前缀（2026-10-13，已授权；不打包）

- 历史引用编辑半屏栏在TEXT模式直接显示Assistant/User/System前缀输入；空白保持原有完整前缀，自定义值按字面替换完整前缀（标点/空格/换行由输入决定）。不编辑源消息，不改变外层API角色；切回MESSAGES保留设置但不使用。
- HistorySelection.textPrefixes独立于format；prefixes字段使用带引号JSON保存映射，避免仅改标签就切换主调用历史格式继承。原始发言方由既有TEXT投影保留，PromptCompiler在拼接末端应用。旧引用默认输出不变；execution<=25与as字段一致拒绝prefixes，无wire/execution/版本提升。
- 沿用当前ModalBottomSheet的半展开/上拉交互，不再开第二个设置窗。只做解析往返、空白默认、最终提示词角色与格式、Android编译定向检查；设备交互留待用户体验。

## Revision91：收藏当前独立副本（2026-10-13，已授权；不打包）

- 主聊天横向三点提供“收藏当前副本”，单击保存到“收藏”文件夹（不存在则创建），不移动、不替换源会话、不改变当前选择；同菜单可进入收藏夹。真正运行中继续使用现有BUSY保护。
- 复用SavedChat和CompletedWorld：保存时赋予独立snapshot会话ID，断点主人及历史再生成候选按既有rebind规则复制；保留已接受消息、状态、宏历史、分支和冻结资源。已有插图按副本会话ID复制显示记录，共享不可变图片文件，不串联后续重绘记录。
- 打开收藏仍创建新的可编辑会话ID并复制其插图记录，收藏本体保持不变。源会话改动/删除和新会话改动均不影响收藏。旧收藏格式继续可读，无wire/execution/应用版本变更；资源全局设置与凭据仍按既有应用策略。
- 已完成：ChatArchiveTest四项通过，覆盖源删除、恢复后编辑、插图归属、暂停断点及历史候选重绑；Android Kotlin编译与diff检查通过。未打包/安装/设备交互验收。

## Revision90：统一日志与导航图标（2026-10-13，用户已确认；不打包）

- 输入栏不展示模型选择；模型配置保留设置入口。普通提示/错误不再自动弹Snackbar/Toast/纯告知框，写统一本机日志；需决策、修复或确认的交互框仍保留。右上角横向三点收到新错误短闪红色，禁止无限闪烁或重复组合触发。
- 原“闪退诊断”改“日志”，展示主聊天、辅助调用、Agent与配置操作的事件/调用状态/错误，并纳入现有崩溃诊断。采用单一AppEventLog有界本机持久记录与StateFlow，不复制聊天数据库、提示词、模型完整响应或凭据。诊断脱敏，流式token不逐个记录；不声称记录系统所有底层事件。日志不影响执行成功/失败边界。
- 公共接口由后端代理提供：dev.gray.companion.AppEventLog对象，entries:StateFlow<List<AppLogEntry>>，errorSerial:StateFlow<Long>；AppLogEntry(id:Long,timeMillis:Long,level:AppLogLevel,source:String,message:String)；level枚举INFO/WARNING/ERROR；record(source:String,message:String,level:AppLogLevel=INFO)、clear()、install(Context)。默认按时间先后，UI可倒序。错误序号单调增长，清空不倒退。调用方不提交秘密/整段请求响应。
- 移除常驻提示词预览入口但保留有效工具/预览实现。当前对话配置是当前会话身份、主调用、额外包/组及状态的编辑器，保留，说明用途。导航图标按功能语义独立分配；同一动作（返回/保存/删除）跨页保留一致图标，不强行每处随机换图。
- 文件归属：backend负责AppEventLog、新必要测试、AppController/辅助调用/Agent后端事件及Application/CrashHandler接线；chat UI负责CompanionChatPage、ChatInput、TopBar和CrashDiagnosticsDialog日志展示；icon代理新建CompanionIcons及CompanionNavigation等非host导航；root独占OriginalFrontendHost、其他Toast普通提示接线和文档。跨边界接口如需更改先协调。
- 实施补充：共享LocalToaster使用UiToaster适配器，原页面无需逐处替换；普通通知统一入日志，带实际action的Toast保留。无可定位资源的纯告知错误不打开设置问题面板；可跳转修复的交互面板保留。日志最近256条、单条420字符，后台原子写入；UI注意序号只在聊天页RESUMED时消费，同一错误不跨导航重复闪。模型请求序号跨实例共享。
- 集中最少日志脱敏/有界行为检查和Android编译；不调用真实模型、不打包/安装、不改wire/execution/版本、不Git发布。

## Revision89：调用编辑与已有宏设计（2026-10-13，已确认；沿用不打包）

- 查明并复用文本输出的结果宏、记录轨迹与正则处理契约；撤下Revision87调用组首页“从记录创建宏”，不再维护重复的高层创建入口。已有资源不能被静默删除或转换。模型默认记录变化、非模型默认不记录，按用户要求提供可调入口；旧冻结执行保持历史语义。
- 每个模型调用的生成参数与思考解析合为自己的可半展开、上拉扩展的底部面板，复用默认扩展已有组件。面板按钮有独立识别样式；页面跳转与开关沿用既有样式。折叠区增加左侧层级引导线，不用空白间距冒充分组。
- 输入模板、标签与历史插入先核对实际消费者；有效功能改用明确名称与合理层级，不凭观感删运行时字段。不扩展成统一编辑所有模型调用，不新增宏存储或隐式提示注入。
- 契约决定：execution28新增modelTrajectoryControl；旧冻结版本<=27保留模型强制留档，新版本尊重已有recordTrajectory。wire37不变；不凭无法判定来源的旧false字段强制迁移。关闭后最新结果宏、可见正文原文、回执和trace保留，隐藏模型的逐版原文不再另存；不新增旁路存储。
- 主代理负责契约判断、宏编辑路径和集成；ui_notes88只读调查旧设计；recovery88_ui负责ProcessingEditors/EditorPrimitives/EditorSheet的面板与层级样式；recovery88_backend仅完成上一修订必要断言修复。最少检查集中于收尾，保留此前dirty，不打包/安装/发布。

- 最终验收：23个core定向用例及3个LibraryEditPreparation用例通过；Android Kotlin编译和diff检查通过，作者目录92类型/18行为已生成。未打包、安装或实机体验验收；实际重跑与限制见HANDOFF。

## Revision88：可继续的完成态（2026-10-13，已确认；本轮不打包）

- 用户明确：手动停止和断点中断均作为已完成、仍可继续的状态；不因pendingRun全局禁用roll/候选切换、USER三点、编辑删除撤回或正常输入。真正运行中的互斥、旧结果隔离、来源校验仍保持。
- 只使用原发送键位置的一个按钮：输入为空、非编辑态且存在可继续断点时替换为三角形继续键；有输入则是发送键，运行中沿用停止键。平时不展示断点卡/恢复入口，不抢占焦点。点击继续先按已有回执续跑；仍失败或存在不能自动处理的问题时，才展示可关闭的处理对话框。
- 断点是可选续跑资料，不是会话锁。修改聊天路径、roll或开始新一轮时，在同一事务中基于已接受结果执行既有操作，并使不再对应当前路径的断点失效；不能先丢断点再发现操作失败。暂停的普通轮次撤回其USER保留完整检查点回滚；暂停的重新生成保留旧候选，不发布未接受的新候选。
- 保持单一core/AppController/SQLite边界与冻结执行、回执不重复执行契约。停在无失败节点的可恢复日志也必须有明确定义的继续路径；不得靠虚构失败或自动跳过来冒充续跑。候选/用户版本的真实数据限制与运行时BUSY保护仍有效，不能被UI解锁绕过。
- 文件归属：恢复后端代理负责AppController、core消息/路径/恢复引擎及最少定向用例；UI代理负责CompanionChatPage、ChatInput、RecoveryUi及必要消息菜单显示层；root负责基线、其他入口审查、集成和最少验收。跨边界接口先同步。
- 不提升应用版本，不assemble/package/install APK；集中做必要编译和针对新契约的测试。保持之前所有未提交成果，不Git发布。实机交互体验留待用户后续要求打包后验收。
- 追加确认：自动选项默认收成小图标，点击弹出、失焦关闭；重新生成仅保留在消息三点菜单，移除右侧常驻按钮和说明。用户确认全局清理界面辅助说明/提示性长文，保留按钮名称、字段标签、实际值和错误；不删除用户内容、提示词正文或代码注释。ui_notes88负责其他界面的说明文案，恢复UI三文件仍由原UI代理独占。

## Revision87：实机Lookahead闪退、选项入口与记录转宏（2026-10-13，已确认）

- 用户确认0.73上下滚动已修复，保留Rev86的列表/HTML生命周期设计。新实机崩溃栈位于Compose LookaheadPassDelegate放置流程；移除当前聊天导航不再使用的SharedTransitionLayout祖先，保留导航及聊天自身动画。根因判断基于实机堆栈和源码，交付仍需同设备复验。
- 自动选项同时修复触发与可发现性：完整BODY接受后，同一来源因上下文编译更新而取消的请求可按新上下文重新调度；切源、会话或配置撤销旧授权。聊天界面明确展示生成入口和选项位置，不要求用户猜测消息菜单。
- 记录转宏复用现有macro.process和TextProcessStep，不新建解析器/状态存储或变更wire。调用组首页提供“从记录创建宏”：选取同组工作流节点输出，编辑正则提取/替换，预览样本文本，命名macro.*；生成依赖来源节点的SET行为。保留来源记录，宏可在现有引用编辑器中选择，不自动注入主提示词。保存后随后续来源执行更新；不暗中回填历史。
- 用户提供的粉色正文与状态栏资源包仅作实际配置参考；不硬编码字段，不改变正文外观或默认提示词。重复宏目标拒绝，输出键保持库内唯一；复用原生编辑器后续修改。
- 文件归属：lookahead87负责OriginalFrontendHost祖先布局；options87负责AutomaticOptionsController及其测试、CompanionChatPage选项入口；记录转宏代理负责MainCallEditor、新建向导及必要引用可发现性；root负责基线、集成、版本与交付。跨文件先协调。
- 验收集中执行触发时序、生成宏契约和APK构建；隔离模拟器检查新入口与向导。实机Lookahead快速滑动/重生成由用户安装复验，不进行额外付费模型调用或Git发布。
- 实际结果：自动选项时序用例1项和APK构建通过；模拟器确认按钮可见/错误可读、记录载入/正则预览/创建保存，持久化节点契约核对通过。完整主聊天触发宏和真实选项生成未实测；实机Lookahead问题待复验。隔离测试数据已恢复，细节见HANDOFF。

## Revision86：快速跨轮阅读、HTML生命周期与重生成定位（2026-10-13，0.73体验版）

- 用户确认0.72在真我GT8Pro仍闪退、卡顿、定位失败；生成中快速上滑跨轮会跳顶，缓慢滑动正常。Rev79的Haze线索与模拟器阴性结果仅作历史证据，不再把其候选修复视为实机通过。
- 源码已确认：列表未配置相邻预载；HTML高度仅在行内remember，离屏后恢复120dp；复用WebView仍清空文档重载；标题测高回调未校验文档身份。这些必须作为同一生命周期契约修复，不能只增大占位高度或减慢用户手势。
- 保持单一LazyColumn滚动所有者、稳定物理输出ID、原CSS/插图/渐隐及Haze退出采样。页面作用域保留有界高度资料，以实际宽度/字号/文档身份隔离；复用/重载期间保留有效高度，只有当前文档和当前片段的真实测量能更新。相邻预载使用Compose支持的有界窗口；预组合不等于WebView异步内容已就绪，不宣称固定三轮已经加载。
- 自动跟随必须确认真正的列表末项；再生成按对应USER稳定身份，在目标投影到位后执行一次锚定，失败清理请求。避免把后续每次BODY更新变成强制定位。
- 文件归属：root维护基线、集成、窗口配置及验收；scroll86_audit调查并负责经确认的定位/跟随修改；html86_design负责HTML生命周期设计及经确认后的渲染修改。共享文件先协调。core/wire/数据库/模型请求契约不变，不Git发布。
- 验收针对真实行高恢复、旧测高回调、快速跨轮和定位路径；统一构建/必要定向检查。实体机效果和无堆栈闪退保持未证实，不扩大为全量回归或付费模型测试。
- 实现选择：HTML缓存为页面级256项数值LRU，无WebView/Context/正文持有；BODY首次用文字宽度估高120..8000dp，真实测量80..16000dp可缩小；模板任意CSS使估算不可能精确。Compose窗口为前后各2视窗，不是保证3轮全部DOM完成加载。reset保留文档及本地图片权限，release才销毁；导航token/片段revision/宽度共同校验，空shell不发布高度。
- 用户明确闪退为整个应用退出。发现现有CrashHandler未在CompanionApplication安装；接入只记录异常类型/代码栈的本地记录，并在设置提供系统进程退出原因与WebView退出信息的查看/复制。异常message、系统description、native trace及聊天/凭据均不记录、不上传。HTML渲染器退出先移除/销毁实例再显示手动重载，不默认让宿主终止；这不是对手机闪退根因的确认。
- 实际验收：16项定向检查、APK构建/同签名覆盖安装通过；隔离模拟器12轮离线长正文跨轮快速滚动及诊断查看/复制完成。BODY生成中跨轮、真实重生成完成、实体机闪退/帧率与renderer死亡恢复尚未验收。详细证据和限制见HANDOFF。

## Revision85：统一HTML显示、自动选项、阅读性能与离线Agent规范（2026-10-13，已确认）

- 用户报告0.70状态栏卡片出现内部滚动条、自动选项不自动触发、快速滚动新消息卡顿；插图提示词仍是多个文本框，要求使用其他调用同样的提示词编辑器。以0.71源码修复，保留Agent功能与既有未提交工作，不Git发布。
- 查共同边界：正文与状态卡HTML统一内容测高/滚动所有权策略，避免各入口补丁；保留超长内容可访问性、无脚本/远端资源及已有渐隐。性能先定位懒列表/HTML创建与测高，不预设所有消息常驻或大范围重写。
- 自动选项保持独立辅助调用，只有完整BODY来源可触发，切换/取消/新源不发布旧结果；修复必须依真实控制器与发布时序，不添加隐式提示词、盲重试或改变冻结执行语义。
- 插图提示词复用现有通用块编辑器/宏引用/角色与位置等契约；旧配置兼容，新增结构只有必要时改变wire，原字段不得丢失。明确数据契约再落地。
- 文档基线由root维护：开发模型的规范保存在现有AGENTS/DEVELOPMENT；内置Agent通过打包本地资料与可发现只读工具获得资源、插件、提示词及限制说明，不依赖联网查产品契约，也不宣称离线模型推理。避免复制多份漂移。
- 写入归属：显示代理负责HtmlMacroDisplay/CardSnapshotPanel等渲染和ChatList预加载；选项代理负责AutomaticOptionsController与AppController选项发布接线；插图代理负责IllustrationSettingsPage及必要core配置兼容。root负责Agent离线资料、文档、导航/版本、集成与交付。跨共享接口先协调。
- 追加范围：同分支新候选的异常边界与 USER 锚点；消息菜单删除移至上排图标、下排注册可重生成扩展图标。图标/无障碍名称/可用条件/真实回调为宿主 UI 契约，当前接入自动选项与插图，不给第三方资源虚构未实现字段。插图手动重绘以批次首张成功替换整条旧图，同源互斥与不确定交付保护保持。
- 验收仅针对故障边界集中执行；优先可安装体验版。HTML实际截图/滚动和触发状态可在隔离模拟器验收；无法证实的性能根因及实体设备效果如实标注，不用编译代替体验通过。

- 实际交付：11项定向检查通过，0.72 APK同签名安装通过，消息图标菜单与完整插图提示词编辑器已在模拟器检查。HTML性能未作实体机帧率验收；候选闪退无可用堆栈、未稳定复现，只补准备阶段异常边界和USER定位，不声称根因已确认。HTTPS本地模型端到端因测试证书安装命令被自动审批拒绝而未完成；不绕过，隔离数据已恢复。


## Revision84：独立资源 Agent（2026-10-13，0.71体验版）

- 目标：应用内独立 Agent 对话、工具记录、模型/思考选择、可见可编辑工作指令；读写专属工作区文本文件，查询资源/schema/实际提示词/当前会话，复用配置工作台暂存、检查和应用，并可编写受限 JavaScript 插件。用户已确认范围；既有插图小幅上下渐变保持。
- 禁止 Agent 访问应用核心源码、APK/原生库、数据库、凭据、任意设备路径及 shell；没有热改核心或自重启能力。资源配置与插件遵循现有核心校验和授权契约，不新建第二套 Library/执行引擎。core wire36/execution27 无需为了 Agent 本身升级。
- 运行隔离：Agent 单独的协程 Job/网络调用/持久对话记录，不使用 AppController.send/stop 或聊天 busy 标志。切离 Agent 页面不会取消任务。Agent 文件/模型推理不持有聊天 mutex；配置草稿使用现有 workspaceMutex；正式应用仅走 commitConfigurationWorkspace，BUSY 时保留草稿，不停止运行、不自动重放副作用。当前后台指进程仍存活期间与其他页面并行；不承诺 Android 杀进程后自动续跑付费请求。
- 登录：采用官方 SIWC 开源本地应用动态客户端 + PKCE/state/nonce + 127.0.0.1 临时 callback 的原生移植，系统浏览器授权。OAuth token 在独立 Keystore 槽保存，不读取现有 Codex 登录、不写资源或 Agent 文件。验 ID token 签名/issuer/audience/nonce/expiry，推理要求 chatgpt.tokens.use.direct；串行刷新与登出撤销。Android 浏览器回跳尚待设备验收。
- 推理：原生 Responses HTTP/SSE https://api.openai.com/v1/responses，OAuth bearer，store=false/stream=true，使用 instructions + input 数组，完整本地历史，无 previous_response_id。仅 response.completed 完成；incomplete/failed/断流区别处理，不盲重试。按账号 /models 返回选项；本地 namespaced function tools，不开 hosted tools/shell。
- 文件：每个 Agent 聊天一份私有 workspaces/<id>/files 根；相对路径、规范路径和符号链接边界、UTF-8/大小/总量限制，写/替换/移动/删除用内容 hash CAS，原子写入。状态/凭据不在可见文件根。文件不会自行安装为插件；通过工作台预览/提交才进资源库。
- 工作台：复用 ConfigurationToolGateway 和资源 schema；Agent 仅访问自己创建/明确关联的草稿，不抢占用户其他草稿。调用与结果形成记录；会话、文件和可恢复草稿资料保存于独立 Agent 存储。提示词预览复用现有编译路径，区分下一次预览与已经发送的记录，不伪称读取不可获得的数据。
- 文件归属：root 负责 AppController 最小接线、AgentController/Responses transport/持久记录、导航、版本、集成与文档；agent_auth84 负责新 agent/AgentAuth.kt 与 OAuth 校验辅助和定向测试；agent_tools84 后续负责新 agent/AgentFiles.kt、AgentToolHost.kt 及工具定向测试（待明确接口）；UI 代理后续负责新 ui/AgentPage.kt（接口冻结后再交接）。未经协调不编辑共享文件。
- 最少验收：文件边界/CAS、OAuth校验与流终态、工具草稿BUSY且聊天不中断、APK构建；隔离模拟器检查 Agent UI/文件编辑/工作台与聊天并行。真实账号授权需要用户在系统浏览器完成，不能把构建/替身当套餐推理已验证。保留79–83所有未提交工作，不作Git发布。
- 官方依据：developers.openai.com/siwc/token-sharing-open-source/{sign-in,profiles-and-sessions,models-and-inference,preview-limitations}；纯Android直连为移植选择，Codex app-server 子进程方案不作为手机依赖。

- 实际集成：AgentAuth/Files/Responses/ToolHost 共9项定向测试通过，APK编译与模拟器安装通过。模拟器验证文件保存、跨重启保留、工作台stage→恢复→preview→commit及提交后清除恢复资料；系统浏览器打开OpenAI登录页。修复导航栏遮挡与恢复列表重复。真实账号授权/token刷新/套餐推理未完成；本地合成主聊天并行试验因测试端点网络失败未形成并发证据，不能当并行端到端通过。
- 最终实现细节：Agent配置计算/文件/网络在后台调度器，任务状态独立；工作台正式应用仍拒绝主聊天busy。输入历史保留加密reasoning及function output，缺失工具结果在重启/取消时标INTERRUPTED，不自动重放；最多32次推理/轮、上下文1M字符后显式清空。草稿只存累计命令和资源基线hash，恢复再比对并重新preview；原生工作台编辑也跟踪保存。


## Revision82：插件宿主能力基线（2026-10-13，已实现）

- 用户已确认本轮先完善插件底座：联网、显式权限、凭据引用、Agent 可发现接口与结构化错误；BizyAir 插图随后接入。本轮不实现插图排版、常驻 Agent、任意文件/进程/数据库访问或付费服务测试。现有未提交 Revision79–81 保留。
- 保持 core → AppController → SqliteLibraryRepository 的单一执行与持久化边界。plugin.run 仍是普通行为；JavaScript v1 继续纯文本执行。v2 使用 async execute(context, host) 和版本化 host.invoke(capability, args)；首批 network.http、runtime.sleep、capabilities.describe。宿主能力通过注册式分发与机器可读描述扩展，不向 JavaScript 暴露 Android 对象。
- PluginCode.permissions 为声明，不是授权。网络声明按精确 HTTPS origin、HTTP 方法、凭据别名约束；无通配域名。设备上的授权独立保存，绑定 plugin ID 与完整代码/权限摘要，导入、Agent 配置提交及插件更新不能自行授予能力。修改代码/权限须重新授权；每次宿主请求重新核验，以支持撤销。内部权限管理用明确授权/撤销按钮，不增加第二个扩展启停开关。
- 凭据使用插件专属 Keystore 命名空间，资源/导出/Agent 仅含别名及配置状态。host HTTP 按声明向指定 origin 的指定 header 注入凭据，不提供明文读取 API。HTTP 采用独立受控客户端，无自动重定向/重试、无请求/响应日志；拒绝内网/本机/保留地址、URL 凭据和越权 headers；限制请求次数、大小与时间，取消时取消底层请求。原应用代理/网络配置在可行时复用，不能借代理绕过目的地址检查。
- HTTP 返回结构化状态、headers、文本 body；HTTP 非2xx也作为可检查的响应，网络错误区分未发送与结果不确定，不能把未知结果标成安全自动重试。提供可取消 sleep 供异步任务轮询。本轮无跨进程远端任务续跑/恰好一次承诺，插件作者须保留 request ID，不能盲重发有副作用的请求。
- v2 外部能力结果不进入纯重算配方，接受结果沿用现有回执与快照保留；分支重建绝不再次执行 v2。v1 冻结语义保持。新增 wire35/execution27，对旧执行版本中的 v2 明确拒绝，不提升旧冻结版本。
- Agent 可通过 ConfigurationToolGateway 查询插件能力、真实 schema、示例、错误与本机授权/凭据是否已配置；不能通过配置工具授权或读取密钥。UI 可编辑声明、查看实际权限并授权/撤销、填写别名对应凭据；保持原生资源编辑器。
- 文件归属：core_contract82 负责 core 插件契约/描述/校验/重放版本及定向测试；host_runtime82 负责 QuickJsPluginExecutor、PluginHostRuntime/HTTP 和定向测试；plugin_ui82 负责 ExtensionPages 与新 PluginAccessPanel；root 负责 PluginAccessStore、AppController/OriginalFrontendHost/ConfigurationToolGateway 接线、文档、构建集成与交付。共享接口变更先回 root。
- 最少验收集中一次：声明/越权/旧版兼容/重放结果保留，受控 HTTP 与异步 JS 桥（不访问付费服务），应用构建。只对观察到的失败追加检查；设备授权交互与实际 BizyAir 由后续使用验收，不以编译冒充已验证。
- 实际集成：PluginCapabilitiesTest3、CompletedWorldReplayTest5、PluginHostRuntimeTest3、QuickJsPluginExecutorTest3 共14项通过；HTTP权限以离线替身验证，QuickJS异步桥实际运行。示例真实导入/预览/内存应用通过，89类型/18行为及宿主schema已生成；0.69 APK构建与原签名校验通过。未安装、未验证实机授权页、DNS/TLS实网或真实BizyAir。
- 初版网络策略确定为独立直连客户端，不继承应用代理；每次请求固定已检查的DNS结果、无自动重定向/重试，取消覆盖响应读取阶段。后续代理或其他能力必须显式补充权限与传输契约，不能绕过当前策略。

Revision82当时源代码：0.69-preview / code237，wire35，execution27，SQLite1。文档修订82，2026-10-13；插件宿主能力见上方基线，实际验收见 HANDOFF。此前回复过程与设置入口等展示层调整、静态正文闪烁候选保留，实机效果仍待用户验收。旧资料见 `history/architecture-through-0.57.txt` 与 `ARCHITECTURE_HISTORY.md`。

## Revision81：回复过程与分层编辑入口（2026-10-12）

- 用户已确认：三点及其轨道平面同时转动，较预览略快；采用思考期间隐藏头像、完成后折向角色头像的方案。等待阶段左侧显示真实行为；思考出现后等待指示消失。思考尽量占满可用宽度，正文的既有渲染身份和布局不因动画重建；完成/取消时保留查看思考的入口，无思考时正常显示头像与正文。
- 编辑界面：角色关联提示词包/调用组使用底部半展开框；主调用生成参数和思考解析合为一个底部框；正则输出副本由开关直接展开，取消外层折叠；角色基础设定改独立子页面，七项各自可折叠。底部框使用统一的上提面板按钮，与右箭头页面跳转区分。
- 底部框与基础设定子页只编辑所属外层资源草稿，关闭/返回保留草稿，外层保存才提交。保留来源定位、宏编辑、标签、参数未配置语义与外层未保存保护。新底部框沿用稳定顶部安全区策略，保留半→全拖动、键盘避让与固定标题/关闭入口；不改变数据或执行版本。
- 文件归属：root负责共享 EditorSheet.kt、角色 Editors.kt/基础子页、架构/版本/集成；generation_sheet81负责 ProcessingEditors.kt/MainCallEditor.kt/CallEditor.kt 内参数合并与正则开关；dynamic_reply81负责 CompanionChatPage/Projection、ChatList、ChatMessage/Avatar及必要思考/加载组件。共享接口变更先回root，无core/数据库改动。
- 验收集中于最终Kotlin编译和必要可安装体验版本；仅针对实际生命周期/布局风险检查，不叠加大回归或真实模型请求。此前revision79 Haze退出采样和revision80卡片候选修复必须保留，实机体验不冒充已验证。
- 用户追加：暂停后提供直接结束运行的明确入口（保留已接受结果），补齐消息三点菜单删除；不能让普通删除悄悄变成撤回整轮。message_delete81负责RecoveryUi/CompanionMessageToolbar，dynamic_reply81连接已有onDelete回调；如需改变核心删除/结束契约须由root先审定。暂停运行正式结束前继续保护冻结执行状态，失败必须显式反馈。
- 用户追加：去除状态栏卡片外层淡蓝基底。CardSnapshotPanel 是运行时各区域共用容器，统一将外层Surface和WebView默认背景设透明，保留模板自定义CSS、原布局间距、错误及折叠内容；菜单对话框和布局管理自己的底色不受影响。generation_sheet81负责该定向调整，无资源格式变化。

## Revision80：历史引用卡片与回复过程预览（2026-10-12，设计已由Revision81承接）

- 已授权：修复历史引用编辑卡片半展开可点击、全展开失效的问题，并推荐其他适合半→全交互的位置；替换回复等待阶段兔子图标为三点环绕与当前行为提示。头像使用角色卡，用户现要求先比较常驻与动态方案再决定。
- 卡片候选：保留 Material3 半/全拖动和底部/键盘安全区；顶部 safeDrawing 改为内容内固定 padding，避免 offset 消耗顶部 inset 再改变测量高度/展开锚点。1.5.0-alpha28 源码支持该风险分析，但旧版 API36 模拟器未稳定复现点击故障，不能宣称根因已证实或实机已修复。
- 历史设计阶段的静态头像候选（已被Revision81替代）：Companion 首个思考块左侧28dp角色头像，无思考时在正文上方；原生消息布局与正文宽度不变。动态方案仅交互预览：准备阶段三点+行为，思考时无头像并占满可用宽度，完成后收向头像位置，点击头像重开思考。未决定前不将动态候选作为正式行为。
- 三点运动根据用户截图近似制作，不是 Claude 原始代码；正式等待文案必须来自实际执行阶段，不能模拟不存在的工作。数据/wire/execution/SQLite不变。
- 推荐复用处：宏选择及来源定位、注入行为的来源/接收槽选择、导入与移除影响预览；本轮仅建议，不全面重写编辑器。

## Revision79：静态 HTML 正文整块闪空（2026-10-12）

- 用户真我 GT 8 Pro 录屏证实：快速滚动点停、回到顶部后，文字和 CSS 背景整块消失再出现，标题、输入框和选项按钮保留。此故障不同于 revision78 的生成完成交接跳动。
- 当前 HTML 位于 ChatList 的 Haze source 内，顶部渐变模糊无条件采样该 source。Haze 上游 #99、#583、#745 记录 WebView 在 RenderNode/RenderEffect 重复绘制时持续闪空；与当前代码和录屏高度吻合，仍须本机 A/B 确认，不能仅凭机型归因于 GPU 驱动。
- 决定：ChatList 增加默认开启的 captureForBlur；Companion 阅读列表关闭该采样，因为正文及卡片均可能嵌入 WebView。页面背景仍作为 Haze source，顶部与输入区保留背景效果，但不再模糊采样聊天文字。原生消息概览不含 WebView，保留原路径。
- 保持 CSS、正文测高、流式呈现、滚动行为、数据及执行契约不变。不上超长 WebView 软件位图或全局关闭硬件加速，不同时叠加多个渲染补丁。root 负责本次实现与打包；只做构建、同签名和定向启动检查，最终闪烁效果由用户实机验收。
- 上游证据：[Haze #99](https://github.com/chrisbanes/haze/issues/99)、[Haze #745](https://github.com/chrisbanes/haze/issues/745)。隔离 API36/WebView133 模拟器原样式未复现，DOM 观察期间无正文变化、内部滚动或持续测高；该阴性结果不代表手机没有问题。

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

## 2026-10-13 / Revision83 BizyAir illustration baseline
- Authorized: implement post-accepted-BODY background illustration extension, real minimal Google/DeepSeek/BizyAir probes, deliver usable APK. Provider keys remain memory-only for probes, never repository/deliverable. BizyAir account max2 concurrent; configurable1..2, queue/running/congestion UI.
- Config is global `Library.illustration: IllustrationConfig`, built-in extra extension analogous AutomaticOptions; only list switch. Config includes model, editable task/guidance/requirements and macro template, characterId→appearance map, app57992 input mapping/default parameters. No hidden prompt. Image plans use strict XML `<illustrations><image after="p1"><prompt>...</prompt></image></illustrations>`; only latest assistant BODY is marked, context last2 user/assistant turns. Positive prompt only without quality words; stable quality/negative presets separate.
- Results `Library.illustrationImages: List<IllustrationImage>` are display annotations outside Session/world execution. Identity sessionId/messageId/sourceKey derived from exact BODY text + active text revision. Never rewrite BODY/history/accepted world. Project only exact current source; hide on global disable, retain completed images. Local image files in app-private storage; no secrets in Library. Wire36, execution27 unchanged.
- Root owns AppController integration, SQLite metadata, UI root wiring, docs/build/live probes. core agent owns Illustration.kt + Models Library fields + LibraryFormat + focused core tests, defines deterministic slices/XML/request builder. runtime agent owns IllustrationController/BizyAirClient/image storage and focused checks only (not AppController). UI agent owns IllustrationSettingsPage, ExtensionPages, inline rendering/projection wiring; root owns OriginalFrontendHost shared integration unless agreed.
- Runtime triggered by existing bodyCommitted sites after durable commit. Jobs never hold main generation lock; device total max2, configurable lower cap. GET polling may retry bounded; POST never blind retry. Cancellation/disable removes queued work and prevents stale publication, accepted files retained. Publish callback uses controller mutex, current config+source validation, merges latest Library without revision bump/session rewrites. No resume billing on process restart; persisted completed images available.
- Integration checks only touched XML/anchors, queue limit & stale publication, final APK compile/signature. One synthetic planning request per text provider and up to2 real images if necessary; no production DB mutation/Git publish. Device subjective fades and touch remain user acceptance.
- Integration decisions: submitted BizyAir jobs retain their queue slot across disable/config/source changes until terminal status; only their publication is discarded. An unknown POST/status result latches queue suspension until explicit user acknowledgement. This is in-process control, not a durable remote task ledger; process death and tasks submitted from elsewhere still require checking BizyAir. POST 4xx explicit rejection is distinguished from unknown transport/5xx; only GET polling retries.
- Images remain local device files. Annotation references are serialized in the library but image binary portability/export is not implemented. Inline renderer must validate files under the app illustrations directory and map only these images into custom HTML; do not relax arbitrary file/network loading.

- Actual verification: 17 distinct focused checks passed (core3, queue3, body10, image projection1), final APK build/signature verified. Desktop synthetic live Google/DeepSeek planning and one BizyAir image succeeded; first malformed DeepSeek XML was rejected and visible-protocol retry passed. No Android installation/end-to-end/device visual checks; see Revision83 HANDOFF.


Revision83 Android runtime follow-up (2026-10-13): Built-in BizyAir API and storage hosts opt in to proxy Fake-IP 198.18/15 resolution; arbitrary plugin HTTP retains public-only DNS validation. Request-scoped socket cleanup runs on NonCancellable IO. Illustration status/error lives above chat input to remain visible. Actual Android emulator → DeepSeek → BizyAir → local PNG → native and custom BODY rendering verified; see HANDOFF Android supplement.
