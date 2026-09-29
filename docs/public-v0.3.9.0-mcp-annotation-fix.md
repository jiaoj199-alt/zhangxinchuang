# v0.3.9.0 MCP 兼容与日记批注读取修复

本补丁在 `linjian-peek-public-v0.3.9.0-media-state-fix` 基础上修复两个反馈问题：

## 1. AI 平台连接 MCP 时 `ovoActivityCards` 报 Invalid input

部分 AI 平台在 MCP `initialize` 阶段会发送非标准实验能力字段：

```json
params.capabilities.experimental.ovoActivityCards
```

旧版服务端/SDK 校验会因为不认识该字段而拒绝初始化。本补丁在 `/mcp` 与 `/mcp-wallet` 入口增加初始化请求清洗，仅移除已知不兼容字段 `ovoActivityCards`，标准 capability 不受影响。

## 2. 日记批注读取工具返回 noop

Render server 的命令白名单遗漏了日记批注相关 action，导致 MCP 下发：

- `read_diary_entry_with_annotations`
- `add_diary_annotation`
- `list_diary_annotations`
- `mark_diary_annotations_seen`
- `delete_diary_annotation`

时会在 server 端被兜底改写为 `noop`，手机端实际收不到正确 action。本补丁将这些 action 加入 `server/linjian_server.py` 白名单，并在 `/health` 中标记 `diary_annotation_whitelist_fix: true`。

## 部署提示

- 需要重新部署 MCP 服务，以修复 `ovoActivityCards` 连接兼容问题。
- 需要重新部署 server 服务，以修复日记批注读取返回 noop。
- 手机 App 端 v0.3.9.0 已有批注 handler，本次主要修 server / mcp 两侧。
