#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel, text):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)

# 1. 版本号
rel = "GouzhuApp/app/build.gradle"
text = read(rel)
text = replace_once(text, 'def appVersionName = "2.3.21"', 'def appVersionName = "2.3.22"', "versionName")
text = replace_once(text, 'def appVersionCode = 39', 'def appVersionCode = 40', "versionCode")
write(rel, text)

# 2. SDK 增加强类型 routed-code 官方券码接口
rel = "GouzhuApp/app/src/main/java/com/gouzhu/sdk/DeviceSdkManager.java"
text = read(rel)
old = '''    public DeviceAppInternalRedemptionResult createInternalRedemption(
            String clientRequestNo,
            String pickupCode
    ) {
        return newAppClient().createInternalRedemption(clientRequestNo, pickupCode);
    }

    public DeviceAppInternalRedemptionResult queryInternalRedemption(String clientRequestNo) {
'''
new = '''    public DeviceAppInternalRedemptionResult createInternalRedemption(
            String clientRequestNo,
            String pickupCode
    ) {
        return newAppClient().createInternalRedemption(clientRequestNo, pickupCode);
    }

    /** 官方小程序套餐券使用 bootstrap.redemptionRouting 的路由规则，设备端不写死券码前缀。 */
    public DeviceAppInternalRedemptionResult createInternalRedemptionFromRoutedCode(
            String clientRequestNo,
            DeviceAppRedemptionRouting routing,
            String scannedCode
    ) {
        return newAppClient().createInternalRedemptionFromRoutedCode(
                clientRequestNo,
                routing,
                scannedCode
        );
    }

    public DeviceAppInternalRedemptionResult queryInternalRedemption(String clientRequestNo) {
'''
text = replace_once(text, old, new, "DeviceSdkManager internal routed wrapper")
write(rel, text)

# 3. capability 解析恢复 INTERNAL_REDEMPTION
rel = "GouzhuApp/app/src/main/java/com/gouzhu/redemption/RedemptionCapabilityResolver.java"
text = read(rel)
text = replace_once(
    text,
    '    public static final String FEATURE_MEMBER_WITHDRAW = "MEMBER_WITHDRAW";\n    public static final String FEATURE_THIRD_PARTY_REDEMPTION = "THIRD_PARTY_REDEMPTION";',
    '    public static final String FEATURE_MEMBER_WITHDRAW = "MEMBER_WITHDRAW";\n'
    '    public static final String FEATURE_INTERNAL_REDEMPTION = "INTERNAL_REDEMPTION";\n'
    '    public static final String FEATURE_THIRD_PARTY_REDEMPTION = "THIRD_PARTY_REDEMPTION";',
    "capability constant"
)
old = '''    public static FeatureGate memberWithdrawal(DeviceAppBootstrapResult bootstrap) {
        return feature(bootstrap, FEATURE_MEMBER_WITHDRAW);
    }

    public static FeatureGate thirdPartyRedemption(DeviceAppBootstrapResult bootstrap) {
'''
new = '''    public static FeatureGate memberWithdrawal(DeviceAppBootstrapResult bootstrap) {
        return feature(bootstrap, FEATURE_MEMBER_WITHDRAW);
    }

    /** 官方小程序套餐券核销必须同时具备 feature 和 internalRedemption 路由。 */
    public static FeatureGate internalRedemption(DeviceAppBootstrapResult bootstrap) {
        FeatureGate gate = feature(bootstrap, FEATURE_INTERNAL_REDEMPTION);
        if (!gate.visible || !gate.available) {
            return gate;
        }
        if (bootstrap == null
                || bootstrap.getRedemptionRouting() == null
                || bootstrap.getRedemptionRouting().getInternalRedemption() == null) {
            return new FeatureGate(
                    true,
                    false,
                    gate.title,
                    gate.description,
                    "券码核销路由尚未加载"
            );
        }
        return gate;
    }

    public static FeatureGate thirdPartyRedemption(DeviceAppBootstrapResult bootstrap) {
'''
text = replace_once(text, old, new, "capability internal method")
write(rel, text)

# 4. 交易策略：官方券码只有提交后才允许 MQTT 出珠
rel = "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyPolicy.java"
text = read(rel)
old = '''        if ("MEMBER_WITHDRAWAL".equals(owner)
                || "THIRD_PARTY_REDEMPTION".equals(owner)) {
            // prepare/扫码阶段绝不接受出珠；请求已提交后先切 WAITING_DISPENSE，
            // 允许平台 MQTT 比对应 HTTP 响应更早到达。
'''
new = '''        if ("MEMBER_WITHDRAWAL".equals(owner)
                || "INTERNAL_REDEMPTION".equals(owner)
                || "THIRD_PARTY_REDEMPTION".equals(owner)) {
            // prepare/扫码阶段绝不接受出珠；请求已提交后先切 WAITING_DISPENSE，
            // 允许平台 MQTT 比对应 HTTP 响应更早到达。
'''
text = replace_once(text, old, new, "transaction redemption policy")
write(rel, text)

# 5. 全局交易占用新增 INTERNAL_REDEMPTION owner
rel = "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyManager.java"
text = read(rel)
text = replace_once(
    text,
    'import com.gouzhu.payment.PaymentManager;\nimport com.gouzhu.redemption.MemberWithdrawalManager;\nimport com.gouzhu.redemption.ThirdPartyRedemptionManager;',
    'import com.gouzhu.payment.PaymentManager;\n'
    'import com.gouzhu.redemption.InternalRedemptionManager;\n'
    'import com.gouzhu.redemption.MemberWithdrawalManager;\n'
    'import com.gouzhu.redemption.ThirdPartyRedemptionManager;',
    "occupancy import"
)
text = replace_once(
    text,
    '    public static final String OWNER_MEMBER_WITHDRAWAL = "MEMBER_WITHDRAWAL";\n    public static final String OWNER_THIRD_PARTY_REDEMPTION = "THIRD_PARTY_REDEMPTION";',
    '    public static final String OWNER_MEMBER_WITHDRAWAL = "MEMBER_WITHDRAWAL";\n'
    '    public static final String OWNER_INTERNAL_REDEMPTION = "INTERNAL_REDEMPTION";\n'
    '    public static final String OWNER_THIRD_PARTY_REDEMPTION = "THIRD_PARTY_REDEMPTION";',
    "occupancy owner constant"
)
text = replace_once(
    text,
    '''        } else if (OWNER_MEMBER_WITHDRAWAL.equals(released.ownerType)) {
            MemberWithdrawalManager.get(context).onOccupancyReleased(released.clientRequestNo);
        } else if (OWNER_THIRD_PARTY_REDEMPTION.equals(released.ownerType)) {
''',
    '''        } else if (OWNER_MEMBER_WITHDRAWAL.equals(released.ownerType)) {
            MemberWithdrawalManager.get(context).onOccupancyReleased(released.clientRequestNo);
        } else if (OWNER_INTERNAL_REDEMPTION.equals(released.ownerType)) {
            InternalRedemptionManager.get(context).onOccupancyReleased(released.clientRequestNo);
        } else if (OWNER_THIRD_PARTY_REDEMPTION.equals(released.ownerType)) {
''',
    "occupancy release callback"
)
text = replace_once(
    text,
    '''        if (OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType)) {
            return TransactionOccupancyPolicy.isPhysicalPhase(snapshot.phase)
                    ? "会员取珠正在出珠"
                    : "会员取珠处理中";
        }
        if (OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType)) {
''',
    '''        if (OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType)) {
            return TransactionOccupancyPolicy.isPhysicalPhase(snapshot.phase)
                    ? "会员取珠正在出珠"
                    : "会员取珠处理中";
        }
        if (OWNER_INTERNAL_REDEMPTION.equals(snapshot.ownerType)) {
            return TransactionOccupancyPolicy.isPhysicalPhase(snapshot.phase)
                    ? "券码核销正在出珠"
                    : "券码核销处理中";
        }
        if (OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType)) {
''',
    "occupancy display message"
)
text = replace_once(
    text,
    '''                } else if (OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType)) {
                    transitionAnyPhase(snapshot.sessionId, PHASE_FINISHING, "");
                    MemberWithdrawalManager.get(context).onPhysicalDispenseFinished();
                } else if (!OWNER_MEMBER_DEPOSIT.equals(snapshot.ownerType)) {
''',
    '''                } else if (OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType)) {
                    transitionAnyPhase(snapshot.sessionId, PHASE_FINISHING, "");
                    MemberWithdrawalManager.get(context).onPhysicalDispenseFinished();
                } else if (OWNER_INTERNAL_REDEMPTION.equals(snapshot.ownerType)) {
                    // 官方券码 HTTP 核销状态和本地物理完成必须分别收敛，不能仅凭控制板完成释放交易。
                    transitionAnyPhase(snapshot.sessionId, PHASE_FINISHING, "");
                    InternalRedemptionManager.get(context).onPhysicalDispenseFinished();
                } else if (!OWNER_MEMBER_DEPOSIT.equals(snapshot.ownerType)) {
''',
    "occupancy physical finished callback"
)
text = replace_once(
    text,
    '''    private static boolean isRedemptionOwner(String ownerType) {
        return OWNER_MEMBER_WITHDRAWAL.equals(ownerType)
                || OWNER_THIRD_PARTY_REDEMPTION.equals(ownerType);
    }
''',
    '''    private static boolean isRedemptionOwner(String ownerType) {
        return OWNER_MEMBER_WITHDRAWAL.equals(ownerType)
                || OWNER_INTERNAL_REDEMPTION.equals(ownerType)
                || OWNER_THIRD_PARTY_REDEMPTION.equals(ownerType);
    }
''',
    "occupancy redemption owners"
)
write(rel, text)

