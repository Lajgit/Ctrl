from pathlib import Path


def replace_one(path: str, label: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    print(f"{label}: matches={count}")
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    p.write_text(text.replace(old, new), encoding="utf-8")


manager = "GouzhuApp/app/src/main/java/com/gouzhu/payment/PaymentManager.java"
replace_one(
    manager,
    "keep polling DISPENSING",
    """            case "DISPENSING":
                occupancy.onQrPurchaseStatus(requestNo, purchaseStatus);
                cancelPurchaseQuery();
                preferences().edit().putBoolean(KEY_CANCEL_PENDING, false).commit();
                setStage(STAGE_PAID);
                broadcast(
                        EVENT_SUCCESS,
                        message.isEmpty() ? "支付成功，等待平台出珠指令" : message,
                        requestNo,
                        null,
                        purchaseStatus
                );
                return;
""",
    """            case "DISPENSING":
                occupancy.onQrPurchaseStatus(requestNo, purchaseStatus);
                preferences().edit().putBoolean(KEY_CANCEL_PENDING, false).commit();
                setStage(STAGE_PAID);
                broadcast(
                        EVENT_SUCCESS,
                        message.isEmpty() ? "支付成功，等待平台出珠指令" : message,
                        requestNo,
                        null,
                        purchaseStatus
                );
                // DISPENSING 仍是服务端非终态；继续查原订单，直到物理完成且服务端收敛终态。
                schedulePurchaseQuery(requestNo);
                return;
""",
)
replace_one(
    manager,
    "payment code without purchase remains sensitive",
    """            if (requestNo.isEmpty()) {
                // 没有购珠会话时保持原扫码业务路由；仅活动购珠订单消费付款码格式数据。
                return ScanSubmission.notHandled();
            }
""",
    """            if (requestNo.isEmpty()) {
                // 已识别为付款码的数据即使没有购珠订单也不进入核销/日志路径，避免支付凭证外泄。
                return ScanSubmission.handled(
                        false,
                        "请先选择购珠套餐，再出示微信或支付宝付款码",
                        channel
                );
            }
""",
)

policy = "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyPolicy.java"
replace_one(
    policy,
    "physical phase regression policy",
    """    public static boolean isCancellationSuccess(String purchaseStatus) {
        String status = normalize(purchaseStatus);
        return "CANCELED".equals(status) || "CLOSED".equals(status);
    }

    public static String normalize(String value) {
""",
    """    public static boolean isCancellationSuccess(String purchaseStatus) {
        String status = normalize(purchaseStatus);
        return "CANCELED".equals(status) || "CLOSED".equals(status);
    }

    /**
     * MQTT 物理授权可能先于 HTTP 查单到达。已进入物理阶段后，迟到的普通非终态
     * WAITING_PAYMENT / EXPIRED / DISPENSING / 未识别状态都不能把占用阶段回退。
     * 终态和 REFUNDING 由上层显式处理，不在这里静默忽略。
     */
    public static boolean shouldPreservePhysicalPhase(
            String currentPhase,
            String purchaseStatus
    ) {
        if (!isPhysicalPhase(currentPhase)) {
            return false;
        }
        String status = normalize(purchaseStatus);
        return !("CANCELED".equals(status)
                || "CLOSED".equals(status)
                || "COMPLETED".equals(status)
                || "REFUNDING".equals(status)
                || "REFUNDED".equals(status));
    }

    public static String normalize(String value) {
""",
)

occupancy = "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyManager.java"
replace_one(
    occupancy,
    "do not regress physical occupancy",
    """        if ("REFUNDED".equals(normalized)) {
            if (store.hasActivePhysicalOrder()) {
                transitionAnyPhase(
                        snapshot.sessionId,
                        PHASE_BLOCKED,
                        "PAYMENT_REFUNDED_WITH_ACTIVE_DISPENSE"
                );
            } else {
                release(snapshot.sessionId, "qr refunded", true);
            }
            return;
        }
        String next = TransactionOccupancyPolicy.paymentPhase(normalized);
""",
    """        if ("REFUNDED".equals(normalized)) {
            if (store.hasActivePhysicalOrder()) {
                transitionAnyPhase(
                        snapshot.sessionId,
                        PHASE_BLOCKED,
                        "PAYMENT_REFUNDED_WITH_ACTIVE_DISPENSE"
                );
            } else {
                release(snapshot.sessionId, "qr refunded", true);
            }
            return;
        }
        if ("REFUNDING".equals(normalized)
                && TransactionOccupancyPolicy.isPhysicalPhase(snapshot.phase)) {
            // 物理出珠已经开始后再进入退款属于高风险冲突，保持占用并转人工处理。
            transitionAnyPhase(
                    snapshot.sessionId,
                    PHASE_BLOCKED,
                    "PAYMENT_REFUNDING_WITH_ACTIVE_DISPENSE"
            );
            return;
        }
        if (TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                snapshot.phase,
                normalized
        )) {
            // 忽略迟到的 HTTP 非终态，绝不把 DISPENSING/FINISHING 等物理阶段向后回退。
            return;
        }
        String next = TransactionOccupancyPolicy.paymentPhase(normalized);
""",
)
replace_one(
    occupancy,
    "server terminal gates QR release after physical finish",
    """            case "finished":
                if (!OWNER_MEMBER_DEPOSIT.equals(snapshot.ownerType)) {
                    release(snapshot.sessionId, "dispense completed", false);
                }
                break;
""",
    """            case "finished":
                if (OWNER_QR_PURCHASE.equals(snapshot.ownerType)) {
                    String purchaseStatus = PaymentManager.get(context).getCurrentPurchaseStatus();
                    if ("COMPLETED".equals(purchaseStatus)) {
                        // 统一购珠只有服务端 COMPLETED 后才允许释放并生成下一笔 clientRequestNo。
                        release(snapshot.sessionId, "qr dispense completed and server terminal", false);
                    } else if (!PHASE_BLOCKED.equals(snapshot.phase)) {
                        // 控制板完成只代表物理动作结束，继续保持订单占用等待服务端终态。
                        transitionAnyPhase(snapshot.sessionId, PHASE_FINISHING, "");
                    }
                } else if (!OWNER_MEMBER_DEPOSIT.equals(snapshot.ownerType)) {
                    release(snapshot.sessionId, "dispense completed", false);
                }
                break;
""",
)

test = "GouzhuApp/app/src/test/java/com/gouzhu/transaction/TransactionOccupancyPolicyTest.java"
replace_one(
    test,
    "physical regression tests",
    """    @Test
    public void paymentStatusMapsToExpectedPhase() {
""",
    """    @Test
    public void lateHttpStatusDoesNotRegressPhysicalPhase() {
        assertTrue(TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                "DISPENSING", "WAITING_PAYMENT"));
        assertTrue(TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                "FINISHING", "DISPENSING"));
        assertTrue(TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                "DISPENSE_RESERVED", "EXPIRED"));
        assertFalse(TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                "WAITING_PAYMENT", "WAITING_PAYMENT"));
        assertFalse(TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                "DISPENSING", "COMPLETED"));
        assertFalse(TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                "DISPENSING", "REFUNDING"));
    }

    @Test
    public void paymentStatusMapsToExpectedPhase() {
""",
)

print("PATCH_OK")
