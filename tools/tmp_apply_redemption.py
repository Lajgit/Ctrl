from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    if text.count(old) != 1:
        raise RuntimeError(f"{path}: expected exactly one match, got {text.count(old)}")
    write(path, text.replace(old, new, 1))


def insert_before(path, marker, content):
    text = read(path)
    if text.count(marker) != 1:
        raise RuntimeError(f"{path}: marker count={text.count(marker)}")
    write(path, text.replace(marker, content + marker, 1))


# 版本
replace_once(
    "GouzhuApp/app/build.gradle",
    'def appVersionName = "2.3.19"\ndef appVersionCode = 37',
    'def appVersionName = "2.3.20"\ndef appVersionCode = 38'
)

# Manifest 新增统一核销页面
insert_before(
    "GouzhuApp/app/src/main/AndroidManifest.xml",
    '        <activity\n            android:name=".payment.PaymentQrActivity"',
    '        <activity\n'
    '            android:name=".redemption.RedemptionActivity"\n'
    '            android:exported="false"\n'
    '            android:launchMode="singleTop"\n'
    '            android:screenOrientation="portrait" />\n\n'
)

# SDK 强类型包装
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/sdk/DeviceSdkManager.java",
    'import com.pinball.xiaoda.device.sdk.client.DeviceAppPurchaseResult;\n',
    'import com.pinball.xiaoda.device.sdk.client.DeviceAppPurchaseResult;\n'
    'import com.pinball.xiaoda.device.sdk.client.DeviceAppRedemptionRouting;\n'
    'import com.pinball.xiaoda.device.sdk.client.DeviceAppThirdPartyRedemptionPrepareResult;\n'
    'import com.pinball.xiaoda.device.sdk.client.DeviceAppThirdPartyRedemptionResult;\n'
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/sdk/DeviceSdkManager.java",
    '''    public DeviceAppMemberWithdrawalResult queryMemberWithdrawal(String clientRequestNo) {\n        return newAppClient().queryMemberWithdrawal(clientRequestNo);\n    }\n\n''',
    '''    public DeviceAppMemberWithdrawalResult createMemberWithdrawalFromRoutedCode(\n            String clientRequestNo,\n            DeviceAppRedemptionRouting routing,\n            String scannedCode\n    ) {\n        return newAppClient().createMemberWithdrawalFromRoutedCode(\n                clientRequestNo,\n                routing,\n                scannedCode\n        );\n    }\n\n    public DeviceAppMemberWithdrawalResult queryMemberWithdrawal(String clientRequestNo) {\n        return newAppClient().queryMemberWithdrawal(clientRequestNo);\n    }\n\n    /** 抖音/美团团购核销统一使用 SDK 强类型接口，设备端不自行拼签名或第三方协议。 */\n    public DeviceAppThirdPartyRedemptionPrepareResult\n    prepareThirdPartyRedemptionForSelectedChannel(\n            String clientRequestNo,\n            DeviceAppRedemptionRouting routing,\n            String selectedChannelCode,\n            String scannedRawCode\n    ) {\n        return newAppClient().prepareThirdPartyRedemptionForSelectedChannel(\n                clientRequestNo,\n                routing,\n                selectedChannelCode,\n                scannedRawCode\n        );\n    }\n\n    public DeviceAppThirdPartyRedemptionResult confirmThirdPartyRedemption(\n            String clientRequestNo,\n            String certificateId\n    ) {\n        return newAppClient().confirmThirdPartyRedemption(clientRequestNo, certificateId);\n    }\n\n    public DeviceAppThirdPartyRedemptionResult queryThirdPartyRedemption(String clientRequestNo) {\n        return newAppClient().queryThirdPartyRedemption(clientRequestNo);\n    }\n\n'''
)

# MQTT 服务启动时恢复两种核销会话
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/mqtt/DeviceCommandManager.java",
    'import com.gouzhu.payment.PaymentManager;\n',
    'import com.gouzhu.payment.PaymentManager;\n'
    'import com.gouzhu.redemption.MemberWithdrawalManager;\n'
    'import com.gouzhu.redemption.ThirdPartyRedemptionManager;\n'
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/mqtt/DeviceCommandManager.java",
    '        PaymentManager.get(context).resumePendingPayment();\n',
    '        PaymentManager.get(context).resumePendingPayment();\n'
    '        MemberWithdrawalManager.get(context).resumePending();\n'
    '        ThirdPartyRedemptionManager.get(context).resumePending();\n'
)