# 6. 反扫显式券码核销入口
rel = "GouzhuApp/app/src/main/java/com/gouzhu/scanner/ReverseScannerManager.java"
text = read(rel)
text = replace_once(
    text,
    'import com.gouzhu.payment.PaymentManager;\nimport com.gouzhu.redemption.MemberWithdrawalManager;\nimport com.gouzhu.redemption.ThirdPartyRedemptionManager;',
    'import com.gouzhu.payment.PaymentManager;\n'
    'import com.gouzhu.redemption.InternalRedemptionManager;\n'
    'import com.gouzhu.redemption.MemberWithdrawalManager;\n'
    'import com.gouzhu.redemption.ThirdPartyRedemptionManager;',
    "scanner import"
)
old = '''        MemberWithdrawalManager member = MemberWithdrawalManager.get(context);
        if (member.isWaitingForScan()) {
            if (member.handleScannerInput(rawContent)) {
                broadcast(
                        EVENT_SCAN_ACCEPTED,
                        "已接收会员取珠二维码，正在确认",
                        TYPE_MEMBER_WITHDRAWAL,
                        ""
                );
                return;
            }
        }

        // 未进入核销模式时，仍允许统一购珠订单识别微信/支付宝付款码。
'''
new = '''        MemberWithdrawalManager member = MemberWithdrawalManager.get(context);
        if (member.isWaitingForScan()) {
            if (member.handleScannerInput(rawContent)) {
                broadcast(
                        EVENT_SCAN_ACCEPTED,
                        "已接收会员取珠二维码，正在确认",
                        TYPE_MEMBER_WITHDRAWAL,
                        ""
                );
                return;
            }
        }

        InternalRedemptionManager internal = InternalRedemptionManager.get(context);
        if (internal.isWaitingForScan()) {
            if (internal.handleScannerInput(rawContent)) {
                broadcast(
                        EVENT_SCAN_ACCEPTED,
                        "已接收官方套餐核销二维码，正在确认",
                        TYPE_INTERNAL_REDEMPTION,
                        ""
                );
                return;
            }
        }

        // 未进入核销模式时，仍允许统一购珠订单识别微信/支付宝付款码。
'''
text = replace_once(text, old, new, "scanner internal route")
text = replace_once(
    text,
    '"请先在屏幕选择会员取珠或团购核销；长度=" + content.length()',
    '"请先在屏幕选择会员取珠、券码核销或团购核销；长度=" + content.length()',
    "scanner unsupported message"
)
write(rel, text)

