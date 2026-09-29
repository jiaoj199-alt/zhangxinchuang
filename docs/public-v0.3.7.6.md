# 公开版 v0.3.7.9

版本号：`0.3.7.9` / `30709`。

本版补全小金库双向申请：

- 新增 MCP 工具 `submit_companion_wallet_request`，陪伴者可向用户提交申请。
- 新增 MCP 工具 `list_companion_wallet_requests`，陪伴者可查看自己提交的申请和用户处理结果。
- 新增 MCP 工具 `list_wallet_request_results`，可按发起方 `user / companion / all` 和状态 `waiting / handled / all` 筛选申请记录。
- App 审批详情页对“陪伴者的申请”显示处理按钮：通过、暂缓、驳回。
- 继续保留 `save_wallet_request_result`，避免部分平台误拦旧的审批写回工具。

本版不需要新增数据库表。