# 全局交易占用允许会员取珠/第三方核销等待平台出珠指令
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyPolicy.java",
    '''        return isIdleOwner(owner)\n                || "QR_PURCHASE".equals(owner)\n                || "CASH_PURCHASE".equals(owner)\n                || "GENERIC_DISPENSE".equals(owner);''',
    '''        return isIdleOwner(owner)\n                || "QR_PURCHASE".equals(owner)\n                || "CASH_PURCHASE".equals(owner)\n                || "MEMBER_WITHDRAWAL".equals(owner)\n                || "THIRD_PARTY_REDEMPTION".equals(owner)\n                || "GENERIC_DISPENSE".equals(owner);'''
)
replace_once(
    "GouzhuApp/app/src/test/java/com/gouzhu/transaction/TransactionOccupancyPolicyTest.java",
    '''        assertTrue(TransactionOccupancyPolicy.canReserveDispense(\n                "CASH_PURCHASE",\n                "ACCEPTED"\n        ));\n        assertTrue(TransactionOccupancyPolicy.canReserveDispense("", ""));''',
    '''        assertTrue(TransactionOccupancyPolicy.canReserveDispense(\n                "CASH_PURCHASE",\n                "ACCEPTED"\n        ));\n        assertTrue(TransactionOccupancyPolicy.canReserveDispense(\n                "MEMBER_WITHDRAWAL",\n                "WAITING_DISPENSE"\n        ));\n        assertTrue(TransactionOccupancyPolicy.canReserveDispense(\n                "THIRD_PARTY_REDEMPTION",\n                "WAITING_DISPENSE"\n        ));\n        assertTrue(TransactionOccupancyPolicy.canReserveDispense("", ""));'''
)

# TransactionOccupancyManager：新增两类 owner、恢复/现金隔离、终态回调
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyManager.java",
    'import com.gouzhu.payment.PaymentManager;\n',
    'import com.gouzhu.payment.PaymentManager;\n'
    'import com.gouzhu.redemption.MemberWithdrawalManager;\n'
    'import com.gouzhu.redemption.ThirdPartyRedemptionManager;\n'
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyManager.java",
    '''    public static final String OWNER_MEMBER_DEPOSIT = "MEMBER_DEPOSIT";\n    public static final String OWNER_GENERIC_DISPENSE = "GENERIC_DISPENSE";''',
    '''    public static final String OWNER_MEMBER_DEPOSIT = "MEMBER_DEPOSIT";\n    public static final String OWNER_MEMBER_WITHDRAWAL = "MEMBER_WITHDRAWAL";\n    public static final String OWNER_THIRD_PARTY_REDEMPTION = "THIRD_PARTY_REDEMPTION";\n    public static final String OWNER_GENERIC_DISPENSE = "GENERIC_DISPENSE";'''
)