# 7. 首页恢复券码核销入口
rel = "GouzhuApp/app/src/main/java/com/gouzhu/MainActivity.java"
text = read(rel)
text = replace_once(
    text,
    'import com.gouzhu.redemption.RedemptionActivity;\nimport com.gouzhu.redemption.RedemptionCapabilityResolver;',
    'import com.gouzhu.redemption.InternalRedemptionActivity;\n'
    'import com.gouzhu.redemption.InternalRedemptionManager;\n'
    'import com.gouzhu.redemption.RedemptionActivity;\n'
    'import com.gouzhu.redemption.RedemptionCapabilityResolver;',
    "main imports"
)
text = replace_once(
    text,
    '''    private View memberWithdrawEntry;
    private View thirdPartyRedemptionEntry;
    private TextView memberWithdrawHint;
    private TextView thirdPartyRedemptionHint;
    private boolean memberWithdrawVisible;
    private boolean memberWithdrawAvailable;
    private boolean thirdPartyVisible;
    private boolean thirdPartyAvailable;
''',
    '''    private View memberWithdrawEntry;
    private View internalRedemptionEntry;
    private View thirdPartyRedemptionEntry;
    private TextView memberWithdrawHint;
    private TextView internalRedemptionHint;
    private TextView thirdPartyRedemptionHint;
    private boolean memberWithdrawVisible;
    private boolean memberWithdrawAvailable;
    private boolean internalRedemptionVisible;
    private boolean internalRedemptionAvailable;
    private boolean thirdPartyVisible;
    private boolean thirdPartyAvailable;
''',
    "main redemption fields"
)
text = replace_once(
    text,
    '''        loadBootstrap(false);
        PaymentManager.get(this).resumePendingPayment();
        if (DeviceCommandManager.get(this).hasPendingCollection()) {
''',
    '''        loadBootstrap(false);
        PaymentManager.get(this).resumePendingPayment();
        // 官方券码请求一旦提交，进程重建后只查询原 requestNo，不重新提交券码。
        InternalRedemptionManager.get(this).resumePending();
        if (DeviceCommandManager.get(this).hasPendingCollection()) {
''',
    "main internal resume"
)
text = replace_once(
    text,
    '''        memberWithdrawEntry = findViewById(R.id.card_member_withdraw);
        thirdPartyRedemptionEntry = findViewById(R.id.card_third_party_redemption);
        memberWithdrawHint = findViewById(R.id.text_member_withdraw_hint);
        thirdPartyRedemptionHint = findViewById(R.id.text_third_party_redemption_hint);
''',
    '''        memberWithdrawEntry = findViewById(R.id.card_member_withdraw);
        internalRedemptionEntry = findViewById(R.id.card_internal_redemption);
        thirdPartyRedemptionEntry = findViewById(R.id.card_third_party_redemption);
        memberWithdrawHint = findViewById(R.id.text_member_withdraw_hint);
        internalRedemptionHint = findViewById(R.id.text_internal_redemption_hint);
        thirdPartyRedemptionHint = findViewById(R.id.text_third_party_redemption_hint);
''',
    "main bind internal views"
)
text = replace_once(
    text,
    '''        memberWithdrawEntry.setOnClickListener(view ->
                openRedemption(RedemptionActivity.MODE_MEMBER)
        );
        thirdPartyRedemptionEntry.setOnClickListener(view ->
                openRedemption(RedemptionActivity.MODE_THIRD_PARTY)
        );
''',
    '''        memberWithdrawEntry.setOnClickListener(view ->
                openRedemption(RedemptionActivity.MODE_MEMBER)
        );
        internalRedemptionEntry.setOnClickListener(view -> openInternalRedemption());
        thirdPartyRedemptionEntry.setOnClickListener(view ->
                openRedemption(RedemptionActivity.MODE_THIRD_PARTY)
        );
''',
    "main bind internal action"
)
old = '''        RedemptionCapabilityResolver.FeatureGate member =
                RedemptionCapabilityResolver.memberWithdrawal(bootstrap);
        RedemptionCapabilityResolver.FeatureGate third =
                RedemptionCapabilityResolver.thirdPartyRedemption(bootstrap);

        memberWithdrawVisible = member.visible;
        memberWithdrawAvailable = member.visible && member.available;
        thirdPartyVisible = third.visible;
        thirdPartyAvailable = third.visible && third.available;

        memberWithdrawEntry.setVisibility(member.visible ? View.VISIBLE : View.GONE);
        thirdPartyRedemptionEntry.setVisibility(third.visible ? View.VISIBLE : View.GONE);
        memberWithdrawEntry.setEnabled(memberWithdrawAvailable);
        thirdPartyRedemptionEntry.setEnabled(thirdPartyAvailable);
        memberWithdrawEntry.setAlpha(memberWithdrawAvailable ? 1f : 0.5f);
        thirdPartyRedemptionEntry.setAlpha(thirdPartyAvailable ? 1f : 0.5f);
        memberWithdrawHint.setText(member.available
                ? firstNonBlank(member.description, getString(R.string.member_withdraw_entry_hint))
                : firstNonBlank(member.unavailableReason, getString(R.string.redemption_unavailable)));
        thirdPartyRedemptionHint.setText(third.available
                ? firstNonBlank(third.description, getString(R.string.third_party_redemption_entry_hint))
                : firstNonBlank(third.unavailableReason, getString(R.string.redemption_unavailable)));
'''
new = '''        RedemptionCapabilityResolver.FeatureGate member =
                RedemptionCapabilityResolver.memberWithdrawal(bootstrap);
        RedemptionCapabilityResolver.FeatureGate internal =
                RedemptionCapabilityResolver.internalRedemption(bootstrap);
        RedemptionCapabilityResolver.FeatureGate third =
                RedemptionCapabilityResolver.thirdPartyRedemption(bootstrap);

        memberWithdrawVisible = member.visible;
        memberWithdrawAvailable = member.visible && member.available;
        internalRedemptionVisible = internal.visible;
        internalRedemptionAvailable = internal.visible && internal.available;
        thirdPartyVisible = third.visible;
        thirdPartyAvailable = third.visible && third.available;

        memberWithdrawEntry.setVisibility(member.visible ? View.VISIBLE : View.GONE);
        internalRedemptionEntry.setVisibility(internal.visible ? View.VISIBLE : View.GONE);
        thirdPartyRedemptionEntry.setVisibility(third.visible ? View.VISIBLE : View.GONE);
        memberWithdrawEntry.setEnabled(memberWithdrawAvailable);
        internalRedemptionEntry.setEnabled(internalRedemptionAvailable);
        thirdPartyRedemptionEntry.setEnabled(thirdPartyAvailable);
        memberWithdrawEntry.setAlpha(memberWithdrawAvailable ? 1f : 0.5f);
        internalRedemptionEntry.setAlpha(internalRedemptionAvailable ? 1f : 0.5f);
        thirdPartyRedemptionEntry.setAlpha(thirdPartyAvailable ? 1f : 0.5f);
        memberWithdrawHint.setText(member.available
                ? firstNonBlank(member.description, getString(R.string.member_withdraw_entry_hint))
                : firstNonBlank(member.unavailableReason, getString(R.string.redemption_unavailable)));
        internalRedemptionHint.setText(internal.available
                ? firstNonBlank(internal.description, getString(R.string.internal_redemption_entry_hint))
                : firstNonBlank(internal.unavailableReason, getString(R.string.redemption_unavailable)));
        thirdPartyRedemptionHint.setText(third.available
                ? firstNonBlank(third.description, getString(R.string.third_party_redemption_entry_hint))
                : firstNonBlank(third.unavailableReason, getString(R.string.redemption_unavailable)));
'''
text = replace_once(text, old, new, "main apply redemption capabilities")
text = replace_once(
    text,
    '''        memberWithdrawVisible = false;
        memberWithdrawAvailable = false;
        thirdPartyVisible = false;
        thirdPartyAvailable = false;
        memberWithdrawEntry.setEnabled(false);
        thirdPartyRedemptionEntry.setEnabled(false);
        memberWithdrawEntry.setAlpha(0.5f);
        thirdPartyRedemptionEntry.setAlpha(0.5f);
''',
    '''        memberWithdrawVisible = false;
        memberWithdrawAvailable = false;
        internalRedemptionVisible = false;
        internalRedemptionAvailable = false;
        thirdPartyVisible = false;
        thirdPartyAvailable = false;
        memberWithdrawEntry.setEnabled(false);
        internalRedemptionEntry.setEnabled(false);
        thirdPartyRedemptionEntry.setEnabled(false);
        memberWithdrawEntry.setAlpha(0.5f);
        internalRedemptionEntry.setAlpha(0.5f);
        thirdPartyRedemptionEntry.setAlpha(0.5f);
''',
    "main disable redemption entries"
)
anchor = '''    private void appendRuleOptions(DeviceAppBootstrapResult.PurchaseRule rule) {
'''
method = '''    private void openInternalRedemption() {
        TransactionOccupancyManager.Snapshot snapshot =
                TransactionOccupancyManager.get(this).current();
        boolean resuming = snapshot != null
                && TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION.equals(snapshot.ownerType);
        if (!resuming && !internalRedemptionAvailable) {
            Toast.makeText(this, R.string.redemption_start_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!resuming && !TransactionOccupancyManager.get(this).canStartNewTransaction()) {
            Toast.makeText(this, R.string.transaction_device_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, InternalRedemptionActivity.class));
    }

'''
text = replace_once(text, anchor, method + anchor, "main internal activity method")
old = '''        boolean memberOwned = snapshot != null
                && TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType);
        boolean thirdOwned = snapshot != null
                && TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType);
        boolean memberEnabled = memberWithdrawVisible
                && ((available && memberWithdrawAvailable) || memberOwned);
        boolean thirdEnabled = thirdPartyVisible
                && ((available && thirdPartyAvailable) || thirdOwned);
        memberWithdrawEntry.setEnabled(memberEnabled);
        thirdPartyRedemptionEntry.setEnabled(thirdEnabled);
        memberWithdrawEntry.setAlpha(memberEnabled ? 1f : 0.5f);
        thirdPartyRedemptionEntry.setAlpha(thirdEnabled ? 1f : 0.5f);
'''
new = '''        boolean memberOwned = snapshot != null
                && TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType);
        boolean internalOwned = snapshot != null
                && TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION.equals(snapshot.ownerType);
        boolean thirdOwned = snapshot != null
                && TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType);
        boolean memberEnabled = memberWithdrawVisible
                && ((available && memberWithdrawAvailable) || memberOwned);
        boolean internalEnabled = internalRedemptionVisible
                && ((available && internalRedemptionAvailable) || internalOwned);
        boolean thirdEnabled = thirdPartyVisible
                && ((available && thirdPartyAvailable) || thirdOwned);
        memberWithdrawEntry.setEnabled(memberEnabled);
        internalRedemptionEntry.setEnabled(internalEnabled);
        thirdPartyRedemptionEntry.setEnabled(thirdEnabled);
        memberWithdrawEntry.setAlpha(memberEnabled ? 1f : 0.5f);
        internalRedemptionEntry.setAlpha(internalEnabled ? 1f : 0.5f);
        thirdPartyRedemptionEntry.setAlpha(thirdEnabled ? 1f : 0.5f);
'''
text = replace_once(text, old, new, "main occupancy card state")
text = replace_once(
    text,
    '''            if (TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL.equals(owner)
                    || TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION.equals(owner)) {
''',
    '''            if (TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL.equals(owner)
                    || TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION.equals(owner)
                    || TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION.equals(owner)) {
''',
    "main occupancy status owner"
)
write(rel, text)

