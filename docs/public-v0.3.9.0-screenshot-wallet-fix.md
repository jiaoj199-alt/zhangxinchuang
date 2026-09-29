# public v0.3.9.0 截图与小金库审批修复

本次修复两个反馈点：

1. 截图工具返回结构不稳定
   - Cloudflare MCP 的 `peek_screen` 现在会在手机上传截图后直接返回 `image` 内容，不再只返回“截图成功”的文字回执。
   - `latest_screen` / `peek_screen` 返回结构统一为 `content: [{ type: "text" }, { type: "image" }]`，并附带 `structuredContent` 元数据。
   - 避免外层处理时出现 `(x.content || []).filter is not a function`。

2. 小金库审批误判成功
   - `queued.ok: true` 现在只代表“命令已排队”，不会被描述成最终写入成功。
   - 小金库审批写回会等待手机端 `completed_at / phone_result`，未确认时返回“已发送到手机，等待手机确认”。
   - 返回中新增 `phone_confirmed`、`waiting_phone_confirmation`、`confirmation_state`、`display_status` 等字段，方便机判断。
   - Android 端在小金库命令执行后会发出 `WALLET_UPDATED` 广播；小金库页面收到后自动刷新，减少“已经处理但页面没刷新”的错觉。

