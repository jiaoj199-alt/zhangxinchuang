# 公开版 v0.3.7.9

版本号：`0.3.7.9` / `30709`。

## 小金库双向审批闭环修复

- 明确补全陪伴者发起申请入口：`submit_companion_wallet_request`。
- 明确补全陪伴者查看用户处理结果入口：`list_companion_wallet_requests`。
- 新增 `save_user_wallet_request_result`：用户在聊天里明确同意后，陪伴者可把用户的通过 / 暂缓 / 驳回和理由写回小金库。
- `save_wallet_request_result`、`update_wallet_request_result` 继续保留，用于更中性的处理结果写回，降低平台误拦概率。
- 修复申请参数兼容：标题支持 `item/title/purpose/content/name/note/reason`，金额支持 `amount/amount_yuan/price/cost/estimated_amount/total`。
- 金额不会再在普通记账场景静默变成 0；普通账单缺金额会返回 `amount_required`。
- 无金额申请仍可明确传 `amount: 0`，并显示为“这笔申请”，不再默认写成“这笔消费”。

## 小金库账单交互

- 最近消费卡片支持左滑展开操作。
- 左滑后可编辑金额、分类、商家和备注。
- 左滑后可删除账单，删除前会二次确认。

## 用户处理陪伴者申请

- 用户处理“陪伴者的申请”时，点击通过 / 暂缓 / 驳回后会弹出理由输入框。
- 用户填写的理由会进入审批记录，陪伴者可通过 MCP 查看处理结果和备注。