# 8. 首页 XML：三个服务入口并列 + 版本
rel = "GouzhuApp/app/src/main/res/layout/activity_main.xml"
text = read(rel)
text = replace_once(
    text,
    '<!-- 参考主屏套餐卡片风格，顾客服务区只保留会员取珠和团购核销。 -->',
    '<!-- 顾客服务区显式区分会员取珠、官方小程序券码核销和第三方团购核销。 -->',
    "main layout service comment"
)
third_card = '''                <LinearLayout
                    android:id="@+id/card_third_party_redemption"
'''
internal_card = '''                <LinearLayout
                    android:id="@+id/card_internal_redemption"
                    android:layout_width="0dp"
                    android:layout_height="112dp"
                    android:layout_margin="8dp"
                    android:layout_weight="1"
                    android:background="@drawable/bg_package_button"
                    android:clickable="true"
                    android:focusable="true"
                    android:gravity="center"
                    android:orientation="vertical"
                    android:padding="14dp">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/internal_redemption_entry"
                        android:textColor="@color/text_primary"
                        android:textSize="22sp"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/text_internal_redemption_hint"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="7dp"
                        android:gravity="center"
                        android:maxLines="2"
                        android:text="@string/internal_redemption_entry_hint"
                        android:textColor="@color/text_secondary"
                        android:textSize="14sp" />
                </LinearLayout>

'''
text = replace_once(text, third_card, internal_card + third_card, "main layout internal card")
text = replace_once(text, 'android:text="V2.3.20"', 'android:text="V2.3.22"', "main footer version")
write(rel, text)

# 9. 字符串
rel = "GouzhuApp/app/src/main/res/values/redemption_strings.xml"
text = read(rel)
text = replace_once(
    text,
    '''    <string name="member_withdraw_entry">会员取珠</string>
    <string name="member_withdraw_entry_hint">扫码取出会员账户中的弹珠</string>
    <string name="third_party_redemption_entry">团购核销</string>
''',
    '''    <string name="member_withdraw_entry">会员取珠</string>
    <string name="member_withdraw_entry_hint">扫码取出会员账户中的弹珠</string>
    <string name="internal_redemption_entry">券码核销</string>
    <string name="internal_redemption_entry_hint">官方小程序套餐券扫码出珠</string>
    <string name="third_party_redemption_entry">团购核销</string>
''',
    "redemption home strings"
)
insert = '''    <string name="internal_redemption_title">券码核销</string>
    <string name="internal_redemption_subtitle">扫描弹球小达官方小程序购买套餐的核销二维码</string>
    <string name="internal_redemption_scan_hint">请将官方套餐核销二维码对准扫码器</string>
    <string name="internal_redemption_preparing">正在准备券码核销，请稍候</string>
    <string name="internal_redemption_quantity_format">已出 %1$d / %2$d 珠</string>
    <string name="internal_redemption_manual_review">本次核销出珠结果异常，请联系工作人员处理</string>
    <string name="internal_redemption_logo_description">弹球小达官方券码核销</string>

'''
text = replace_once(
    text,
    '    <string name="third_party_redemption_title">团购核销</string>\n',
    insert + '    <string name="third_party_redemption_title">团购核销</string>\n',
    "internal strings"
)
write(rel, text)

# 10. Manifest 注册独立券码核销页面
rel = "GouzhuApp/app/src/main/AndroidManifest.xml"
text = read(rel)
old = '''        <activity
            android:name=".redemption.RedemptionActivity"
            android:exported="false"
            android:launchMode="singleTop"
            android:screenOrientation="portrait" />

        <activity
            android:name=".payment.PaymentQrActivity"
'''
new = '''        <activity
            android:name=".redemption.RedemptionActivity"
            android:exported="false"
            android:launchMode="singleTop"
            android:screenOrientation="portrait" />

        <activity
            android:name=".redemption.InternalRedemptionActivity"
            android:exported="false"
            android:launchMode="singleTop"
            android:screenOrientation="portrait" />

        <activity
            android:name=".payment.PaymentQrActivity"
'''
text = replace_once(text, old, new, "manifest internal activity")
write(rel, text)

# 11. TransactionOccupancyPolicyTest 增加官方券码门禁覆盖
rel = "GouzhuApp/app/src/test/java/com/gouzhu/transaction/TransactionOccupancyPolicyTest.java"
text = read(rel)
old = '''        assertFalse(TransactionOccupancyPolicy.canReserveDispense(
                "THIRD_PARTY_REDEMPTION",
                "READY"
        ));
        assertTrue(TransactionOccupancyPolicy.canReserveDispense(
                "MEMBER_WITHDRAWAL",
                "WAITING_DISPENSE"
        ));
'''
new = '''        assertFalse(TransactionOccupancyPolicy.canReserveDispense(
                "THIRD_PARTY_REDEMPTION",
                "READY"
        ));
        assertFalse(TransactionOccupancyPolicy.canReserveDispense(
                "INTERNAL_REDEMPTION",
                "READY"
        ));
        assertTrue(TransactionOccupancyPolicy.canReserveDispense(
                "MEMBER_WITHDRAWAL",
                "WAITING_DISPENSE"
        ));
'''
text = replace_once(text, old, new, "occupancy test internal ready")
old = '''        assertTrue(TransactionOccupancyPolicy.canReserveDispense(
                "THIRD_PARTY_REDEMPTION",
                "WAITING_DISPENSE"
        ));
        assertTrue(TransactionOccupancyPolicy.canReserveDispense("", ""));
'''
new = '''        assertTrue(TransactionOccupancyPolicy.canReserveDispense(
                "THIRD_PARTY_REDEMPTION",
                "WAITING_DISPENSE"
        ));
        assertTrue(TransactionOccupancyPolicy.canReserveDispense(
                "INTERNAL_REDEMPTION",
                "WAITING_DISPENSE"
        ));
        assertTrue(TransactionOccupancyPolicy.canReserveDispense("", ""));
'''
text = replace_once(text, old, new, "occupancy test internal waiting")
write(rel, text)

# 12. 新增纯 Java 结算策略
write("GouzhuApp/app/src/main/java/com/gouzhu/redemption/InternalRedemptionPolicy.java", r'''package com.gouzhu.redemption;

/**
 * 官方小程序套餐券的本地结算判定。
 *
 * <p>HTTP 终态只用于收敛业务状态，真实出珠仍必须来自 MQTT 物理授权。
 * 只要服务端终态显示已经出过部分珠但没有完整履约，就进入人工处理，禁止自动放行下一笔。</p>
 */
public final class InternalRedemptionPolicy {

    public static final String OUTCOME_PENDING = "PENDING";
    public static final String OUTCOME_SUCCEEDED = "SUCCEEDED";
    public static final String OUTCOME_FAILED = "FAILED";
    public static final String OUTCOME_MANUAL_REVIEW = "MANUAL_REVIEW";

    private InternalRedemptionPolicy() {
    }

    public static String terminalOutcome(
            boolean terminal,
            int requestedQuantity,
            int dispensedQuantity
    ) {
        if (!terminal) {
            return OUTCOME_PENDING;
        }
        int requested = Math.max(0, requestedQuantity);
        int dispensed = Math.max(0, dispensedQuantity);
        if (requested > 0 && dispensed >= requested) {
            return OUTCOME_SUCCEEDED;
        }
        if (dispensed > 0) {
            return OUTCOME_MANUAL_REVIEW;
        }
        return OUTCOME_FAILED;
    }
}
''')

