# 开发插件

::: warning 预览功能
插件可从商店或本地 ZIP 安装，支持组合包、开发目录重载，提供**技能**、**子代理**、**工作区工具**、**页面／动作入口**、**AI 工具**、**MCP 服务器**、**OpenAI 兼容提供商**和**生命周期 hooks**。扩展宿主接口还包括模型句柄、文件监视、HTTP、加密键值存储、系统交互、悬浮窗与插件备份区段。hooks 分三种：只读观察只接收运行元数据；可写 hook 可在工具执行前拒绝调用或改写参数（改写后重新走完整权限判断），也可改写工具成功结果的数据，或在每次 provider HTTP 请求发出前改写 URL、headers 与 body；代审核 hook 可在宿主准备弹窗确认时批准或拒绝包括高危在内的 ASK 调用。只读观察看不到消息正文、工具参数或结果；可写 hook 会拿到对应调用的参数或结果，HTTP hook 可读取请求体，但已识别的敏感请求头用占位符代替，代审核 hook 能批准原本需要你确认的调用，只安装你信任的插件。发布入口可核对和导出插件包。仅配置了专用 GitHub 暂存区（Inbox）的本机调试版可测试投稿，写入暂存区不等于上架。正式版和 Beta 版不携带投稿凭据。AI 工具与 hooks 的业务逻辑在容器内的插件子进程中运行（`node` 或 `python3`）；设置页页面在 WebView 里运行，通过宿主注入的 `aicode` 接口读写文件与提示；MCP 服务器复用现有的 HTTP/stdio 连接链路。使用方法见[插件](/guide/plugins)。
:::

一个插件就是一个 `.zip` 包，根目录放一份 `plugin.json` 说明包里有什么，其余是 Markdown、HTML 页面、工具 schema，以及可选的 `dist/main.js` 业务入口。

## 目录结构

```text
demo-kit/
├── plugin.json
├── dist/
│   └── main.js
├── schemas/
│   └── echo.input.json
├── mcp/
│   └── project-context.json
├── skills/
│   └── project-notes/
│       └── SKILL.md
├── agents/
│   └── builder.md
└── ui/
    └── memory.html
```

没有 `runtime` 的插件是纯声明式包，可以只放技能、子代理和工作区工具页面。

`plugin.json` 必须直接位于压缩包根部，不能再套一层 `demo-kit/` 目录。

## plugin.json

```json
{
  "manifestVersion": 1,
  "id": "community.demo-kit",
  "name": {"default": "Demo Kit", "zh-CN": "演示套件"},
  "version": "1.0.0",
  "publisher": "community",
  "repository": "https://github.com/example/demo-kit",
  "description": "为项目笔记和构建任务提供技能与子代理",
  "license": "MIT",
  "engines": {"pluginApi": "^1.0.0"},
  "contributes": {
    "skills": [{"id": "project-notes", "file": "skills/project-notes/SKILL.md"}],
    "agents": [{"id": "builder", "file": "agents/builder.md"}]
  }
}
```

### 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `manifestVersion` | 是 | 固定为 `1` |
| `id` | 是 | `发布者.插件名`，每段以 Unicode 字母开头，可含字母、数字、下划线和连字符，每段最多 32 字符；也支持合法中文 ID |
| `publisher` | 是 | 必须等于 `id` 的第一段 |
| `name` / `description` | 是 | 展示给使用者的文字，可写成字符串，或按语言写成对象（见下） |
| `version` | 是 | 语义化版本，如 `1.0.0`、`1.1.0-beta.1` |
| `repository` | 是 | 你的源码仓库，必须是 `https://` 地址，不能带账号、`?` 或 `#`。AiCove 只展示它，**不核验** |
| `license` | 是 | 许可证名称，如 `MIT` |
| `engines.pluginApi` | 是 | 需要的插件接口版本范围，目前宿主提供 `1.0.0`；写 `^1.0.0` 或包含 `1.0.0` 的范围均可 |
| `contributes` | 是 | 提供的内容，至少一项 |

不认识的字段一律拒绝安装，而不是忽略——所以不要写 `official`、`permissionReviewer` 之类目前不支持的字段。

### 多语言文字

`name`、`description` 都可以写成按语言区分的对象：

```json
"name": {"default": "Demo Kit", "zh-CN": "演示套件"}
```

AiCove 优先匹配完整语言标签、书写系统、地区及语言，再使用 `default`、`en` 或第一项作为回退。中文会依书写系统与地区回退到简体 (`zh-CN`) 或繁体 (`zh-TW`)；例如系统返回 `zh-Hans-CN` 时会匹配简体名称，返回 `zh-Hant-TW` 时会匹配繁体名称。语言标签大小写不敏感，`-` 与 `_` 等价。`name` 不能换行，`description` 可以。

### 版本范围写法

`engines.pluginApi` 支持 `^1.0.0`、`~1.0.0`、`>=1.0.0 <2.0.0`、精确版本 `1.0.0`。不支持 `1.x`、`*`、`||`、`1.0.0 - 2.0.0`。

## 能力与信任边界

插件启用后，清单声明的技能、子代理、AI 工具、工作区工具、设置页、hook 和 MCP 都会生效，不再逐项授权。安装确认只展示它会提供什么。

- 插件页面通过宿主桥读写真实绝对路径与 `data://`（插件自己的数据目录），可以执行 `aicode.bash`、用 `aicode.terminal` 创建共享终端标签，也可以用 `aicode.invoke` 调用同一插件常驻进程的 handler。
- 命令与 MCP 进程内部不受宿主路径限制；只安装你信任来源的插件。
- 路径是当前执行环境的真实路径（本地为容器内，SSH 为远端），`~` 展开到 home，裸相对路径挂到工作区根；`data://` 是插件自己的数据目录。
- 插件 AI 工具与 hook 在本地容器的插件子进程中运行，可以读写自己目录与授权范围内的文件、执行命令、访问网络；host API 的路径限制只约束这些接口，不约束进程自身。
- 后续如果接入 Shizuku 这类宿主能真正拦截的系统能力，会单独增加确认开关。

