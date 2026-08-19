from pathlib import Path

path = Path("GouzhuApp/app/src/main/java/com/gouzhu/payment/PaymentManager.java")
text = path.read_text(encoding="utf-8")


def one(label: str, old: str, new: str) -> None:
    global text
    count = text.count(old)
    print(f"{label}: matches={count}")
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    text = text.replace(old, new)


one(
    "request identity",
    """        if (!requestNo.equals(getCurrentOrderId()) || result == null) {
            return;
        }

        String purchaseStatus = normalize(result.getPurchaseStatus());
""",
    """        if (!requestNo.equals(getCurrentOrderId()) || result == null) {
            return;
        }
        String resultRequestNo = safe(result.getClientRequestNo());
        if (!resultRequestNo.isEmpty() && !requestNo.equals(resultRequestNo)) {
            // SDK 响应必须属于当前持久化请求；身份错配时禁止用错误订单推进本机状态。
            cancelPurchaseQuery();
            setStage(STAGE_BLOCKED);
            occupancy.markBlocked("PAYMENT_RESPONSE_REQUEST_MISMATCH");
            broadcast(
                    EVENT_FAILED,
                    "支付响应与当前订单不一致，设备已停止新交易",
                    requestNo,
                    null,
                    "REQUEST_MISMATCH"
            );
            return;
        }

        String purchaseStatus = normalize(result.getPurchaseStatus());
""",
)

one(
    "explicit failed raw state",
    """        String paymentStatus = normalize(result.getPaymentStatus());
        String selectedMode = normalize(result.getSelectedPaymentMode());
        if (selectedMode.isEmpty()) {
            selectedMode = getCurrentSelectedPaymentMode();
        }
        String payChannel = normalize(result.getPayChannel());
        if (payChannel.isEmpty()) {
            payChannel = getCurrentPayChannel();
        }
""",
    """        String paymentStatus = normalize(result.getPaymentStatus());
        String rawSelectedMode = normalize(result.getSelectedPaymentMode());
        String rawPayChannel = normalize(result.getPayChannel());
        String selectedMode = rawSelectedMode;
        String payChannel = rawPayChannel;
        if (!"FAILED".equals(paymentStatus)) {
            // 普通部分响应可沿用上一快照；明确 FAILED 必须接受服务端“已清空支付入口”的最新状态。
            if (selectedMode.isEmpty()) {
                selectedMode = getCurrentSelectedPaymentMode();
            }
            if (payChannel.isEmpty()) {
                payChannel = getCurrentPayChannel();
            }
        }
""",
)

one(
    "failed rearm persistence",
    """        if (explicitAttemptFailed
                && isAuthCodeSubmitted()
                && !preferences().edit().putBoolean(KEY_AUTH_CODE_SUBMITTED, false).commit()) {
            setStage(STAGE_BLOCKED);
            occupancy.markBlocked("PAYMENT_REARM_STATE_PERSISTENCE_FAILED");
            broadcast(
                    EVENT_FAILED,
                    "支付重试状态无法可靠保存，设备已停止本次交易",
                    requestNo,
                    null,
                    purchaseStatus
            );
            return;
        }

        // ORDER_CLOSED 可以先于 purchaseStatus=CLOSED 返回，必须权威关闭本地统一会话。
        if (paymentSaysClosed) {
            finishClosedByPaymentStatus(requestNo, message, purchaseStatus);
            return;
        }
""",
    """        if (explicitAttemptFailed
                && !preferences().edit()
                .putBoolean(KEY_AUTH_CODE_SUBMITTED, false)
                .putLong(KEY_QUERY_DEADLINE, 0L)
                .commit()) {
            setStage(STAGE_BLOCKED);
            occupancy.markBlocked("PAYMENT_REARM_STATE_PERSISTENCE_FAILED");
            broadcast(
                    EVENT_FAILED,
                    "支付重试状态无法可靠保存，设备已停止本次交易",
                    requestNo,
                    null,
                    purchaseStatus
            );
            return;
        }
""",
)

one(
    "closed ordering",
    """        // 未识别的 terminal=true 必须 fail-closed，禁止设备开启下一笔购买。
        if (terminal) {
""",
    """        /*
         * ORDER_CLOSED 只有在 purchaseStatus 没有给出更强的出珠/退款/已完成语义时才结束
         * 会话。取消与支付竞态中，DISPENSING/COMPLETED 必须保持支付胜出结果。
         */
        if (paymentSaysClosed) {
            finishClosedByPaymentStatus(requestNo, message, purchaseStatus);
            return;
        }

        // 未识别的 terminal=true 必须 fail-closed，禁止设备开启下一笔购买。
        if (terminal) {
""",
)

one(
    "no purchase scanner routing",
    """            if (requestNo.isEmpty()) {
                return ScanSubmission.handled(
                        false,
                        "请先选择购珠套餐，再出示微信或支付宝付款码",
                        channel
                );
            }
""",
    """            if (requestNo.isEmpty()) {
                // 没有购珠会话时保持原扫码业务路由；仅活动购珠订单消费付款码格式数据。
                return ScanSubmission.notHandled();
            }
""",
)

path.write_text(text, encoding="utf-8")
print("PATCH_OK")