# 13. 官方券码独立持久化，不保存扫码原文
write("GouzhuApp/app/src/main/java/com/gouzhu/redemption/InternalRedemptionStore.java", r'''package com.gouzhu.redemption;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 官方小程序套餐券核销会话。
 *
 * <p>扫码原文属于一次性业务凭据，绝不写入数据库；这里只保存 requestNo、服务端状态和
 * 出珠数量，确保进程重建后能够查询同一个请求，而不是再次消费同一张券。</p>
 */
public final class InternalRedemptionStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "gouzhu_internal_redemption_v1.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "internal_redemption_session";

    public InternalRedemptionStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY CHECK(id=1),"
                + "client_request_no TEXT NOT NULL UNIQUE,"
                + "operation_id INTEGER NOT NULL DEFAULT -1,"
                + "operation_no TEXT NOT NULL DEFAULT '',"
                + "requested_quantity INTEGER NOT NULL DEFAULT 0,"
                + "dispensed_quantity INTEGER NOT NULL DEFAULT 0,"
                + "operation_status INTEGER NOT NULL DEFAULT -1,"
                + "redemption_status TEXT NOT NULL DEFAULT '',"
                + "expire_time TEXT NOT NULL DEFAULT '',"
                + "ui_state TEXT NOT NULL,"
                + "message TEXT NOT NULL DEFAULT '',"
                + "terminal INTEGER NOT NULL DEFAULT 0,"
                + "submitted_at INTEGER NOT NULL DEFAULT 0,"
                + "last_status_checked_at INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 独立首版数据库，无破坏性升级逻辑。
    }

    public synchronized Session load() {
        try (Cursor cursor = getReadableDatabase().query(
                TABLE, null, "id=1", null, null, null, null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            Session value = new Session();
            value.clientRequestNo = text(cursor, "client_request_no");
            value.operationId = longValue(cursor, "operation_id", -1L);
            value.operationNo = text(cursor, "operation_no");
            value.requestedQuantity = intValue(cursor, "requested_quantity", 0);
            value.dispensedQuantity = intValue(cursor, "dispensed_quantity", 0);
            value.operationStatus = intValue(cursor, "operation_status", -1);
            value.redemptionStatus = text(cursor, "redemption_status");
            value.expireTime = text(cursor, "expire_time");
            value.uiState = text(cursor, "ui_state");
            value.message = text(cursor, "message");
            value.terminal = intValue(cursor, "terminal", 0) != 0;
            value.submittedAt = longValue(cursor, "submitted_at", 0L);
            value.lastStatusCheckedAt = longValue(cursor, "last_status_checked_at", 0L);
            value.createdAt = longValue(cursor, "created_at", 0L);
            value.updatedAt = longValue(cursor, "updated_at", 0L);
            return value;
        }
    }

    public synchronized boolean save(Session value) {
        if (value == null || blank(value.clientRequestNo) || blank(value.uiState)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (value.createdAt <= 0L) {
            value.createdAt = now;
        }
        value.updatedAt = now;

        ContentValues values = new ContentValues();
        values.put("id", 1);
        values.put("client_request_no", safe(value.clientRequestNo));
        values.put("operation_id", value.operationId);
        values.put("operation_no", safe(value.operationNo));
        values.put("requested_quantity", Math.max(0, value.requestedQuantity));
        values.put("dispensed_quantity", Math.max(0, value.dispensedQuantity));
        values.put("operation_status", value.operationStatus);
        values.put("redemption_status", safe(value.redemptionStatus));
        values.put("expire_time", safe(value.expireTime));
        values.put("ui_state", safe(value.uiState));
        values.put("message", safe(value.message));
        values.put("terminal", value.terminal ? 1 : 0);
        values.put("submitted_at", value.submittedAt);
        values.put("last_status_checked_at", value.lastStatusCheckedAt);
        values.put("created_at", value.createdAt);
        values.put("updated_at", value.updatedAt);
        return getWritableDatabase().insertWithOnConflict(
                TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L;
    }

    public synchronized void clear() {
        getWritableDatabase().delete(TABLE, "id=1", null);
    }

    private static String text(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
    }

    private static int intValue(Cursor cursor, String name, int fallback) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getInt(index);
    }

    private static long longValue(Cursor cursor, String name, long fallback) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getLong(index);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Session {
        public String clientRequestNo = "";
        public long operationId = -1L;
        public String operationNo = "";
        public int requestedQuantity;
        public int dispensedQuantity;
        public int operationStatus = -1;
        public String redemptionStatus = "";
        public String expireTime = "";
        public String uiState = "";
        public String message = "";
        public boolean terminal;
        public long submittedAt;
        public long lastStatusCheckedAt;
        public long createdAt;
        public long updatedAt;
    }
}
''')