## 技能文件

和[技能](/guide/skills)里的 `SKILL.md` 一样：顶部 YAML，正文是指令。

```markdown
---
description: 记录项目笔记；当用户要求总结或归档当前讨论时使用
---
把本次讨论的结论整理成要点，写入 `NOTES.md`……
```

- 技能的名字由 AiCove 生成为 `插件ID:内容id`（如 `community.demo-kit:project-notes`），文件里的 `name` 会被忽略；
- `description` 决定 AI 什么时候加载这个技能，会被压成一行写进系统提示词，请写清「何时使用」；
- 正文不能为空；
- 插件包保存在本机容器可访问的全局配置目录，技能可引用包内脚本；远程执行环境不会自动获得手机上的插件文件。需要依赖时声明运行时依赖或随包打包，不要假定服务器已安装。

## 子代理文件

格式与[子代理](/guide/subagent)的定义文件相同：YAML 配置加正文提示词。可用的配置有 `description`、`mode`、`tools`、`disallowedTools`、`inject`、`reasoningEffort`。有两点不同：

- **不能写 `provider` 和 `model`**。它们会动用使用者的付费渠道，插件子代理一律沿用主会话的模型。写了会被拒绝安装；
- `mode` 只能收紧（如 `plan`），不能放大超过主会话。

子代理同样由 AiCove 命名为 `插件ID:内容id`，派发时用这个完整名字。

## MCP 服务器

插件可以用现有 `mcp.json` 格式提供 MCP server，文件放在包里：

```json
{
  "mcpServers": {
    "project-context": {
      "url": "https://example.com/mcp",
      "headers": {"Authorization": "Bearer …"}
    }
  }
}
```

清单声明方式：

```json
{
  "contributes": {
    "mcp": [{"id": "project-context", "file": "mcp/project-context.json"}]
  }
}
```

- 支持 HTTP 和 stdio；stdio 会在当前工作区的容器或 SSH 后端启动进程。
- 安装预览会显示 MCP 名称和连接目标。
- 插件贡献的 server 名会加 `plugin__` 命名空间，不会覆盖你在「设置 → MCP」里手工创建的配置；同名冲突时用户配置优先。
- 插件停用、撤权或卸载时，对应的 MCP 连接和工具会一起移除。
- stdio 配置里的 `command`、`args`、`env` 会原样用于启动进程；只安装你信任的插件。

## 插件提供商

插件可以登记一个 OpenAI 兼容服务，宿主复用现有 OpenAI 适配器，不内置新的上游协议。

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，同类内唯一 |
| `name` | 是 | 本地化名称 |
| `description` | 否 | 本地化说明 |
| `format` | 是 | 第一版只支持 `openai` |
| `baseUrl` | 是 | `http`/`https` 绝对地址；可指向固定外部服务或容器内 `http://127.0.0.1:<port>` |

```json
"contributes": {
  "providers": [
    {
      "id": "gateway",
      "name": {"zh-CN": "本地网关", "en": "Local Gateway"},
      "format": "openai",
      "baseUrl": "http://127.0.0.1:18080/v1"
    }
  ]
}
```

插件负责启动兼容服务并保证宿主可访问；插件启用或安装后，宿主在设置 → 提供商里创建带「插件」标记的普通条目。条目与自建渠道同权：可以改名、改地址、改凭据、启停，在模型页从兼容服务 `/v1/models` 拉取并选择模型。插件停用或卸载时，该插件注册的条目会被删除；服务未就绪时请求直接失败，宿主不会回退到其他付费渠道。

## Shizuku 权限

插件可以声明通过 Shizuku 执行 Android 系统命令的范围。Shizuku 是插件级独立授权：安装或启用插件不会自动获得，必须在插件详情里单独打开。

```json
"contributes": {
  "shizuku": {
    "purpose": "切换免打扰与查询系统设置"
  }
}
```

授权后，该插件可以直接执行任意 Shizuku 命令，不逐次确认；实际执行还要求 Shizuku 服务运行且 App 已获系统授权。Shizuku 授权是插件级独立开关，启用插件本身不会自动获得。

## AI 工具与运行时

AI 工具需要 `runtime`，包内放一个入口脚本。AiCove 在本地容器的子进程里运行它，工作目录是插件自己的安装目录；进程通过 stdin/stdout 的 NDJSON JSON-RPC 与宿主通信，可以读写文件、执行命令、发事件。

```json
{
  "runtime": {"type": "javascript", "entry": "dist/main.js"},
  "contributes": {
    "tools": [
      {
        "id": "echo",
        "title": "回显",
        "description": "把传入的文本原样回显",
        "inputSchema": "schemas/echo.input.json",
        "handler": "tools.echo"
      }
    ]
  }
}
```

- `type` 支持 `javascript`（用 `node` 启动）与 `python`（用 `python3` 启动）；可用 `command` 覆盖启动命令。
- 可选 `resident: true`：进程不参与 10 分钟空闲回收，首次启动后常驻；插件停用、卸载、替换安装（包括同版本重装）或 App 进程结束仍会关闭。常驻进程会持续占用内存与容器资源。
- 可选 `dependencies` 声明依赖输入文件：
  ```json
  "dependencies": {"tool": "npm", "file": "package.json"}
  ```
  在插件详情页点「准备依赖」会在插件目录执行 `npm ci`/`npm install` 或 `python3 -m venv` + `pip install`。容器缺少 node/npm 或 python3 时只提示去终端「环境安装」，不会自动装系统包。准备成功的状态会持久保存；准备期间插件被替换，或状态无法保存时，会提示重新准备，旧安装的结果不会应用到新安装。

常驻插件可在 App 启动时初始化进程：

```json
{
  "runtime": {"type": "javascript", "entry": "dist/main.js", "resident": true},
  "contributes": {
    "hooks": [{"id": "boot", "event": "app.started", "handler": "hooks.boot"}]
  }
}
```