marker = '''    /**\n     * Disables both cash devices and waits for the controller's applied mask=0 report.\n     * The QR order must not be created unless this method succeeds and the session still owns the lock.\n     */\n    public boolean prepareQrCashIsolation(String sessionId) {'''
redemption_methods = '''    /** 会员取珠和团购核销使用独立 owner，避免与购珠、现金和存珠会话混用。 */\n    public AcquireResult tryAcquireRedemption(String ownerType, String clientRequestNo) {\n        if (!isRedemptionOwner(ownerType) || blank(clientRequestNo)) {\n            return AcquireResult.denied("redemption identity is invalid", current());\n        }\n        if (!canStartNewTransaction()) {\n            return AcquireResult.denied(\n                    "device hardware is not ready for a new transaction",\n                    current()\n            );\n        }\n        return tryAcquire(\n                ownerType,\n                clientRequestNo,\n                PHASE_PREPARING,\n                clientRequestNo,\n                "",\n                "",\n                ""\n        );\n    }\n\n    /** APP 重启只恢复原 requestNo，不替换其他业务 owner。 */\n    public AcquireResult recoverRedemption(String ownerType, String clientRequestNo) {\n        if (!isRedemptionOwner(ownerType) || blank(clientRequestNo)) {\n            return AcquireResult.denied("redemption identity is invalid", current());\n        }\n        Snapshot snapshot = current();\n        if (snapshot != null) {\n            if (ownerType.equals(snapshot.ownerType)\n                    && clientRequestNo.equals(snapshot.clientRequestNo)) {\n                return AcquireResult.acquired(snapshot, false);\n            }\n            return AcquireResult.denied("device is occupied", snapshot);\n        }\n        return tryAcquire(\n                ownerType,\n                clientRequestNo,\n                PHASE_PREPARING,\n                clientRequestNo,\n                "",\n                "",\n                ""\n        );\n    }\n\n    /** 核销扫码前先关闭纸钞机/硬币器并等待控制板确认 mask=0。 */\n    public boolean prepareRedemptionCashIsolation(String sessionId, String ownerType) {\n        Snapshot snapshot = current();\n        if (snapshot == null\n                || !sessionId.equals(snapshot.sessionId)\n                || !isRedemptionOwner(ownerType)\n                || !ownerType.equals(snapshot.ownerType)\n                || !(PHASE_PREPARING.equals(snapshot.phase)\n                || PHASE_READY.equals(snapshot.phase))) {\n            return false;\n        }\n\n        int configVersion = Math.max(1, store.getCashConfigVersion());\n        CashDisableWaiter waiter = new CashDisableWaiter(configVersion);\n        cashDisableWaiter = waiter;\n        try {\n            long packed = configVersion & 0x00FFFFFFL;\n            boolean sent = SerialManager.get(context).sendCommand(\n                    CMD_CASH_APPLY_V22,\n                    packed,\n                    true\n            );\n            if (!sent) {\n                return false;\n            }\n            if (!waiter.latch.await(CASH_DISABLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)\n                    || !waiter.matched) {\n                return false;\n            }\n            Snapshot after = current();\n            if (after == null\n                    || !sessionId.equals(after.sessionId)\n                    || !ownerType.equals(after.ownerType)) {\n                return false;\n            }\n            if (PHASE_READY.equals(after.phase)) {\n                return true;\n            }\n            return transition(\n                    sessionId,\n                    PHASE_PREPARING,\n                    PHASE_READY,\n                    null,\n                    null,\n                    null,\n                    null,\n                    ""\n            );\n        } catch (InterruptedException error) {\n            Thread.currentThread().interrupt();\n            return false;\n        } finally {\n            if (cashDisableWaiter == waiter) {\n                cashDisableWaiter = null;\n            }\n        }\n    }\n\n    public boolean transitionRedemption(String clientRequestNo, String phase) {\n        Snapshot snapshot = current();\n        if (snapshot == null\n                || !isRedemptionOwner(snapshot.ownerType)\n                || !safe(clientRequestNo).equals(snapshot.clientRequestNo)) {\n            return false;\n        }\n        return transitionAnyPhase(snapshot.sessionId, phase, "");\n    }\n\n    public boolean isRedemptionOwned(String ownerType, String clientRequestNo) {\n        Snapshot snapshot = current();\n        return snapshot != null\n                && ownerType.equals(snapshot.ownerType)\n                && safe(clientRequestNo).equals(snapshot.clientRequestNo);\n    }\n\n'''
insert_before(
    "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyManager.java",
    marker,
    redemption_methods
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyManager.java",
    '''        if (OWNER_QR_PURCHASE.equals(released.ownerType)) {\n            PaymentManager.get(context).onOccupancyReleased(released.clientRequestNo);\n        }''',
    '''        if (OWNER_QR_PURCHASE.equals(released.ownerType)) {\n            PaymentManager.get(context).onOccupancyReleased(released.clientRequestNo);\n        } else if (OWNER_MEMBER_WITHDRAWAL.equals(released.ownerType)) {\n            MemberWithdrawalManager.get(context).onOccupancyReleased(released.clientRequestNo);\n        } else if (OWNER_THIRD_PARTY_REDEMPTION.equals(released.ownerType)) {\n            ThirdPartyRedemptionManager.get(context).onOccupancyReleased(released.clientRequestNo);\n        }'''
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyManager.java",
    '''        if (OWNER_MEMBER_DEPOSIT.equals(snapshot.ownerType)) {\n            return PHASE_COLLECTING.equals(snapshot.phase)\n                    ? "正在存珠"\n                    : "存珠会话已占用设备";\n        }\n        return "设备正在执行出珠任务";''',
    '''        if (OWNER_MEMBER_DEPOSIT.equals(snapshot.ownerType)) {\n            return PHASE_COLLECTING.equals(snapshot.phase)\n                    ? "正在存珠"\n                    : "存珠会话已占用设备";\n        }\n        if (OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType)) {\n            return TransactionOccupancyPolicy.isPhysicalPhase(snapshot.phase)\n                    ? "会员取珠正在出珠"\n                    : "会员取珠处理中";\n        }\n        if (OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType)) {\n            return TransactionOccupancyPolicy.isPhysicalPhase(snapshot.phase)\n                    ? "团购核销正在出珠"\n                    : "团购核销处理中";\n        }\n        return "设备正在执行出珠任务";'''
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyManager.java",
    '''                } else if (!OWNER_MEMBER_DEPOSIT.equals(snapshot.ownerType)) {\n                    release(snapshot.sessionId, "dispense completed", false);\n                }\n                break;''',
    '''                } else if (OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType)) {\n                    // 物理完成不等于第三方核销业务终态，继续等待 status 收敛。\n                    transitionAnyPhase(snapshot.sessionId, PHASE_FINISHING, "");\n                    ThirdPartyRedemptionManager.get(context).onPhysicalDispenseFinished();\n                } else if (OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType)) {\n                    transitionAnyPhase(snapshot.sessionId, PHASE_FINISHING, "");\n                    MemberWithdrawalManager.get(context).onPhysicalDispenseFinished();\n                } else if (!OWNER_MEMBER_DEPOSIT.equals(snapshot.ownerType)) {\n                    release(snapshot.sessionId, "dispense completed", false);\n                }\n                break;'''
)
insert_before(
    "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyManager.java",
    '    private static String newSessionId() {',
    '''    private static boolean isRedemptionOwner(String ownerType) {\n        return OWNER_MEMBER_WITHDRAWAL.equals(ownerType)\n                || OWNER_THIRD_PARTY_REDEMPTION.equals(ownerType);\n    }\n\n'''
)