# 14. 官方小程序券码业务状态机
write("GouzhuApp/app/src/main/java/com/gouzhu/redemption/InternalRedemptionManager.java", r'''package com.gouzhu.redemption;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.gouzhu.mqtt.DeviceCommandStore;
import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.pinball.xiaoda.device.sdk.client.DeviceApiException;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppInternalRedemptionResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppRedemptionRouting;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 官方小程序套餐券核销。
 *
 * <p>用户必须先点击“券码核销”进入显式扫码态。扫码原文只在本次 SDK 调用期间存在，
 * 不落库、不打印。HTTP 只创建/查询核销业务，真实出珠只执行平台签名后的 MQTT
 * dispense_marbles；一旦请求提交，超时或进程重建都只查询同一个 clientRequestNo。</p>
 */
public final class InternalRedemptionManager {

    public static final String ACTION_CHANGED = "com.gouzhu.action.INTERNAL_REDEMPTION_CHANGED";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_REQUEST_NO = "requestNo";

    public static final String STATE_STARTING = "STARTING";
    public static final String STATE_SCANNING = "SCANNING";
    public static final String STATE_SUBMITTING = "SUBMITTING";
    public static final String STATE_WAITING_DISPENSE = "WAITING_DISPENSE";
    public static final String STATE_WAITING_FINAL = "WAITING_FINAL";
    public static final String STATE_SUCCEEDED = "SUCCEEDED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_MANUAL_REVIEW = "MANUAL_REVIEW";

    private static final String TAG = "GouzhuInternalRedeem";
    private static final int MAX_CODE_LENGTH = 4096;

    private static volatile InternalRedemptionManager instance;

    private final Context context;
    private final DeviceSdkManager sdkManager;
    private final TransactionOccupancyManager occupancy;
    private final InternalRedemptionStore store;
    private final DeviceCommandStore commandStore;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> queryTask;

    private InternalRedemptionManager(Context context) {
        this.context = context.getApplicationContext();
        sdkManager = DeviceSdkManager.get(this.context);
        occupancy = TransactionOccupancyManager.get(this.context);
        store = new InternalRedemptionStore(this.context);
        commandStore = new DeviceCommandStore(this.context);
    }

    public static InternalRedemptionManager get(Context context) {
        if (instance == null) {
            synchronized (InternalRedemptionManager.class) {
                if (instance == null) {
                    instance = new InternalRedemptionManager(context);
                }
            }
        }
        return instance;
    }

    public synchronized boolean beginScan() {
        InternalRedemptionStore.Session existing = store.load();
        if (existing != null && !existing.clientRequestNo.isEmpty() && !existing.terminal) {
            broadcast(existing);
            return true;
        }

        DeviceAppBootstrapResult bootstrap = sdkManager.getLastBootstrap();
        RedemptionCapabilityResolver.FeatureGate gate =
                RedemptionCapabilityResolver.internalRedemption(bootstrap);
        if (!gate.visible || !gate.available) {
            broadcastMessage(
                    firstNonBlank(gate.unavailableReason, "券码核销当前不可用"),
                    STATE_FAILED,
                    ""
            );
            return false;
        }
        DeviceAppRedemptionRouting routing =
                bootstrap == null ? null : bootstrap.getRedemptionRouting();
        if (routing == null || routing.getInternalRedemption() == null) {
            broadcastMessage("券码核销扫码路由尚未加载", STATE_FAILED, "");
            return false;
        }
        if (!occupancy.canStartNewTransaction()) {
            broadcastMessage("设备正在处理其他交易，请稍后再试", STATE_FAILED, "");
            return false;
        }

        String requestNo = newRequestNo();
        TransactionOccupancyManager.AcquireResult acquired = occupancy.tryAcquireRedemption(
                TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION,
                requestNo
        );
        if (!acquired.success || acquired.snapshot == null) {
            broadcastMessage("设备正在处理其他交易，请稍后再试", STATE_FAILED, "");
            return false;
        }

        InternalRedemptionStore.Session session = new InternalRedemptionStore.Session();
        session.clientRequestNo = requestNo;
        session.uiState = STATE_STARTING;
        session.message = "正在准备券码核销";
        if (!store.save(session)) {
            occupancy.release(acquired.snapshot.sessionId, "internal redemption state save failed", true);
            return false;
        }
        broadcast(session);
        executor.execute(() -> prepareScannerSession(acquired.snapshot.sessionId, requestNo));
        return true;
    }

    private void prepareScannerSession(String sessionId, String requestNo) {
        boolean ready = occupancy.prepareRedemptionCashIsolation(
                sessionId,
                TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION
        );
        synchronized (this) {
            InternalRedemptionStore.Session session = store.load();
            if (session == null || !requestNo.equals(session.clientRequestNo)) {
                return;
            }
            if (!ready) {
                session.uiState = STATE_FAILED;
                session.terminal = true;
                session.message = "现金入口未确认关闭，券码核销未启动";
                store.save(session);
                occupancy.release(sessionId, "internal redemption cash isolation failed", true);
                broadcast(session);
                return;
            }
            session.uiState = STATE_SCANNING;
            session.message = "请扫描官方小程序套餐核销二维码";
            store.save(session);
            broadcast(session);
        }
    }

    public boolean isWaitingForScan() {
        InternalRedemptionStore.Session session = store.load();
        return session != null
                && STATE_SCANNING.equals(session.uiState)
                && occupancy.isRedemptionOwned(
                TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION,
                session.clientRequestNo
        );
    }

    public synchronized boolean handleScannerInput(String rawCode) {
        InternalRedemptionStore.Session session = store.load();
        if (session == null || !STATE_SCANNING.equals(session.uiState)) {
            return false;
        }
        String code = rawCode == null ? "" : rawCode;
        if (code.trim().isEmpty() || code.length() > MAX_CODE_LENGTH) {
            session.message = "官方套餐核销二维码格式无效，请重新扫码";
            store.save(session);
            broadcast(session);
            return true;
        }

        session.uiState = STATE_SUBMITTING;
        session.submittedAt = System.currentTimeMillis();
        session.message = "券码已识别，正在确认套餐核销资格";
        if (!store.save(session)) {
            occupancy.markBlocked("INTERNAL_REDEMPTION_STATE_SAVE_FAILED");
            return true;
        }

        /*
         * submittedAt 持久化后才开放 MQTT 出珠资格。这样即使 MQTT 比 HTTP 响应先到，
         * 也能接住合法物理授权；扫码/准备阶段仍然绝不允许出珠。
         */
        if (!occupancy.markRedemptionWaitingDispense(session.clientRequestNo)) {
            occupancy.markBlocked("INTERNAL_REDEMPTION_OCCUPANCY_FAILED");
            session.uiState = STATE_FAILED;
            session.message = "券码核销交易状态切换失败，请联系工作人员";
            store.save(session);
            broadcast(session);
            return true;
        }

        broadcast(session);
        char[] sensitiveCode = code.toCharArray();
        executor.execute(() -> submitOnce(session.clientRequestNo, sensitiveCode));
        return true;
    }

    private void submitOnce(String requestNo, char[] sensitiveCode) {
        String raw = null;
        try {
            InternalRedemptionStore.Session session = store.load();
            if (session == null || !requestNo.equals(session.clientRequestNo)) {
                return;
            }
            DeviceAppBootstrapResult bootstrap = sdkManager.getLastBootstrap();
            DeviceAppRedemptionRouting routing =
                    RedemptionCapabilityResolver.requireRouting(bootstrap);
            if (routing.getInternalRedemption() == null) {
                throw new IllegalStateException("券码核销路由已失效");
            }
            raw = new String(sensitiveCode);
            DeviceAppInternalRedemptionResult result =
                    sdkManager.createInternalRedemptionFromRoutedCode(
                            requestNo,
                            routing,
                            raw
                    );
            applyResult(requestNo, result);
        } catch (Throwable error) {
            DeviceApiException apiError = asApiError(error);
            synchronized (this) {
                InternalRedemptionStore.Session session = store.load();
                if (session == null || !requestNo.equals(session.clientRequestNo)) {
                    return;
                }
                if (apiError != null && apiError.getApiCode() != null) {
                    Log.w(TAG, "官方券码核销接口失败：requestNo=" + requestNo
                            + "，apiCode=" + apiError.getApiCode()
                            + "，traceId=" + safe(apiError.getTraceId()));
                    session.uiState = STATE_FAILED;
                    session.terminal = true;
                    session.message = firstNonBlank(apiError.getMessage(), "券码核销失败");
                    store.save(session);
                    broadcast(session);
                    releaseIfSettled(session);
                } else {
                    // 创建结果未知时不允许重新提交原券码，只查询相同 requestNo。
                    session.uiState = STATE_WAITING_FINAL;
                    session.message = "核销结果正在确认，请勿重复扫码";
                    store.save(session);
                    broadcast(session);
                    scheduleQuery(requestNo, 2_000L);
                }
            }
        } finally {
            clearSensitive(sensitiveCode);
            raw = null;
        }
    }

    private synchronized void applyResult(
            String requestNo,
            DeviceAppInternalRedemptionResult result
    ) {
        InternalRedemptionStore.Session session = store.load();
        if (session == null || !requestNo.equals(session.clientRequestNo) || result == null) {
            return;
        }
        if (!requestNo.equals(safe(result.getClientRequestNo()))) {
            occupancy.markBlocked("INTERNAL_REDEMPTION_RESPONSE_MISMATCH");
            session.uiState = STATE_MANUAL_REVIEW;
            session.message = "券码核销响应与当前请求不一致，请联系工作人员";
            store.save(session);
            broadcast(session);
            return;
        }

        session.operationId = result.getOperationId() == null
                ? session.operationId : result.getOperationId();
        session.operationNo = safe(result.getOperationNo());
        session.requestedQuantity = result.getRequestedQuantity() == null
                ? session.requestedQuantity : Math.max(0, result.getRequestedQuantity());
        session.dispensedQuantity = result.getDispensedQuantity() == null
                ? session.dispensedQuantity : Math.max(0, result.getDispensedQuantity());
        session.operationStatus = result.getOperationStatus() == null
                ? session.operationStatus : result.getOperationStatus();
        session.redemptionStatus = safe(result.getRedemptionStatus());
        session.expireTime = safe(result.getExpireTime());
        session.terminal = result.isTerminal();
        session.lastStatusCheckedAt = System.currentTimeMillis();
        session.message = firstNonBlank(
                result.getMessage(),
                result.getRedemptionStatus(),
                "券码核销处理中"
        );

        String outcome = InternalRedemptionPolicy.terminalOutcome(
                session.terminal,
                session.requestedQuantity,
                session.dispensedQuantity
        );
        if (InternalRedemptionPolicy.OUTCOME_SUCCEEDED.equals(outcome)) {
            session.uiState = STATE_SUCCEEDED;
        } else if (InternalRedemptionPolicy.OUTCOME_MANUAL_REVIEW.equals(outcome)) {
            session.uiState = STATE_MANUAL_REVIEW;
            session.message = "券码已核销但出珠数量未完整匹配，请联系工作人员";
            store.save(session);
            broadcast(session);
            occupancy.markBlocked("INTERNAL_REDEMPTION_PARTIAL_DELIVERY");
            cancelQuery();
            return;
        } else if (InternalRedemptionPolicy.OUTCOME_FAILED.equals(outcome)) {
            session.uiState = STATE_FAILED;
        } else {
            session.uiState = STATE_WAITING_DISPENSE;
            // HTTP 查询晚于 MQTT 时只确认资格，绝不把 DISPENSING/FINISHING 回退。
            occupancy.markRedemptionWaitingDispense(session.clientRequestNo);
        }

        store.save(session);
        broadcast(session);
        if (session.terminal) {
            cancelQuery();
            releaseIfSettled(session);
        } else {
            scheduleQuery(requestNo, 2_000L);
        }
    }

    private void query(String requestNo) {
        InternalRedemptionStore.Session session = store.load();
        if (session == null || !requestNo.equals(session.clientRequestNo) || session.terminal) {
            if (session != null) {
                releaseIfSettled(session);
            }
            return;
        }
        try {
            applyResult(requestNo, sdkManager.queryInternalRedemption(requestNo));
        } catch (Throwable error) {
            Log.w(TAG, "券码核销状态查询失败，将继续查询原请求：requestNo=" + requestNo
                    + "，error=" + error.getClass().getSimpleName());
            scheduleQuery(requestNo, 5_000L);
        }
    }

    private synchronized void scheduleQuery(String requestNo, long delayMs) {
        if (queryTask != null && !queryTask.isDone()) {
            return;
        }
        queryTask = executor.schedule(() -> {
            synchronized (InternalRedemptionManager.this) {
                queryTask = null;
            }
            query(requestNo);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelQuery() {
        if (queryTask != null) {
            queryTask.cancel(false);
            queryTask = null;
        }
    }

    public synchronized void resumePending() {
        InternalRedemptionStore.Session session = store.load();
        if (session == null || session.clientRequestNo.isEmpty()) {
            return;
        }

        // 终态不重新获得交易锁；页面只展示结果，人工异常由已有 BLOCKED 会话处理。
        if (session.terminal) {
            releaseIfSettled(session);
            broadcast(session);
            return;
        }

        TransactionOccupancyManager.AcquireResult recovered = occupancy.recoverRedemption(
                TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION,
                session.clientRequestNo
        );
        if (!recovered.success || recovered.snapshot == null) {
            return;
        }

        if (STATE_STARTING.equals(session.uiState)) {
            executor.execute(() -> prepareScannerSession(
                    recovered.snapshot.sessionId,
                    session.clientRequestNo
            ));
        } else if (STATE_SUBMITTING.equals(session.uiState)
                || STATE_WAITING_DISPENSE.equals(session.uiState)
                || STATE_WAITING_FINAL.equals(session.uiState)) {
            // 券码原文没有落库；一旦提交，重启后只恢复出珠资格并查询原 requestNo。
            occupancy.markRedemptionWaitingDispense(session.clientRequestNo);
            scheduleQuery(session.clientRequestNo, 0L);
        } else {
            broadcast(session);
        }
    }

    public synchronized void onPhysicalDispenseFinished() {
        InternalRedemptionStore.Session session = store.load();
        if (session == null) {
            return;
        }
        if (!session.terminal) {
            session.uiState = STATE_WAITING_FINAL;
            session.message = "出珠已完成，正在确认券码核销最终状态";
            store.save(session);
            broadcast(session);
            scheduleQuery(session.clientRequestNo, 0L);
        } else {
            releaseIfSettled(session);
        }
    }

    public synchronized boolean abandonBeforeSubmit() {
        InternalRedemptionStore.Session session = store.load();
        if (session == null) {
            return true;
        }
        if (session.submittedAt > 0L || STATE_SUBMITTING.equals(session.uiState)) {
            return false;
        }
        cancelQuery();
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot != null
                && TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION.equals(snapshot.ownerType)
                && session.clientRequestNo.equals(snapshot.clientRequestNo)) {
            occupancy.release(snapshot.sessionId, "internal redemption abandoned", true);
        }
        store.clear();
        return true;
    }

    public synchronized void acknowledgeTerminal() {
        InternalRedemptionStore.Session session = store.load();
        if (session == null || !session.terminal) {
            return;
        }
        if (STATE_MANUAL_REVIEW.equals(session.uiState)) {
            return;
        }
        releaseIfSettled(session);
        if (!commandStore.hasActivePhysicalOrder()) {
            store.clear();
        }
    }

    public synchronized UiSnapshot snapshot() {
        InternalRedemptionStore.Session session = store.load();
        if (session == null) {
            return null;
        }
        UiSnapshot result = new UiSnapshot();
        result.clientRequestNo = session.clientRequestNo;
        result.operationNo = session.operationNo;
        result.redemptionStatus = session.redemptionStatus;
        result.requestedQuantity = session.requestedQuantity;
        result.dispensedQuantity = session.dispensedQuantity;
        result.uiState = session.uiState;
        result.message = session.message;
        result.terminal = session.terminal;
        result.submittedAt = session.submittedAt;
        return result;
    }

    public synchronized void onOccupancyReleased(String requestNo) {
        InternalRedemptionStore.Session session = store.load();
        if (session == null || !requestNo.equals(session.clientRequestNo)) {
            return;
        }
        if (session.terminal && STATE_MANUAL_REVIEW.equals(session.uiState)) {
            // 人工处理流程已经显式释放全局占用后，清除本地异常快照，避免再次进入页面又重新阻塞。
            store.clear();
            return;
        }
        broadcast(session);
    }

    private void releaseIfSettled(InternalRedemptionStore.Session session) {
        if (session == null || !session.terminal) {
            return;
        }
        if (STATE_MANUAL_REVIEW.equals(session.uiState)) {
            occupancy.markBlocked("INTERNAL_REDEMPTION_TERMINAL_MANUAL_REVIEW");
            return;
        }
        if (commandStore.hasActivePhysicalOrder()) {
            occupancy.transitionRedemption(
                    session.clientRequestNo,
                    TransactionOccupancyManager.PHASE_FINISHING
            );
            scheduleReleaseCheck(session.clientRequestNo);
            return;
        }
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot != null
                && TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION.equals(snapshot.ownerType)
                && session.clientRequestNo.equals(snapshot.clientRequestNo)) {
            occupancy.release(snapshot.sessionId, "internal redemption terminal", true);
        }
    }

    private synchronized void scheduleReleaseCheck(String requestNo) {
        if (queryTask != null && !queryTask.isDone()) {
            return;
        }
        queryTask = executor.schedule(() -> {
            synchronized (InternalRedemptionManager.this) {
                queryTask = null;
            }
            InternalRedemptionStore.Session session = store.load();
            if (session != null && requestNo.equals(session.clientRequestNo)) {
                releaseIfSettled(session);
            }
        }, 2_000L, TimeUnit.MILLISECONDS);
    }

    private void broadcast(InternalRedemptionStore.Session session) {
        broadcastMessage(session.message, session.uiState, session.clientRequestNo);
    }

    private void broadcastMessage(String message, String state, String requestNo) {
        Intent intent = new Intent(ACTION_CHANGED);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_MESSAGE, safe(message));
        intent.putExtra(EXTRA_STATE, safe(state));
        intent.putExtra(EXTRA_REQUEST_NO, safe(requestNo));
        context.sendBroadcast(intent);
    }

    private static DeviceApiException asApiError(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof DeviceApiException) {
                return (DeviceApiException) current;
            }
            current = current.getCause();
        }
        return null;
    }

    private static void clearSensitive(char[] value) {
        if (value == null) {
            return;
        }
        for (int index = 0; index < value.length; index++) {
            value[index] = '\0';
        }
    }

    private static String newRequestNo() {
        return "APPREDEEM_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    public static final class UiSnapshot {
        public String clientRequestNo = "";
        public String operationNo = "";
        public String redemptionStatus = "";
        public int requestedQuantity;
        public int dispensedQuantity;
        public String uiState = "";
        public String message = "";
        public boolean terminal;
        public long submittedAt;
    }
}
''')