`app.started` 只在 App 主进程启动完成时派发一次，payload 含 `launchId`、`startedAt`、`appVersion`、`process`。handler 应在完成初始化后尽快返回，需要常驻的服务请在进程内自行后台化；观察 hook 单次超时 30 秒，超时会关闭该插件进程。容器未初始化或缺少 node/python 时 hook 会失败并记日志；之后再调用工具或 hook 仍会按需启动进程。

### 进程协议

宿主与插件都按行读写 JSON：

| 方向 | 方法 | 说明 |
| --- | --- | --- |
| 宿主 → 插件 | `tool.invoke` | `params: {handler, args}`，返回 `result` |
| 宿主 → 插件 | `hook.invoke` | `params: {handler, args}`，返回 `result` |
| 宿主 → 插件 | `ui.invoke` | 插件页面调用常驻进程：`params: {handler, args}`，返回 `result` |
| 插件 → 宿主 | `host.fs.read/write/list/stat/delete` | 真实绝对路径或 `data://` |
| 插件 → 宿主 | `host.bash` | `params: {command, timeout}`，在当前工作区执行 |
| 插件 → 宿主 | `host.terminal.run` / `poll` / `stop` / `close` | 创建、轮询、停止、关闭共享终端标签，参数与页面桥 `terminal.*` 一致 |
| 插件 → 宿主 | `host.notify` | `params: {title, body, key?}`，发系统通知；同一 `key` 覆盖上一条 |
| 插件 → 宿主 | `host.tip` | `params: {title?, text, action?{label, handler}}`，在对话内显示可关闭提示条；按钮点击以 `ui.invoke` 回调 `handler` |
| 插件 → 宿主 | `host.shizuku.exec` | `params: {command, timeout?}`，经 Shizuku 在 Android 系统执行；插件声明用途且使用者已授权后直接执行 |
| 插件 → 宿主 | `plugin.event`（通知） | `params: {name, payload}`，推送事件到已打开的插件页面 |

入口至少要实现一个消息循环：

```js
const readline = require('readline');
const handlers = {
  'tools.echo': (args) => ({ text: String(args.text ?? '') })
};

readline.createInterface({ input: process.stdin }).on('line', (line) => {
  const message = JSON.parse(line);
  if (!message.method || message.id === undefined) return; // 宿主对 host API 的响应 / 通知
  const handler = handlers[message.params.handler];
  const respond = (payload) => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: message.id, ...payload }) + '\n');
  if (typeof handler !== 'function') return respond({ error: { code: 'HANDLER_NOT_FOUND', message: message.params.handler } });
  Promise.resolve(handler(message.params.args || {}))
    .then((result) => respond({ result: result === undefined ? null : result }))
    .catch((error) => respond({ error: { code: 'PLUGIN_ERROR', message: String(error && error.message || error) } }));
});
```

- handler 可以返回字符串、数字、布尔、数组或对象；抛出的异常会作为工具错误返回给 AI。
- 工具对 AI 暴露的名字由宿主生成，形如 `plugin__community_demo-kit__echo`，不会和内置工具重名。
- 每次调用仍走 AI 工具权限判断，PLAN 模式下按外部工具拦截。
- 进程与 Bash 同级，拥有容器内文件、网络与命令能力；host API 的路径限制只约束这些接口，不约束进程自身。只安装可信插件。
- 进程启动或调用失败会以工具错误返回；超时会结束该次进程，避免死循环持续占用。

## 生命周期 hooks

hooks 有三种模式：

- **`observe`（默认）**：只读通知，宿主异步派发，适合记录日志或统计；
- **`transform`**：可写，宿主在固定拦截点等待结果，校验通过后应用；失败、超时或格式错误都保持原值继续；
- **`review`**：代审核，只在宿主判断需要使用者确认（ASK）后运行，返回 allow/deny/pass。

`transform` hook 目前支持三个事件，且必须显式写 `"mode": "transform"`：

| 事件 | 时机 | 可返回的变更 |
| --- | --- | --- |
| `agent.tool.before` | 工具执行前、权限判定前 | `decision: "deny"` 阻止执行；`arguments` 改写参数 |
| `agent.tool.after` | 工具成功结束后 | `data` 改写成功结果的数据 |
| `agent.http.before` | 每次实际发出 provider HTTP 请求前 | `url`、`headers`、`body` 改写请求 |

代审核事件必须显式写 `"mode": "review"`：

| 事件 | 时机 | 可返回的决定 |
| --- | --- | --- |
| `agent.permission.review` | 宿主判定为 ASK 后、弹出用户确认前 | `allow` / `deny` / `pass` |

::: warning 高权限能力
`agent.http.before` 的 payload 保留请求正文（可能含对话内容），已识别的 `Authorization`、API Key、Cookie 等敏感头以 `{{Token}}` 占位。同 origin 改写可保留宿主原始敏感值，跨 origin 不继承这些值。占位规则不构成对任意自定义正文的去敏保证。
:::

只读观察事件有八个：

| 事件 | 时机 |
| --- | --- |
| `app.started` | App 主进程启动完成时；用于常驻插件初始化 |
| `agent.run.started` | 一轮对话开始处理时 |
| `agent.run.finished` | 一轮对话结束时；成功、失败或被取消都会发出 |
| `agent.request.started` | 一次模型请求即将发出时 |
| `agent.request.finished` | 一次模型响应结束；成功、失败或取消都会发出 |
| `agent.tool.started` | 一次工具调用真正开始执行时 |
| `agent.tool.finished` | 一次工具调用结束；成功或失败 |
| `agent.compaction` | 上下文被压缩时 |

hooks 需要 `runtime`，`handler` 是进程 handler 表里的键（与 AI 工具共用），由宿主通过 `hook.invoke` 调用：

```json
{
  "runtime": {"type": "javascript", "entry": "dist/main.js"},
  "contributes": {
    "hooks": [
      {"id": "run-finished", "event": "agent.run.finished", "handler": "hooks.runFinished"},
      {"id": "guard-bash", "event": "agent.tool.before", "handler": "hooks.guardBash", "mode": "transform"},
      {"id": "redact-result", "event": "agent.tool.after", "handler": "hooks.redactResult", "mode": "transform"},
      {"id": "review", "event": "agent.permission.review", "handler": "hooks.reviewPermission", "mode": "review"}
    ]
  }
}
```

