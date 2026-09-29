# 掌心窗 Cloudflare 轻量后端

这是私人版掌心窗的 Cloudflare Workers 后端兼容层，目标是保留现有 Render 后端，同时新增一个更抗免费额度消耗的 Cloudflare 后端。

## v0.4.3.3 当前调用绑定修复

`send_sticker` 现在按本次查询得到的 `sticker_id` 生成 `/api/stickers/image?sticker_id=...&v=<timestamp>`，并通过 `structuredContent.display_image_url` 交给组件。组件只监听当前 `ui/notifications/tool-result`，不再自行访问 `latest_image` 或 `latest_display`，因此不会因全局最新记录或缓存竞争误显示上一张表情包。资源 URI 已更新为 `ui://linjian/sticker-card-v0433.html`。

## v0.4.3.2 App 空白与网页 Runtime error 修复

本版使用新的 MCP Apps 标准 `_meta.ui.resourceUri`，将表情包组件改成零 JavaScript 的单图片页面，并使用新资源 URI 清除 ChatGPT 对旧组件的缓存。工具返回顺序调整为图片优先，调试状态移入 `_meta`；最终回复只需输出 `structuredContent.markdown_image`，不再向用户汇报内部字段。Markdown 使用 `/api/stickers/image?sticker_id=...` 固定图片代理，避免客户端拦截 Supabase 热链。

## v0.4.3.1 Android App 表情包直显

`send_sticker` 现在同时提供三种展示方式：MCP 图片本体、安全表情包卡片、Markdown/图片地址兜底。`include_image` 默认值为 `true`，无需修改 Android APK 或 Supabase；重新部署 Worker 后，在 ChatGPT Android App 中刷新或重连掌心窗 MCP 即可。

重新部署：

```bash
cd server/cloudflare-worker
npx wrangler deploy --config wrangler.toml
```

支持核心接口：
- `/mcp`（Cloudflare Remote MCP，一体化工具入口，GPT 插件地址可填 `https://你的-worker.workers.dev/mcp`）
- `/health`
- `/api/command`、`/api/poll`、`/api/command/status`
- `/api/device/state`、`/api/life_state`、`/api/device/report`
- `/api/latest.json`、`/api/latest`、`/api/screenshot`（截图使用 KV 保存最近一张）
- `/api/companion/state`、`/api/companion/whisper`、`/api/companion/action`
- `/api/activity/events`
- `/api/appgate/unlock_request`、`/api/appgate/unlock_requests`

部署思路：
1. 创建 D1 数据库，执行 `schema.sql`。
2. 创建 KV namespace，绑定名保持 `SCREENSHOT_KV`。
3. 复制 `wrangler.example.toml` 为 `wrangler.toml`，填入 D1/KV 的 ID。
4. 执行 `wrangler secret put LINJIAN_TOKEN`，填和手机/MCP 一致的 Token。
5. `wrangler deploy`。
6. 在掌心窗设置页填写 Cloudflare 地址，模式选“自动”或“Cloudflare”。

注意：聆音/鲸鸣/声息这类重功能仍建议走 Render；Cloudflare 这版优先保证查状态、发指令、门禁、提醒、截图、守护日历远程命令这一条主线。


## v0.4.2.2 Cloudflare 一体 MCP

本版把 MCP 也合进 Cloudflare Worker。掌心窗 App 仍填写 Worker 根地址；GPT 插件/Remote MCP 填同一个 Worker 地址加 `/mcp`。认证支持 `Authorization: Bearer <LINJIAN_TOKEN>`、`X-Auth-Token` 或 `?token=`。

重新部署时不需要重建 D1/KV，也不需要重放 `wrangler secret put LINJIAN_TOKEN`；只要原来的 `wrangler.toml` 和 secret 还在 Cloudflare，执行 `npx wrangler deploy` 即可。

## v0.4.2.3 Cloudflare full-tools

本版把 Cloudflare `/mcp` 从 Lite 工具集补成私人版主力工具集，保留声息、聆音、鲸鸣不迁移：

- 新增归电设置：`set_guidian_config`、`mark_guidian_returned`。
- 新增门禁高级功能：紧急口令、临时放行、恢复申请读取、拒绝申请、可门禁 App 列表、添加门禁 App、设置/更新口令。
- 新增屏幕节点与精准控制：`get_screen_nodes`、`tap_text`、`input_text`。
- 新增小红书评论草稿/发送辅助：`draft_xhs_comment`、`xhs_comment`、`send_visible_comment_after_confirmation`。
- 新增主动关心与到访记录：`get_care_policy`、`set_care_policy`、`active_care_check`、`care_action`、`record_visit`、`get_visit_stats` 等，状态保存在 D1 的 `companion_state` 中。
- 新增连招/预设：`run_sequence`、`run_preset`、`save_known_app`。
- 新增轻量天气/感官读取：只读取手机最近上报状态，不在 Cloudflare 里生成声息/聆音/鲸鸣。

重新部署仍然只需要在 `cloudflare-worker` 目录执行：

```bash
npx wrangler deploy
```

D1 schema 不需要新增表；本版复用已有 `companion_state` 存放主动关心和到访记录。


## v0.4.2.4 表情包库与 MCP

新增 `list_stickers`、`search_stickers`、`get_sticker_detail`、`send_sticker` MCP 工具，以及 `/api/stickers/*` 普通接口。部署前请设置 `SUPABASE_URL` 与 `SUPABASE_SERVICE_ROLE_KEY` 两个 Worker Secret；bucket 默认 `stickers`。