# 15. 券码核销独立页面
write("GouzhuApp/app/src/main/java/com/gouzhu/redemption/InternalRedemptionActivity.java", r'''package com.gouzhu.redemption;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.gouzhu.R;

/** 官方小程序套餐券核销页面。 */
public final class InternalRedemptionActivity extends AppCompatActivity {

    private TextView statusText;
    private LinearLayout scanSection;
    private TextView resultDetailText;
    private Button backButton;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null
                    && InternalRedemptionManager.ACTION_CHANGED.equals(intent.getAction())) {
                refresh();
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_internal_redemption);
        bindViews();
        findViewById(R.id.button_internal_redemption_close)
                .setOnClickListener(view -> requestClose());
        backButton.setOnClickListener(view -> requestClose());
        registerReceiverIfNeeded();
        hideSystemUi();

        InternalRedemptionManager manager = InternalRedemptionManager.get(this);
        if (manager.snapshot() == null) {
            manager.beginScan();
        } else {
            manager.resumePending();
        }
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        InternalRedemptionManager.get(this).resumePending();
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        requestClose();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void bindViews() {
        statusText = findViewById(R.id.text_internal_redemption_status);
        scanSection = findViewById(R.id.layout_internal_redemption_scan);
        resultDetailText = findViewById(R.id.text_internal_redemption_result_detail);
        backButton = findViewById(R.id.button_internal_redemption_back);
    }

    private void refresh() {
        InternalRedemptionManager.UiSnapshot snapshot =
                InternalRedemptionManager.get(this).snapshot();
        if (snapshot == null) {
            statusText.setText(R.string.internal_redemption_preparing);
            scanSection.setVisibility(View.VISIBLE);
            resultDetailText.setText("");
            backButton.setText(R.string.redemption_back_home);
            return;
        }

        statusText.setText(snapshot.message);
        boolean scanning = InternalRedemptionManager.STATE_STARTING.equals(snapshot.uiState)
                || InternalRedemptionManager.STATE_SCANNING.equals(snapshot.uiState);
        scanSection.setVisibility(scanning ? View.VISIBLE : View.GONE);

        if (InternalRedemptionManager.STATE_MANUAL_REVIEW.equals(snapshot.uiState)) {
            resultDetailText.setText(R.string.internal_redemption_manual_review);
        } else if (snapshot.requestedQuantity > 0) {
            resultDetailText.setText(getString(
                    R.string.internal_redemption_quantity_format,
                    snapshot.dispensedQuantity,
                    snapshot.requestedQuantity
            ));
        } else {
            resultDetailText.setText("");
        }
        backButton.setText(snapshot.terminal
                ? R.string.redemption_finish
                : R.string.redemption_back_home);
    }

    private void requestClose() {
        InternalRedemptionManager manager = InternalRedemptionManager.get(this);
        InternalRedemptionManager.UiSnapshot snapshot = manager.snapshot();
        if (snapshot == null) {
            finish();
            return;
        }
        if (snapshot.terminal) {
            manager.acknowledgeTerminal();
            finish();
            return;
        }
        if (manager.abandonBeforeSubmit()) {
            finish();
            return;
        }
        // HTTP 请求已经提交后返回首页不等于取消核销，后台继续查询原 requestNo。
        finish();
    }

    private void registerReceiverIfNeeded() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(InternalRedemptionManager.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void hideSystemUi() {
        View decorView = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }
}
''')

