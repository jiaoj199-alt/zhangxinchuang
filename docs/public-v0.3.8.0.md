# 公开版 v0.3.8.6

版本号：`0.3.8.6` / `30806`。

## 本版重点

1. 新增专注模式 Focus Mode：支持全机专注、MCP 开启/结束/查询、目标与守护文案由 AI 填写。
2. 专注页保留“留言给他”：用户在锁定页可以留一句话；AI 之后通过 `get_focus_status` 可看到留言。
3. 应急放行保持简单：默认每次专注 1 次、每次 1 分钟，时间到会自动回到专注页。
4. 修复 `rename_diary_book`：允许只传 `new_name`，当手机端只有一本日记本时自动重命名；也支持 `old_name + new_name` 自动匹配。
5. 强化 MCP 工具暴露：`/health` 明确返回 `focus_tools: true`、`diary_rename_fix: true`，普通 `/mcp` 提前注册专注工具，避免用户反馈“后端有但工具面板没有”。

## 必须能看到的专注工具

- `get_focus_status`
- `start_focus_mode`
- `end_focus_mode`
- `set_focus_plan`
- `reply_focus_request`
- `approve_focus_unlock`
- `deny_focus_unlock`

部署后请检查后端 `/health`，再检查 MCP 客户端工具列表。