```js
const handlers = {
  "hooks.runFinished": (payload) => ({
    logs: [`run finished ${payload.status} toolCalls=${payload.toolCalls}`]
  }),
  "hooks.guardBash": (payload) => {
    if (payload.toolName === "bash" && /rm\\s+-rf/.test(payload.arguments.command ?? "")) {
      return { decision: "deny", reason: "禁止递归删除" };
    }
    return { arguments: payload.arguments };
  },
  "hooks.redactResult": (payload) => ({
    data: { text: String(payload.data?.text ?? "").replace(/ghp_[A-Za-z0-9]+/g, "***") }
  }),
  "hooks.reviewPermission": (payload) => {
    if (payload.toolName === "bash" && payload.arguments.command === "ls") {
      return { decision: "allow", reason: "只读列目录" };
    }
    return { decision: "pass" };
  }
};
```

`handlers` 的接入方式见上面的进程消息循环；hook 与 AI 工具共用同一个进程与 handler 表。

### 事件内容

所有事件都带这些公共字段：

| 字段 | 说明 |
| --- | --- |
| `runId` | 本轮运行的标识，同一次运行的各事件一致 |
| `sessionId` | 会话标识；主会话与子代理会话各有一个 |
| `scope` | `main` 或 `subagent` |
| `agentName` | 子代理名称；主会话为 null |
| `mode` | 事件发生时的模式：`BUILD`、`PLAN` 或 `AUTO` |

各事件的专属字段：

| 事件 | 额外字段 |
| --- | --- |
| `agent.run.started` | `status=started`、`toolCalls=0`、`errorCode=null` |
| `agent.run.finished` | `status`（`success`/`error`/`cancelled`）、`toolCalls`、`errorCode` |
| `agent.request.started` | `requestId`、`messageCount`、`toolCount`、`estimatedInputTokens` |
| `agent.request.finished` | `requestId`、`status`（`success`/`error`/`cancelled`）、`inputTokens`、`outputTokens`、`toolCalls`、`durationMs`、`stopReason`、`errorCode` |
| `agent.tool.started` | `callId`、`toolName` |
| `agent.tool.finished` | `callId`、`toolName`、`status`（`success`/`error`）、`durationMs` |
| `agent.compaction` | `compacted=true`、`estimatedInputTokens` |
| `app.started` | `launchId`、`startedAt`、`appVersion`、`process`（无 run/session 公共字段） |
| `agent.tool.before` | `callId`、`toolName`、`arguments` |
| `agent.tool.after` | `callId`、`toolName`、`status=success`、`data` |
| `agent.http.before` | `url`、`method`、`headers`、`body`（不带头部的公共字段） |
| `agent.permission.review` | `callId`、`toolName`、`arguments` |

只读事件的 payload 里**没有**消息正文、工具参数、工具结果和模型输出；可写事件会提供它要处理的参数或结果快照。

### 可写 hook 的返回值

`agent.tool.before`：

```json
{"decision": "deny", "reason": "策略说明", "arguments": {"path": "safe.txt"}}
```

- `decision` 只能是 `allow` 或 `deny`，缺省按 `allow`；`deny` 会立即停止后续 hook，返回给 AI 的工具错误码是 `PLUGIN_HOOK_DENIED`；
- `arguments` 必须是 JSON 对象，且不能改工具名；
- 参数被改写后，宿主按最终参数重新执行完整权限判断与模式限制，原批准不会沿用。

`agent.tool.after`：

```json
{"data": {"text": "已脱敏结果"}}
```

- 只能改 `data`；`status`、图片附件、文件卡片与错误结果不可改，失败的工具调用不会触发该事件；
- 宿主把改写后的结果同时用于界面展示与回放给模型。

`agent.http.before`：

```json
{
  "url": "https://gateway.example/v1/chat/completions",
  "headers": {"Authorization": "Bearer new-token", "X-Route": "team-a"},
  "body": "{\"model\":\"gpt-4o\",\"messages\":[]}"
}
```

- `url` 必须是 http/https 地址，最长 4096 字符；不返回则保持原 URL；
- `headers` 是完整替换表（不是增量合并），最多 64 项，值为字符串；`Host`、`Content-Length`、`Transfer-Encoding`、`Connection`、`Expect`、`Upgrade` 不允许改写；
- `body` 必须是合法 JSON 对象文本，最大 4 MiB；方法固定为 `POST`，插件不能改请求方法；
- 该 hook 挂在共享 OkHttp 客户端上，覆盖对话、标题生成、上下文压缩、模型列表/测试与图片请求；**每次实际发送都会执行**，网络重试或换 Key 会重复触发，插件应保持幂等；
- `body` 就是该 provider 的完整请求 JSON，因此系统提示词、消息列表、工具定义与模型参数都在可改写范围内；
- 宿主只校验 JSON 对象、大小与字段白名单，不校验 provider 语义：改写消息可能破坏工具调用配对或 Anthropic 签名，改工具名可能与宿主 `ToolRegistry` 不一致，改提示词不会同步更新 token 估算与压缩决策；
- 该 hook 也作用于标题生成与上下文压缩请求，插件需自行根据 body 内容区分请求类型；
- 超时、异常或返回值格式不合法时保持原请求继续。

`agent.permission.review`：

```json
{"decision": "allow", "reason": "只读命令，允许本次执行"}
```

- `decision` 只能是 `allow`、`deny` 或 `pass`；缺省按 `pass`；
- `allow` 只批准当前一次调用，不写入“始终允许”规则，也不改变后续调用；
- `deny` 只拒绝当前工具调用，返回错误码 `PLUGIN_REVIEW_DENIED`，其他工具继续审批与执行；
- `pass` 继续询问下一个代审核插件，全部不处理才进入原有用户确认 UI；
- 插件不能改工具参数，也不能覆盖宿主已经做出的 DENY、ALLOW/自动放行或 PLAN 模式限制；
- 已开放全部 ASK：Shizuku、`elevate` 提权、配置修改、删除子代理、危险 rm 都可代审；`planMode` 模式切换仍由本人处理；插件不能代审自己提供的工具。