# 16. 券码核销 UI：使用用户上传的“弹球小达”图标资源
write("GouzhuApp/app/src/main/res/layout/activity_internal_redemption.xml", r'''<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/page_background"
    android:fillViewport="true"
    android:overScrollMode="never">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_horizontal"
        android:orientation="vertical"
        android:paddingStart="28dp"
        android:paddingTop="28dp"
        android:paddingEnd="28dp"
        android:paddingBottom="48dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="vertical">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/internal_redemption_title"
                    android:textColor="@color/text_primary"
                    android:textSize="32sp"
                    android:textStyle="bold" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="8dp"
                    android:text="@string/internal_redemption_subtitle"
                    android:textColor="@color/text_secondary"
                    android:textSize="18sp" />
            </LinearLayout>

            <Button
                android:id="@+id/button_internal_redemption_close"
                style="@style/Widget.Gouzhu.ActionButton"
                android:layout_width="72dp"
                android:text="×"
                android:textSize="28sp" />
        </LinearLayout>

        <TextView
            android:id="@+id/text_internal_redemption_status"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="26dp"
            android:background="@drawable/bg_selected_package"
            android:gravity="center"
            android:minHeight="78dp"
            android:padding="18dp"
            android:text="@string/internal_redemption_preparing"
            android:textColor="@color/text_primary"
            android:textSize="20sp" />

        <LinearLayout
            android:id="@+id/layout_internal_redemption_scan"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:background="@drawable/bg_selected_package"
            android:gravity="center"
            android:orientation="vertical"
            android:padding="24dp">

            <ImageView
                android:layout_width="238dp"
                android:layout_height="238dp"
                android:contentDescription="@string/internal_redemption_logo_description"
                android:scaleType="centerInside"
                android:src="@drawable/ic_internal_redemption_brand" />

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="14dp"
                android:gravity="center"
                android:text="@string/internal_redemption_scan_hint"
                android:textColor="@color/text_primary"
                android:textSize="22sp"
                android:textStyle="bold" />

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:gravity="center"
                android:text="@string/redemption_scanner_subtitle"
                android:textColor="@color/text_secondary"
                android:textSize="16sp" />
        </LinearLayout>

        <TextView
            android:id="@+id/text_internal_redemption_result_detail"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="22dp"
            android:gravity="center"
            android:lineSpacingExtra="5dp"
            android:textColor="@color/text_secondary"
            android:textSize="18sp" />

        <Button
            android:id="@+id/button_internal_redemption_back"
            style="@style/Widget.Gouzhu.ActionButton"
            android:layout_width="220dp"
            android:layout_marginTop="30dp"
            android:text="@string/redemption_back_home" />
    </LinearLayout>
</ScrollView>
''')

# 17. 纯策略单测
write("GouzhuApp/app/src/test/java/com/gouzhu/redemption/InternalRedemptionPolicyTest.java", r'''package com.gouzhu.redemption;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class InternalRedemptionPolicyTest {

    @Test
    public void nonTerminalRemainsPending() {
        assertEquals(
                InternalRedemptionPolicy.OUTCOME_PENDING,
                InternalRedemptionPolicy.terminalOutcome(false, 10, 0)
        );
    }

    @Test
    public void completeQuantityIsSuccessful() {
        assertEquals(
                InternalRedemptionPolicy.OUTCOME_SUCCEEDED,
                InternalRedemptionPolicy.terminalOutcome(true, 10, 10)
        );
        assertEquals(
                InternalRedemptionPolicy.OUTCOME_SUCCEEDED,
                InternalRedemptionPolicy.terminalOutcome(true, 10, 12)
        );
    }

    @Test
    public void partialPhysicalDeliveryRequiresManualReview() {
        assertEquals(
                InternalRedemptionPolicy.OUTCOME_MANUAL_REVIEW,
                InternalRedemptionPolicy.terminalOutcome(true, 10, 3)
        );
        assertEquals(
                InternalRedemptionPolicy.OUTCOME_MANUAL_REVIEW,
                InternalRedemptionPolicy.terminalOutcome(true, 0, 1)
        );
    }

    @Test
    public void terminalWithoutDispenseIsSafeFailure() {
        assertEquals(
                InternalRedemptionPolicy.OUTCOME_FAILED,
                InternalRedemptionPolicy.terminalOutcome(true, 10, 0)
        );
    }
}
''')