# 反扫：显式选择的团购/会员模式优先，不再自动猜第三方渠道或 routed third-party
scanner_path = "GouzhuApp/app/src/main/java/com/gouzhu/scanner/ReverseScannerManager.java"
scanner = read(scanner_path)
scanner = scanner.replace(
    'import com.gouzhu.payment.PaymentManager;\n',
    'import com.gouzhu.payment.PaymentManager;\n'
    'import com.gouzhu.redemption.MemberWithdrawalManager;\n'
    'import com.gouzhu.redemption.ThirdPartyRedemptionManager;\n',
    1
)
start = scanner.index('        /*\n         * 付款码在统一购珠订单中优先消费。')
end = scanner.index('    /** 线路空闲短帧只做低频 Debug 统计', start)
new_route = '''        /*\n         * 团购核销和会员取珠都要求用户先在屏幕明确选择入口。处于显式扫码态时，\n         * 扫码原文只交给对应业务，禁止再根据前缀、长度或 URL 自动猜渠道。\n         */\n        ThirdPartyRedemptionManager thirdParty = ThirdPartyRedemptionManager.get(context);\n        if (thirdParty.isWaitingForScan()) {\n            if (thirdParty.handleScannerInput(content)) {\n                broadcast(\n                        EVENT_SCAN_ACCEPTED,\n                        "已接收团购券二维码，正在验券",\n                        TYPE_THIRD_PARTY_REDEMPTION,\n                        ""\n                );\n                return;\n            }\n        }\n\n        MemberWithdrawalManager member = MemberWithdrawalManager.get(context);\n        if (member.isWaitingForScan()) {\n            if (member.handleScannerInput(content)) {\n                broadcast(\n                        EVENT_SCAN_ACCEPTED,\n                        "已接收会员取珠二维码，正在确认",\n                        TYPE_MEMBER_WITHDRAWAL,\n                        ""\n                );\n                return;\n            }\n        }\n\n        // 未进入核销模式时，仍允许统一购珠订单识别微信/支付宝付款码。\n        PaymentManager.ScanSubmission paymentSubmission =\n                PaymentManager.get(context).handleAuthCodeScan(content);\n        if (paymentSubmission.handled) {\n            broadcast(\n                    paymentSubmission.accepted\n                            ? EVENT_SCAN_ACCEPTED : EVENT_SCAN_UNSUPPORTED,\n                    paymentSubmission.message,\n                    TYPE_PAYMENT_AUTH_CODE,\n                    ""\n            );\n            return;\n        }\n\n        String maskedCode = maskCode(content);\n        broadcast(\n                EVENT_SCAN_UNSUPPORTED,\n                "请先在屏幕选择会员取珠或团购核销；长度=" + content.length()\n                        + "，尾号=" + maskedCode,\n                TYPE_UNSUPPORTED,\n                maskedCode\n        );\n    }\n\n'''
scanner = scanner[:start] + new_route + scanner[end:]
write(scanner_path, scanner)
old_router = ROOT / "GouzhuApp/app/src/main/java/com/gouzhu/scanner/ScannerBusinessRouter.java"
if old_router.exists():
    old_router.unlink()