多个可写 hook 按插件 ID 升序、各自清单顺序执行，后一个看到前一个改写后的值。单次调用上限 10 秒，一次拦截合计上限 20 秒；超时、异常或返回值格式不合法时跳过该 hook 并保持当前值，**不会因此自动放行或自动拒绝**。代审核同样是顺序执行、首个 allow/deny 生效；超时或异常按 `pass` 继续，最终无人处理时回到用户确认。

### 调试输出

handler 可以返回 `{ logs: ["..."] }`，宿主把每行写进应用日志（最多 50 行、单行 500 字符）。可写 hook 的 `decision`、`arguments`、`data`、`url`、`headers`、`body` 与 `logs` 可以同时返回；代审核的 `decision`、`reason` 与 `logs` 可以同时返回。

### 边界

- 只读 hook 异步派发，不进入 Agent 主流程；可写 hook 在固定拦截点等待结果，受上述超时约束；
- **不能提权**：可写 hook 只能拒绝或缩小调用，不能批准工具、不能把宿主拒绝改成允许、不能绕过 PLAN 模式或权限策略；
- 改写参数后必须重新走完整权限判断；审批后不会悄悄改写参数；
- `agent.tool.after` 只处理成功结果，不能把错误改成成功，也不能伪造附件或图片；
- `agent.http.before` 不能读取已识别鉴权头的真实值；它不能改请求方法，也不能把请求改成非 JSON。跨 origin 改写会剥离原敏感头，响应仍按原 provider 格式解析；
- `agent.permission.review` 能批准原本需要用户确认的调用，属于高权限能力；它不能改参数、不能覆盖宿主 DENY/PLAN、不能写入永久允许规则，也不能代审自己的工具；
- hook 与 AI 工具共用同一个插件进程；进程拥有容器内的文件与命令能力，可写 hook 能读取对应工具的参数与结果，只安装可信插件；
- 单项观察 hook 超时 30 秒，超时会关闭该插件的运行时；连续失败目前不会自动停用插件。

## 模型、悬浮窗与备份声明

`contributes.modelAccess` 为 `{ "purpose": "说明调用模型的用途" }`，允许已启用插件通过宿主模型句柄发起请求，不暴露供应商 Key。请求不会自动执行模型返回的工具调用；需要工具时通过 `aicove.tools.execute` 并处理本插件的审批。模型句柄与在途流在停用、卸载或替换时失效。

`contributes.overlay` 包含 `purpose` 和 `surfaces`，每个 surface 提供 `id`、包内 HTML `page`，以及可选的 `width`、`height`、`draggable`、`snap`（`none`／`x`／`y`／`both`）。插件详情中的独立授权和 Android 系统悬浮窗权限都满足后才可创建；撤权或卸载关闭窗口。

`contributes.backup` 为 `{ "purpose": "说明附加数据用途" }`。需要备份开关时，在 `uiEntries` 声明 `slot: "backup.data"` 的 DATA 项，不填 `page` 或 `handler`。用户单独授权并在备份页勾选后，观察 hook `backup.export.before` 收到 `{dir:"data://backup-export"}`；宿主只归档这个暂存区。导入主数据成功后，`backup.import.finished` 收到 `{dir:"data://backup-import",stats:{chatSessions,workspaceFiles}}`，只向仍启用且获授权的对应插件回交数据。禁止符号链接和越界路径，区段最多 1000 个文件、单文件 16 MiB、总计 64 MiB；临时区会在结束或失败时清理。

## 插件事件

进程可以主动发事件，宿主会把它推给同一插件已打开的页面：

```js
process.stdout.write(JSON.stringify({
  jsonrpc: '2.0',
  method: 'plugin.event',
  params: { name: 'echo.done', payload: { length: 4 } }
}) + '\n');
```

页面侧：

```js
const off = aicode.events.on((event) => {
  console.log(event.name, event.payload);
});
```

- 事件只发给同一插件、当前已加载的页面；没有页面时丢弃，不排队。
- 不跨插件转发，也不会注入会话或触发新的 AI 推理。

## 工作区工具页面

工作区工具通过 `contributes.workspaceTools` 声明，`pageFile` 指向包内 HTML 页面。用户可以在右侧项目工具抽屉里添加并打开；页面通过 `window.aicode` 桥读写数据、执行命令或发事件。命令与文件操作需要你打开页面并点击对应操作才会发生。

短命令可以用 `aicode.bash` 同步执行；需要后台运行、实时输出或停止时，用 `aicode.terminal.run` 创建共享标签，再用 `terminal.poll(tabId, offset)` 按偏移取增量输出、`terminal.stop` 发送 Ctrl-C、`terminal.close` 关闭。标签默认不在终端页显示，用户在「设置 → 终端设置 → 显示插件运行」开启后才可见；标签归属创建它的插件，其他插件和 AI 的 terminal 工具不能操作。

## 插件专属配置页面与设置页入口

插件可以使用 HTML/WebView 构建自己的界面，分为两种不同场景：

### 1. 插件专属配置页面（`configPage`）

如果插件需要提供自身参数配置（如填写 API Key、设置开关、选项调整等），可在 `contributes` 或顶层直接声明 `configPage`，指定包内的 HTML 相对路径：

```json
{
  "id": "community.demo-kit",
  "contributes": {
    "configPage": "ui/config.html"
  }
}
```

- **位置独立**：该页面只在「设置 → 插件」点击该插件后的详情界面中展示，与 AiCove 固定的原生配置（启停、卸载）通过分段控件无缝切换，**不会混入设置主菜单**；
- **随插件启用**：属于插件自身的专属配置 UI，启用插件即可加载使用；
- **WebView 能力一致**：自动注入 App 全套主题 Token（CSS 变量、暗黑/浅色模式平滑同步），并提供 `window.aicode` 桥接接口读写私有数据目录（`data://`）。

