# 公开版 v0.3.7.9

版本号：`0.3.7.9` / `30709`。

## 修复重点

- 修复部分 AI/MCP 客户端只看到 `/health` 里的工具列表，但 ChatGPT 工具面板没有暴露新增小金库/外卖工具的问题。
- 普通 `/mcp` 里提前暴露 `wallet_takeout_action` 统一入口。即使单独工具没刷新出来，也可以通过 `action` 调用小金库、双向申请和外卖助手能力。
- 新增 `/mcp-wallet` 专用 MCP 端点，只暴露小金库和外卖助手相关工具，避免工具太多时客户端截断 schema。
- `/health` 新增 `mcp_wallet_endpoint`、`schema_exposure_fix`、`priority_tool`、`wallet_takeout_tools`，方便用户和开发者确认部署是否生效。

## 推荐测试

1. 重新部署 MCP 服务后访问 `/health`，确认看到：
   - `version: 0.3.7.9`
   - `mcp_wallet_endpoint: /mcp-wallet`
   - `priority_tool: wallet_takeout_action`
2. AI 客户端普通连接继续使用：`https://你的-mcp-域名/mcp`。
3. 如果普通连接仍没暴露新增工具，新增一个连接使用：`https://你的-mcp-域名/mcp-wallet`。
4. 测试工具：`submit_companion_wallet_request`、`list_companion_wallet_requests`、`save_user_wallet_request_result`、`get_takeout_state`、`prepare_takeout_checkout`。

APK 输出名：`Zhangxinchuang-public-v0.3.7.9.apk`。