# MainActivity：参考主屏风格增加两个底部服务卡片，只保留会员取珠/团购核销顾客入口
main_path = "GouzhuApp/app/src/main/java/com/gouzhu/MainActivity.java"
replace_once(
    main_path,
    'import com.gouzhu.payment.QrCodeUtil;\n',
    'import com.gouzhu.payment.QrCodeUtil;\n'
    'import com.gouzhu.redemption.RedemptionActivity;\n'
    'import com.gouzhu.redemption.RedemptionCapabilityResolver;\n'
)
replace_once(
    main_path,
    '''    private ImageView paymentQrImage;\n    private Button[] packageButtons;\n\n    private LinearLayout collectionLayout;''',
    '''    private ImageView paymentQrImage;\n    private Button[] packageButtons;\n\n    private View memberWithdrawEntry;\n    private View thirdPartyRedemptionEntry;\n    private TextView memberWithdrawHint;\n    private TextView thirdPartyRedemptionHint;\n    private boolean memberWithdrawVisible;\n    private boolean memberWithdrawAvailable;\n    private boolean thirdPartyVisible;\n    private boolean thirdPartyAvailable;\n\n    private LinearLayout collectionLayout;'''
)
replace_once(
    main_path,
    '''        paymentQrImage = findViewById(R.id.image_payment_qr);\n\n        packageButtons = new Button[]{''',
    '''        paymentQrImage = findViewById(R.id.image_payment_qr);\n        memberWithdrawEntry = findViewById(R.id.card_member_withdraw);\n        thirdPartyRedemptionEntry = findViewById(R.id.card_third_party_redemption);\n        memberWithdrawHint = findViewById(R.id.text_member_withdraw_hint);\n        thirdPartyRedemptionHint = findViewById(R.id.text_third_party_redemption_hint);\n\n        packageButtons = new Button[]{'''
)
replace_once(
    main_path,
    '''        findViewById(R.id.button_backend_settings).setOnClickListener(\n                view -> openBackendSettings()\n        );''',
    '''        findViewById(R.id.button_backend_settings).setOnClickListener(\n                view -> openBackendSettings()\n        );\n        memberWithdrawEntry.setOnClickListener(view ->\n                openRedemption(RedemptionActivity.MODE_MEMBER)\n        );\n        thirdPartyRedemptionEntry.setOnClickListener(view ->\n                openRedemption(RedemptionActivity.MODE_THIRD_PARTY)\n        );'''
)
replace_once(
    main_path,
    '''            public void onFailure(Throwable error) {\n                bootstrapLoading = false;\n                disablePackages();''',
    '''            public void onFailure(Throwable error) {\n                bootstrapLoading = false;\n                disablePackages();\n                disableRedemptionEntries();'''
)
replace_once(
    main_path,
    '''        if (bootstrap == null) {\n            disablePackages();''',
    '''        if (bootstrap == null) {\n            disablePackages();\n            disableRedemptionEntries();'''
)
replace_once(
    main_path,
    '''        packageOptions.clear();\n        List<DeviceAppBootstrapResult.PurchaseRule> rules = bootstrap.getPurchaseRules();''',
    '''        applyRedemptionCapabilities(bootstrap);\n\n        packageOptions.clear();\n        List<DeviceAppBootstrapResult.PurchaseRule> rules = bootstrap.getPurchaseRules();'''
)
insert_before(
    main_path,
    '    private void appendRuleOptions(DeviceAppBootstrapResult.PurchaseRule rule) {',
    '''    private void applyRedemptionCapabilities(DeviceAppBootstrapResult bootstrap) {\n        RedemptionCapabilityResolver.FeatureGate member =\n                RedemptionCapabilityResolver.memberWithdrawal(bootstrap);\n        RedemptionCapabilityResolver.FeatureGate third =\n                RedemptionCapabilityResolver.thirdPartyRedemption(bootstrap);\n\n        memberWithdrawVisible = member.visible;\n        memberWithdrawAvailable = member.visible && member.available;\n        thirdPartyVisible = third.visible;\n        thirdPartyAvailable = third.visible && third.available;\n\n        memberWithdrawEntry.setVisibility(member.visible ? View.VISIBLE : View.GONE);\n        thirdPartyRedemptionEntry.setVisibility(third.visible ? View.VISIBLE : View.GONE);\n        memberWithdrawHint.setText(member.available\n                ? firstNonBlank(member.description, getString(R.string.member_withdraw_entry_hint))\n                : firstNonBlank(member.unavailableReason, getString(R.string.redemption_unavailable)));\n        thirdPartyRedemptionHint.setText(third.available\n                ? firstNonBlank(third.description, getString(R.string.third_party_redemption_entry_hint))\n                : firstNonBlank(third.unavailableReason, getString(R.string.redemption_unavailable)));\n    }\n\n    private void disableRedemptionEntries() {\n        memberWithdrawVisible = false;\n        memberWithdrawAvailable = false;\n        thirdPartyVisible = false;\n        thirdPartyAvailable = false;\n        memberWithdrawEntry.setEnabled(false);\n        thirdPartyRedemptionEntry.setEnabled(false);\n        memberWithdrawEntry.setAlpha(0.5f);\n        thirdPartyRedemptionEntry.setAlpha(0.5f);\n    }\n\n    private void openRedemption(String mode) {\n        TransactionOccupancyManager.Snapshot snapshot =\n                TransactionOccupancyManager.get(this).current();\n        boolean resumingMember = snapshot != null\n                && TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType)\n                && RedemptionActivity.MODE_MEMBER.equals(mode);\n        boolean resumingThird = snapshot != null\n                && TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType)\n                && RedemptionActivity.MODE_THIRD_PARTY.equals(mode);\n        if (!resumingMember && !resumingThird\n                && !TransactionOccupancyManager.get(this).canStartNewTransaction()) {\n            Toast.makeText(this, R.string.transaction_device_busy, Toast.LENGTH_SHORT).show();\n            return;\n        }\n        Intent intent = new Intent(this, RedemptionActivity.class);\n        intent.putExtra(RedemptionActivity.EXTRA_MODE, mode);\n        startActivity(intent);\n    }\n\n'''
)
replace_once(
    main_path,
    '''        for (int index = 0; index < packageButtons.length; index++) {\n            packageButtons[index].setEnabled(available && index < packageOptions.size());\n        }\n        paymentButton.setEnabled(available && selectedOption != null);''',
    '''        for (int index = 0; index < packageButtons.length; index++) {\n            packageButtons[index].setEnabled(available && index < packageOptions.size());\n        }\n        paymentButton.setEnabled(available && selectedOption != null);\n\n        boolean memberOwned = snapshot != null\n                && TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType);\n        boolean thirdOwned = snapshot != null\n                && TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType);\n        boolean memberEnabled = memberWithdrawVisible\n                && ((available && memberWithdrawAvailable) || memberOwned);\n        boolean thirdEnabled = thirdPartyVisible\n                && ((available && thirdPartyAvailable) || thirdOwned);\n        memberWithdrawEntry.setEnabled(memberEnabled);\n        thirdPartyRedemptionEntry.setEnabled(thirdEnabled);\n        memberWithdrawEntry.setAlpha(memberEnabled ? 1f : 0.5f);\n        thirdPartyRedemptionEntry.setAlpha(thirdEnabled ? 1f : 0.5f);'''
)
replace_once(
    main_path,
    '''        } else {\n            collectionStartButton.setEnabled(false);\n            collectionFinishButton.setEnabled(false);\n        }\n    }''',
    '''        } else {\n            collectionStartButton.setEnabled(false);\n            collectionFinishButton.setEnabled(false);\n            if (TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL.equals(owner)\n                    || TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION.equals(owner)) {\n                paymentStatusText.setText(manager.displayMessage(snapshot));\n            }\n        }\n    }'''
)
insert_before(
    main_path,
    '    private static boolean notBlank(String value) {',
    '''    private String firstNonBlank(String... values) {\n        if (values != null) {\n            for (String value : values) {\n                if (value != null && !value.trim().isEmpty()) {\n                    return value.trim();\n                }\n            }\n        }\n        return "";\n    }\n\n'''
)