### 2. 宿主槽位入口（`uiEntries`）

插件可以在宿主 UI 的指定槽位注册页面或动作入口（如设置页某个分组、侧栏底部、聊天顶栏菜单），使用 `contributes.uiEntries`：

```json
{
  "contributes": {
    "uiEntries": [
      {
        "id": "memory",
        "title": { "zh-CN": "记忆管理", "en": "Memory" },
        "slot": "settings.ai",
        "page": "pages/memory.html",
        "icon": "book"
      }
    ]
  }
}
```

`slot` 只能取下列值，宿主按它把入口放进对应位置：

| 值 | 位置 |
| --- | --- |
| `settings.general` | 设置 · 通用 |
| `settings.ai` | 设置 · AI 配置 |
| `settings.environment` | 设置 · 运行环境 |
| `settings.permissions` | 设置 · 权限与后台 |
| `settings.data` | 设置 · 数据与诊断 |
| `settings.help` | 设置 · 帮助与关于 |
| `drawer.footer` | 聊天侧栏底部（设置入口上方） |
| `chat.header.menu` | 聊天顶栏「更多」菜单底部 |
| `chat.welcome` | 聊天空态 |
| `chat.session.menu` | 被长按的会话菜单 |
| `files.tree.menu` | 被长按的文件／目录菜单，仅动作 |
| `editor.selection.menu` | 编辑器真实选区的动作菜单 |
| `backup.data` | 备份数据开关，仅 DATA，不提供 page 或 handler |

页面入口用 `page` 指向包内 HTML，动作入口用 `handler` 调用同插件运行时，二者不能同时提供。设置六槽仅接受页面；文件和编辑器槽仅接受动作。动作声明 `confirm: true` 时执行前由宿主确认。页面最大 512 KiB，每插件每槽最多 2 个入口；停用或卸载后消失。

> 旧版清单里的 `contributes.settingsPages` + `anchor` 已换成 `uiEntries` + `slot`，旧写法不再被识别。

### 编辑器选区动作

编辑器选中文本后，浮动菜单里会多出一个「插件」按钮，点开列出当前插件提供的动作。动作以 `handler` 形式在插件常驻进程里执行，宿主把选区上下文作为参数传进去：

| 字段 | 含义 |
| --- | --- |
| `path` | 当前文件路径 |
| `selection` | 选中的文本，上限 16000 字符 |
| `language` | 文件扩展名推断出的语言 |
| `lineStart` / `lineEnd` | 选区的起止行号（从 1 开始） |

动作执行完可以用 `aicode.chat.setDraft` 把结果写回输入框，由用户决定是否发送。

### 页面里的宿主接口

宿主向页面注入 `window.aicove`，同时保留兼容别名 `window.aicode`；下列原有写法仍可用。请求方法返回 Promise，事件订阅返回取消订阅函数：

| 方法 | 作用 |
| --- | --- |
| `aicode.context()` | 插件 id、页面 id、版本、语言、主题、工作区名，以及 `home` / `workspace` / `dataDir` 三个真实路径 |
| `aicode.chat.sessions(options?)` | 列出会话，返回 `{ sessions }`；`options.workspacePath` 可指定其他工作区，不传则用当前工作区 |
| `aicode.chat.create({title?, mode?, workspacePath?})` | 新建会话；`workspacePath` 可指定落在哪个工作区，不传用当前工作区 |
| `aicode.chat.messages({sessionId, limit?, before?, after?})` | 读取消息，返回 `{ sessionId, messages, hasMore }` |
| `aicode.chat.send({sessionId, text})` | 向会话发一条用户消息并启动 AI 轮次，走聊天页现有队列与授权链路 |
| `aicode.chat.stop(sessionId)` | 停止该会话正在跑的轮次 |
| `aicode.chat.open(sessionId)` | 把该会话切换为聊天页当前会话 |
| `aicode.chat.get(sessionId)` | 取单个会话详情 |
| `aicode.chat.rename(sessionId, title)` | 重命名会话 |
| `aicode.chat.pin(sessionId, pinned)` | 置顶/取消置顶 |
| `aicode.chat.remove(sessionId)` | 删除会话及其消息 |
| `aicode.chat.runState(sessionId)` | 查询该会话是否还在跑，返回 `{ running, status, error?, startedAt?, finishedAt? }` |
| `aicode.chat.waitRun(sessionId, timeoutMs?)` | 等该会话跑完再返回，超时返回最后一次快照 |
| `aicode.chat.setDraft(text, {sessionId?, append?})` | 把文本写进该会话的输入框草稿，只改草稿不发送；不传 `sessionId` 用当前工作区最近更新的会话 |
| `aicode.workspaces.list()` | 列出工作区，返回 `{ current, workspaces: [{name, path, remote, available}] }` |
| `aicode.fs.read(path)` | 读取文件，返回 `{ content, size }` |
| `aicode.fs.write(path, content)` | 写入文件（原子写） |
| `aicode.fs.list(path)` | 列出目录，返回 `{ entries: [{ name, isDir, size }] }` |
| `aicode.fs.delete(path)` | 删除文件或目录 |
| `aicode.fs.stat(path)` | 返回 `{ exists, isDir, size }` |
| `aicode.fs.mkdir(path)` | 创建目录（含父目录），已存在视为成功 |
| `aicode.fs.append(path, content)` | 在现有内容后追加 |
| `aicode.fs.readBinary(path)` | 读原始字节，返回 `{ base64, size }` |
| `aicode.fs.writeBinary(path, base64)` | 写入 Base64 解码后的字节 |
| `aicode.fs.rename(from, to)` | 移动/重命名，不能把目录移进自身 |
| `aicode.fs.copy(from, to, {overwrite?, contextKey?})` | 复制文件或目录，不能复制进自身 |
| `aicode.toast(message)` | 轻提示 |
| `aicode.tip({title?, text, action?{label, handler}})` | 在对话内显示可关闭提示条；按钮点击回调同插件进程的 `handler` |
| `aicode.shizuku.exec(command, options)` | 经 Shizuku 在 Android 系统执行；插件声明用途且已单独授权后直接执行，`options.timeout` 以秒计 |
| `aicode.invoke(handler, args)` | 调用同一插件常驻进程的 handler，返回其 `result`；不支持跨插件 |
| `aicode.bash(command, options)` | 在当前工作区执行一次性 Shell 命令，返回 `{ output, exitCode, truncated }` |
| `aicode.terminal.run(command, options)` | 创建共享终端标签，返回 `{ tabId, state }`；`options.title` 可选 |
| `aicode.terminal.poll(tabId, offset)` | 返回 `{ output, offset, state, exitCode?, truncated }`；`state` 为 `running` / `finished` / `cancelled` |
| `aicode.terminal.stop(tabId)` | 发送 Ctrl-C；超时仍未退出则关闭标签 |
| `aicode.terminal.close(tabId)` | 关闭标签；标签生命周期由插件维护 |
| `aicode.ui.close()` | 退出插件页，回到设置页（App 级返回） |
| `aicode.ui.setOverlay(visible, rect)` | 打开/关闭页面内全屏弹层并上报弹层矩形；宿主在全局背景图上按矩形挖洞，避免背景叠到弹层上 |
| `aicode.ui.onBack(handler)` | 接管页面内返回；`handler` 同步返回 `true` 表示已处理 |
| `aicove.model.listProviders()` / `listModels(providerId)` | 列出可用提供商和模型，不返回密钥 |
| `aicove.model.create(options)` / `close(handleId)` | 创建／释放宿主管理的模型句柄，须声明 modelAccess |
| `aicove.model.start(options)` / `read(options)` / `cancel(streamId)` | 拉取式流请求；`stream(options, onEvent)` 封装 start/read 循环 |
| `aicove.fs.watch(path, options?, handler)` | 订阅有界文件范围，Promise 返回取消订阅函数；页面隐藏或环境切换时暂停／失效 |
| `aicove.tools.list({includeSchema?})` / `execute(name, args, options?)` | 查询和执行内置／MCP 工具；复用宿主策略，禁止经此递归调用插件工具 |
| `aicove.tools.pending(sessionId?)` / `resolve(requestId, choice)` | 仅处理本插件命名空间内的工具审批 |
| `aicove.http.request(options)` | 宿主 HTTP 请求，不自动附带宿主凭据、不跟随重定向 |
| `aicove.secrets.get/set/delete/list` | 插件私有加密存储；list 只返回键名 |
| `aicove.clipboard.read/write`、`share.text`、`ui.openUrl`、`app.info` | 系统交互和宿主信息 |
| `aicove.overlay.create/update/close/postMessage` | 声明并单独授权后的系统悬浮窗 |

