# 插件宿主 API v2

Revision93，2026-10-13。插件继续通过普通 `plugin.run` 行为执行；本页描述 JavaScript v2 宿主能力。BizyAir 插图是独立的内置扩展；实际验收与限制以 HANDOFF 为准。

## 创建与发现

在扩展定义中选择 JavaScript v2，填写代码和网络声明；保存后在扩展详情授权并填写凭据。总启停开关仍在扩展列表卡片。已有 v1 插件保留纯文本执行与旧分支语义，不会自动升级。

配置工作台工具提供：

```json
{"op":"pluginCapabilities"}
{"op":"schema","type":"PluginCode"}
{"op":"pluginAccess","id":"你的扩展ID"}
```

第一项返回宿主方法、参数/结果 schema、示例、错误与限额；第二项返回真实资源序列化类型；第三项只返回本机授权摘要及已配置的凭据别名。Agent 可以起草资源、查看缺少什么，不能通过这些工具授予权限或读取密钥。完整离线参考也包含在 `AI_IMPORT_API_REFERENCE.json` 的 `pluginCapabilities` 中。

## 入口与权限

```javascript
async function execute(context, host) {
  const response = await host.invoke("network.http", {
    url: "https://api.example.com/v1/status",
    method: "GET",
    credential: { alias: "serviceKey" }
  });
  if (!response.ok) throw new Error(response.error.code);
  if (response.data.status !== 200) throw new Error("SERVICE_STATUS");
  return response.data.body;
}
```

`context` 仍有 `pluginId/input/state/results/messages`；内容冻结，messages 为受限 BODY 文本，代码和密钥不传入 JS。入口返回字符串或 `{text: "..."}`；要返回 JSON，应 `JSON.stringify(value)`。没有浏览器 `fetch`、DOM、文件、环境变量、任意 Android 对象或数据库接口。

对应的 `code` 声明：

```json
{
  "runtime": "javascript",
  "version": 2,
  "source": "async function execute(context, host) { return context.input; }",
  "permissions": [
    {
      "capability": "network.http",
      "origins": ["https://api.example.com"],
      "methods": ["GET", "POST"],
      "credentials": ["serviceKey"]
    }
  ]
}
```

每条规则是一组完整授权范围：目标 origin、方法和所用凭据必须匹配同一条规则。origin 不含路径、尾斜线、用户信息、查询或片段；使用小写主机名和精确端口，不支持通配符。使用国际域名时填写 ASCII/Punycode 形式。别名以英文字母开头，只包含英文字母、数字、下划线、点和连字符，最多64字符。

声明不会自动变成本机授权。授权绑定扩展 ID 及完整代码/权限摘要；修改源码或声明后新版本须重新授权。授权可撤销，不随导出迁移。模型参数、提示词等组成资源仍由既有原生编辑器管理。

## HTTP

`await host.invoke("network.http", request)` 接受：

| 字段 | 含义 |
|---|---|
| `url` | 必填，绝对 HTTPS URL |
| `method` | 默认 GET；支持 GET、HEAD、POST、PUT、PATCH、DELETE、OPTIONS |
| `headers` | 可选字符串映射，默认空 |
| `body` | 可选文本；发送 JSON 时先 JSON.stringify，并设 Content-Type: application/json |
| `credential` | 可选 `{alias, header, prefix}`，默认 header=Authorization、prefix="Bearer " |
| `timeoutMs` | 默认30000，最大60000 |
| `maxResponseBytes` | 默认262144，最大1048576 |

凭据在原生扩展详情中填写，由既有 Android Keystore 适配器加密保存于独立的插件命名空间。宿主只在授权目标的请求头中注入，不提供 JS 明文读取接口。不要把密钥写进源码、资源、普通请求头或 body。常见认证请求头必须用凭据引用。API 返回中原样回显的注入凭据会被遮盖，敏感响应头被移除；外部服务对凭据的处理仍由该服务决定。

成功获得 HTTP 响应：

```json
{"ok":true,"data":{"status":200,"headers":{"content-type":"application/json"},"body":"{\"value\":1}"}}
```

`ok=true` 表示拿到 HTTP 响应，不表示服务成功；4xx/5xx同样通过 `data.status` 检查。宿主不自动追随重定向、不重试请求、不携带浏览器 Cookie 或模型连接凭据，也不记录请求/响应内容。插件联网目前使用独立直连客户端，不继承应用代理设置；HTTPS 目的地址在 DNS 后检查并固定，拒绝本机、内网和保留地址。

网络失败或参数/权限错误：

```json
{"ok":false,"error":{"code":"PERMISSION_DENIED","message":"插件网络权限未授予或超出声明范围","retryable":false,"delivery":"not_sent"}}
```

`delivery` 为 `not_sent`（未提交）、`unknown`（可能已经到达远端）、`received`（已收到响应，可能在读取阶段失败）。不能因为 `unknown` 就重发收费 POST；插件应保留服务返回的任务 ID，优先查询原任务。当前错误码与格式以能力清单为准。未经处理的脚本异常显示经过清理的执行错误，避免把请求或密钥写入诊断。

## 等待与能力发现

```javascript
await host.invoke("runtime.sleep", { milliseconds: 2000 });
const capabilities = await host.invoke("capabilities.describe", {});
```

这两项不需要网络声明或设备授权。等待支持0–10000毫秒并可取消。每次 v2 执行总时长最多180秒、HTTP最多64次；请求体最多256KiB，最终插件输出最多128KiB。宿主还限制 JS 内存、栈和持续执行时间；无限等待/循环会失败。所有能力都经同一版本化分发入口，后续能力需增加描述、参数校验、权限和实现，不能只声明名称就取得权限。

## 结果、取消与恢复

v2 的接受结果沿用 AppController 和既有 SQLite 回执保存。即使未勾选轨迹记录，也不会像纯文本 v1 那样把结果替换成纯重算配方；切换候选、分支重建不会再次联网。旧执行版本拒绝 v2，不把冻结聊天偷偷提升到新语义。

取消会停止本地等待和 HTTP 读写，但不能证明远端任务已取消。尚未接受的远端副作用无法由本地事务回滚。本轮没有跨进程任务续跑或“恰好一次提交”保证；长任务应拆为提交、保存任务 ID、查询结果等明确阶段，专门的后台任务与图片附件能力留待后续实现。

可导入的作者示例见 `examples/plugin-v2-http.config.json`，默认关闭、使用示例域名，不含任何凭据。导入校验只证明资源合同有效，不会执行联网代码。