# 主界面：两张浅蓝服务卡；后台设置不再作为顾客入口；版本号更新
layout_path = "GouzhuApp/app/src/main/res/layout/activity_main.xml"
layout = read(layout_path)
collection_marker = '''            <LinearLayout\n                android:id="@+id/layout_collection"'''
service_section = '''            <TextView\n                android:layout_width="match_parent"\n                android:layout_height="wrap_content"\n                android:layout_marginTop="30dp"\n                android:text="@string/home_redemption_section_title"\n                android:textColor="@color/text_primary"\n                android:textSize="23sp"\n                android:textStyle="bold" />\n\n            <!-- 参考主屏套餐卡片风格，顾客服务区只保留会员取珠和团购核销。 -->\n            <LinearLayout\n                android:layout_width="match_parent"\n                android:layout_height="wrap_content"\n                android:layout_marginTop="14dp"\n                android:orientation="horizontal">\n\n                <LinearLayout\n                    android:id="@+id/card_member_withdraw"\n                    android:layout_width="0dp"\n                    android:layout_height="112dp"\n                    android:layout_margin="8dp"\n                    android:layout_weight="1"\n                    android:background="@drawable/bg_package_button"\n                    android:clickable="true"\n                    android:focusable="true"\n                    android:gravity="center"\n                    android:orientation="vertical"\n                    android:padding="14dp">\n\n                    <TextView\n                        android:layout_width="wrap_content"\n                        android:layout_height="wrap_content"\n                        android:text="@string/member_withdraw_entry"\n                        android:textColor="@color/text_primary"\n                        android:textSize="22sp"\n                        android:textStyle="bold" />\n\n                    <TextView\n                        android:id="@+id/text_member_withdraw_hint"\n                        android:layout_width="match_parent"\n                        android:layout_height="wrap_content"\n                        android:layout_marginTop="7dp"\n                        android:gravity="center"\n                        android:maxLines="2"\n                        android:text="@string/member_withdraw_entry_hint"\n                        android:textColor="@color/text_secondary"\n                        android:textSize="14sp" />\n                </LinearLayout>\n\n                <LinearLayout\n                    android:id="@+id/card_third_party_redemption"\n                    android:layout_width="0dp"\n                    android:layout_height="112dp"\n                    android:layout_margin="8dp"\n                    android:layout_weight="1"\n                    android:background="@drawable/bg_package_button"\n                    android:clickable="true"\n                    android:focusable="true"\n                    android:gravity="center"\n                    android:orientation="vertical"\n                    android:padding="14dp">\n\n                    <TextView\n                        android:layout_width="wrap_content"\n                        android:layout_height="wrap_content"\n                        android:text="@string/third_party_redemption_entry"\n                        android:textColor="@color/text_primary"\n                        android:textSize="22sp"\n                        android:textStyle="bold" />\n\n                    <TextView\n                        android:id="@+id/text_third_party_redemption_hint"\n                        android:layout_width="match_parent"\n                        android:layout_height="wrap_content"\n                        android:layout_marginTop="7dp"\n                        android:gravity="center"\n                        android:maxLines="2"\n                        android:text="@string/third_party_redemption_entry_hint"\n                        android:textColor="@color/text_secondary"\n                        android:textSize="14sp" />\n                </LinearLayout>\n            </LinearLayout>\n\n'''
if layout.count(collection_marker) != 1:
    raise RuntimeError("activity_main collection marker mismatch")
layout = layout.replace(collection_marker, service_section + collection_marker, 1)
layout = layout.replace('android:text="@string/backend_debug_entry" />',
                        'android:text="@string/backend_debug_entry"\n                android:visibility="gone" />', 1)
layout = layout.replace('android:text="V2.3.19"', 'android:text="V2.3.20"', 1)
write(layout_path, layout)

print("redemption integration patch applied")