返回键按这个顺序处理：WebView 自身历史（如 hash 路由）→ `onBack` 注册的处理器 → 退出插件页。页面有多层视图时用 `onBack` 接管返回，回到最外层时返回 `false`，交给宿主退出；顶栏自绘的返回按钮在首页调用 `aicode.ui.close()`，在子页调用自己的路由返回。

`AicodeBottomSheet`、`AicodeDialog` 打开时会自动上报弹层矩形并调用 `aicode.ui.setOverlay(true, rect)`，关闭后恢复；页面自己绘制的全屏弹层也应成对调用，`rect` 取弹层元素 `getBoundingClientRect()` 的 `left/top/width/height`，并用 `topRadius`/`bottomRadius` 描述圆角，避免全局背景图叠在弹层上。

`aicode.invoke` 只允许调用当前页面对应插件的进程，参数与返回值都走 JSON；它会让宿主按需启动该插件的 runtime，单次等待上限 60 秒。插件进程需要处理 `ui.invoke` 方法，否则会返回 `METHOD_UNKNOWN`。

`aicode.bash` 在插件页面所属工作区执行命令，返回 stdout/stderr 合并输出、退出码与是否被截断；`options.timeout` 以秒计，默认 30、上限 300。它适合版本检测、状态查询这类短命令，不要用来启动常驻进程或交互式程序；命令执行环境的初始化由终端页负责，未初始化时返回的退出码为 `null`，输出里会带引导提示。

`aicode.terminal` 在共享终端标签里后台运行命令，页面关闭不会停止。输出按偏移轮询，宿主只保留最近 128 KiB，超出后 `truncated` 为 true。插件停用或卸载时，宿主会关闭它创建的全部标签。

路径必须以虚拟根开头：

| 路径 | 含义 | 读写 |
| --- | --- | --- |
| `data://` | 插件自己的数据目录，卸载时清理，与插件 ID 和执行环境解耦 | 读写 |
| 绝对路径 | 当前执行环境的真实路径：本地为容器内路径（如 `/root/workspace/a.txt`），SSH 为远端服务器路径 | 读写 |
| `~/...` | 展开到当前环境的 home | 读写 |
| 裸相对路径 | 挂到工作区根 | 读写 |

单次读取上限 512 KiB，写入上限 1 MiB，目录最多返回 500 条。

### 主题与样式

页面引用宿主动态生成的 `/__aicode/theme.css`，它提供跟随 App 主题的 CSS 变量：

```html
<link rel="stylesheet" href="/__aicode/theme.css">
<script src="/__aicode/aicode.js"></script>
```

可用变量包括 `--aicode-bg`、`--aicode-surface`、`--aicode-on-surface`、`--aicode-primary`、`--aicode-error`、`--aicode-success`、`--aicode-warning`、`--aicode-radius-*`、`--aicode-space-*`、`--aicode-font-scale`。深浅色切换时宿主直接更新变量，页面不会重新加载。

表单可以直接使用宿主的 Vue 组件库，输入框的圆角、描边、文字间距以及深浅色、聚焦和禁用状态与 App 的输入框保持一致：

```html
<link rel="stylesheet" href="/__aicode/ui.css">
<script src="/__aicode/vue.global.prod.js"></script>
<script src="/__aicode/aicode-ui-vue.js"></script>

<aicode-field label="名称">
  <aicode-input v-model="name" placeholder="输入名称"></aicode-input>
</aicode-field>
<aicode-field label="描述">
  <aicode-textarea v-model="description" placeholder="输入描述"></aicode-textarea>
</aicode-field>
```

在 Vue 应用挂载前调用 `app.use(AicodeUI)` 注册组件。每个 `aicode-field` 放一个输入组件；空值且未聚焦时，标签显示在框内，聚焦或有内容时标签浮到边框处，提示文字在标签浮起后显示。点击标签也能聚焦输入框；通过 `disabled` 禁用时，底色保持不变，文字与边框变淡。单独使用 `aicode-input` 或 `aicode-textarea` 时同样应用输入框样式。

`aicode-input` 设置 `type="password"` 时，默认隐藏内容，右侧自动显示明文／隐藏切换按钮；切换只改变显示方式，保留原内容和正在编辑的光标位置。输入框禁用时，切换按钮也会禁用。

### 输入弹窗与文字确认弹窗

`aicode-dialog` 对齐 App 的居中对话框：标题、内容和底部文字按钮分区排列，取消在左、确认在右。两种常见用法分别是：

```html
<!-- 文字确认 -->
<aicode-dialog v-model="confirmOpen" title="确认操作"
  message="确定继续执行吗？" @confirm="runAction"></aicode-dialog>

<!-- 输入表单 -->
<aicode-dialog v-model="editOpen" title="修改名称" confirm-text="保存"
  :confirm-disabled="!name.trim()" @confirm="saveName">
  <aicode-field label="名称">
    <aicode-input v-model="name" placeholder="输入名称"></aicode-input>
  </aicode-field>
</aicode-dialog>
```

文字确认通过 `message` 显示说明；输入弹窗把输入组件放在默认插槽中，输入框底色透出弹窗颜色，内容过长时只滚动内容区，底部按钮保持可见。`confirm-disabled` 为 `true` 时不能确认，适合必填项未完成时禁用保存。确认会触发 `confirm` 并关闭；取消按钮、遮罩、返回键或 Escape 会触发 `cancel` 并关闭。输入值由页面的 `v-model` 管理，取消时是否恢复原值也由页面决定。

`aicode-bottom-sheet` 仍用于底部弹窗内容，窄屏从底部展开，宽屏居中；简单输入和文字确认直接使用 `aicode-dialog`。

### 限制

- 页面资源只能从插件包经虚拟域加载，外部链接会被宿主拦截；
- 页面与 AI 工具运行在同一插件的容器进程体系里：页面有 DOM 与桥，可通过 `aicode.invoke` 调用同插件进程的 `ui.invoke`；AI 工具 handler 没有 DOM；
- 宿主只对已启用且授予 `settings-pages.register` 的插件注入桥；插件停用后入口消失，页面请求会被拒绝；
- 设置页入口最多按声明顺序展示，超出宿主限制的入口会被忽略。

## 在对话中管理插件

宿主工具 `plugin` 支持 `list`、`install`、`reload`、`enable`、`disable`、`uninstall`、`logs`。开发目录为 `~/.aicove/plugin-dev/<项目>`；`install(source="dev", path="目录名")` 和 `reload(plugin_id="发布者.插件名")` 都走统一安装服务。安装、重载、启停和卸载仍受当前运行模式与工具授权约束，不能用插件自调用绕过。

## 打包

在目录里执行（注意最后的 `.`，确保 `plugin.json` 在压缩包根部）：

```bash
cd demo-kit
zip -r ../demo-kit-1.0.0.zip .
```

容器里没有 `zip` 时可以先 `apk add zip`，或用 Python：

```bash
python3 -c "import shutil; shutil.make_archive('../demo-kit-1.0.0', 'zip', '.')"
```

也可以让 AI 在容器里帮你写目录和文件并打包；**导入和授权始终要你自己在「设置 → 插件」里确认**，AI 不能替你安装。

## 安全限制

| 项 | 限制 |
| --- | --- |
| 压缩包大小 | 16 MiB |
| 文件数量 | 512 个 |
| 单个文件 | 4 MiB（技能 / 子代理的 Markdown 文件 512 KiB，`plugin.json` 256 KiB，运行时入口 512 KiB，工具 schema / MCP 配置 256 KiB） |
| 解压后总大小 | 32 MiB |
| 路径 | 不能是绝对路径，不能含 `..`、`.`、空段或反斜杠，长度 ≤ 200，层级 ≤ 8 |
| 重复文件 | 不允许，包括只有大小写不同的 |

超出任何一项都会拒绝整个包。

`aicode.bash` 让页面在当前工作区执行命令，能力与你在容器或 SSH 里执行脚本相近：命令以当前运行环境的用户身份运行，可能修改文件或安装依赖。只安装信任来源的插件。

## 导入失败时的排查

| 提示 | 常见原因 |
| --- | --- |
| 压缩包根目录里没有 plugin.json | 打包时多套了一层目录 |
| plugin.json 缺少 / 无效字段 | 提示里会写出具体路径 |
| 此版本不支持的字段 / 内容类型 | 用了目前未实现的能力，如 `providers`、`permissionReviewer` |
| hooks 声明了 handler 却没有运行时 | 带 handler 的贡献都需要 `runtime`，在清单里补上 `dist/main.js` 入口 |
| `uiEntries` 的 `slot` 无效 | 只能使用本页列出的 13 个槽位，并符合 PAGE／ACTION／DATA 类型约束 |
| `uiEntries` 的 `page` 不是 HTML | `page` 必须指向包内的 `.html` 或 `.htm` 文件 |
| `uiEntries` 单槽位入口超过 2 个 | 同一槽位最多 2 个入口，超出拒绝安装 |
| 内容无效 | 技能或子代理正文为空、子代理写了 `provider` / `model`、AI 工具缺少运行时入口或 schema 不是 `type: object`、MCP 文件没有有效 server |
| 清单引用的文件不存在 | `file` 路径与压缩包里的实际路径不符，注意大小写 |
