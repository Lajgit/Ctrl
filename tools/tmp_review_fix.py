from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one match, got {count}")
    write(path, text.replace(old, new, 1))


# 1. 串口层保留原始扫码正文，第三方/会员交 SDK 时不自行 trim。
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/scanner/ReverseScannerManager.java",
    '''    private void handleScan(byte[] payload) {\n        String content = new String(payload, StandardCharsets.UTF_8)\n                .replace("\\u0000", "")\n                .trim();\n        if (content.isEmpty()) {\n            return;\n        }''',
    '''    private void handleScan(byte[] payload) {\n        // 业务扫码原文不在设备端做 URL 解码、截取或首尾改写；SDK 负责协议规定的 trim。\n        String rawContent = new String(payload, StandardCharsets.UTF_8)\n                .replace("\\u0000", "");\n        String content = rawContent.trim();\n        if (content.isEmpty()) {\n            return;\n        }'''
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/scanner/ReverseScannerManager.java",
    '            if (thirdParty.handleScannerInput(content)) {',
    '            if (thirdParty.handleScannerInput(rawContent)) {'
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/scanner/ReverseScannerManager.java",
    '            if (member.handleScannerInput(content)) {',
    '            if (member.handleScannerInput(rawContent)) {'
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/redemption/ThirdPartyRedemptionManager.java",
    '''        String code = rawCode == null ? "" : rawCode.trim();\n        if (code.isEmpty() || code.length() > MAX_VOUCHER_LENGTH || code.indexOf('|') >= 0) {''',
    '''        String code = rawCode == null ? "" : rawCode;\n        if (code.trim().isEmpty()\n                || code.length() > MAX_VOUCHER_LENGTH\n                || code.indexOf('|') >= 0) {'''
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/redemption/MemberWithdrawalManager.java",
    '''        String code = rawCode == null ? "" : rawCode.trim();\n        if (code.isEmpty() || code.length() > MAX_CODE_LENGTH) {''',
    '''        String code = rawCode == null ? "" : rawCode;\n        if (code.trim().isEmpty() || code.length() > MAX_CODE_LENGTH) {'''
)

# 2. 新核销 owner 只有在业务已经提交/确认后才允许合法 MQTT 接管。
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyPolicy.java",
    '''        return isIdleOwner(owner)\n                || "QR_PURCHASE".equals(owner)\n                || "CASH_PURCHASE".equals(owner)\n                || "MEMBER_WITHDRAWAL".equals(owner)\n                || "THIRD_PARTY_REDEMPTION".equals(owner)\n                || "GENERIC_DISPENSE".equals(owner);''',
    '''        if ("MEMBER_WITHDRAWAL".equals(owner)\n                || "THIRD_PARTY_REDEMPTION".equals(owner)) {\n            // prepare/扫码阶段绝不接受出珠；请求已提交后先切 WAITING_DISPENSE，\n            // 允许平台 MQTT 比对应 HTTP 响应更早到达。\n            return "WAITING_DISPENSE".equals(phase) || isPhysicalPhase(phase);\n        }\n        return isIdleOwner(owner)\n                || "QR_PURCHASE".equals(owner)\n                || "CASH_PURCHASE".equals(owner)\n                || "GENERIC_DISPENSE".equals(owner);'''
)
replace_once(
    "GouzhuApp/app/src/test/java/com/gouzhu/transaction/TransactionOccupancyPolicyTest.java",
    '''        assertTrue(TransactionOccupancyPolicy.canReserveDispense(\n                "MEMBER_WITHDRAWAL",\n                "WAITING_DISPENSE"\n        ));\n        assertTrue(TransactionOccupancyPolicy.canReserveDispense(\n                "THIRD_PARTY_REDEMPTION",\n                "WAITING_DISPENSE"\n        ));''',
    '''        assertFalse(TransactionOccupancyPolicy.canReserveDispense(\n                "MEMBER_WITHDRAWAL",\n                "READY"\n        ));\n        assertFalse(TransactionOccupancyPolicy.canReserveDispense(\n                "THIRD_PARTY_REDEMPTION",\n                "READY"\n        ));\n        assertTrue(TransactionOccupancyPolicy.canReserveDispense(\n                "MEMBER_WITHDRAWAL",\n                "WAITING_DISPENSE"\n        ));\n        assertTrue(TransactionOccupancyPolicy.canReserveDispense(\n                "THIRD_PARTY_REDEMPTION",\n                "WAITING_DISPENSE"\n        ));'''
)

# confirm 门禁先落库，再把 occupancy 切到 WAITING_DISPENSE，最后才发正式核销 HTTP。
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/redemption/ThirdPartyRedemptionManager.java",
    '''        if (!store.saveThirdParty(session)) {\n            block("THIRD_PARTY_CONFIRM_STATE_SAVE_FAILED", "正式核销状态无法可靠保存");\n            return false;\n        }\n        broadcast(session);\n        executor.execute(() -> confirmOnce(session.clientRequestNo, candidate.certificateId));''',
    '''        if (!store.saveThirdParty(session)) {\n            block("THIRD_PARTY_CONFIRM_STATE_SAVE_FAILED", "正式核销状态无法可靠保存");\n            return false;\n        }\n        if (!occupancy.transitionRedemption(\n                session.clientRequestNo,\n                TransactionOccupancyManager.PHASE_WAITING_DISPENSE\n        )) {\n            block("THIRD_PARTY_CONFIRM_OCCUPANCY_FAILED", "正式核销前交易状态切换失败");\n            return false;\n        }\n        broadcast(session);\n        executor.execute(() -> confirmOnce(session.clientRequestNo, candidate.certificateId));'''
)

# 会员取珠同样在请求落盘后、HTTP 调用前开放对应 MQTT 物理授权窗口。
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/redemption/MemberWithdrawalManager.java",
    '''        if (!store.saveMember(session)) {\n            occupancy.markBlocked("MEMBER_WITHDRAW_STATE_SAVE_FAILED");\n            return true;\n        }\n        broadcast(session);\n        char[] sensitiveCode = code.toCharArray();''',
    '''        if (!store.saveMember(session)) {\n            occupancy.markBlocked("MEMBER_WITHDRAW_STATE_SAVE_FAILED");\n            return true;\n        }\n        if (!occupancy.transitionRedemption(\n                session.clientRequestNo,\n                TransactionOccupancyManager.PHASE_WAITING_DISPENSE\n        )) {\n            occupancy.markBlocked("MEMBER_WITHDRAW_OCCUPANCY_FAILED");\n            session.uiState = STATE_FAILED;\n            session.message = "会员取珠交易状态切换失败，请联系工作人员";\n            store.saveMember(session);\n            broadcast(session);\n            return true;\n        }\n        broadcast(session);\n        char[] sensitiveCode = code.toCharArray();'''
)

# 3. feature 动态能力必须直接反映到服务卡 enabled，且每次回首页都刷新 bootstrap。
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/MainActivity.java",
    '''    private void loadBootstrap(boolean force) {\n        if (bootstrapLoading || (!force && !packageOptions.isEmpty())) {\n            return;\n        }''',
    '''    private void loadBootstrap(boolean force) {\n        // 核销 feature/routingVersion 可能由服务端动态变化，回首页和 MQTT 重连都重新读取。\n        if (bootstrapLoading) {\n            return;\n        }'''
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/MainActivity.java",
    '''        memberWithdrawEntry.setVisibility(member.visible ? View.VISIBLE : View.GONE);\n        thirdPartyRedemptionEntry.setVisibility(third.visible ? View.VISIBLE : View.GONE);\n        memberWithdrawHint.setText(member.available''',
    '''        memberWithdrawEntry.setVisibility(member.visible ? View.VISIBLE : View.GONE);\n        thirdPartyRedemptionEntry.setVisibility(third.visible ? View.VISIBLE : View.GONE);\n        memberWithdrawEntry.setEnabled(memberWithdrawAvailable);\n        thirdPartyRedemptionEntry.setEnabled(thirdPartyAvailable);\n        memberWithdrawEntry.setAlpha(memberWithdrawAvailable ? 1f : 0.5f);\n        thirdPartyRedemptionEntry.setAlpha(thirdPartyAvailable ? 1f : 0.5f);\n        memberWithdrawHint.setText(member.available'''
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/MainActivity.java",
    '''        if (!resumingMember && !resumingThird\n                && !TransactionOccupancyManager.get(this).canStartNewTransaction()) {\n            Toast.makeText(this, R.string.transaction_device_busy, Toast.LENGTH_SHORT).show();\n            return;\n        }''',
    '''        if (!resumingMember && RedemptionActivity.MODE_MEMBER.equals(mode)\n                && !memberWithdrawAvailable) {\n            Toast.makeText(this, R.string.redemption_start_failed, Toast.LENGTH_SHORT).show();\n            return;\n        }\n        if (!resumingThird && RedemptionActivity.MODE_THIRD_PARTY.equals(mode)\n                && !thirdPartyAvailable) {\n            Toast.makeText(this, R.string.redemption_start_failed, Toast.LENGTH_SHORT).show();\n            return;\n        }\n        if (!resumingMember && !resumingThird\n                && !TransactionOccupancyManager.get(this).canStartNewTransaction()) {\n            Toast.makeText(this, R.string.transaction_device_busy, Toast.LENGTH_SHORT).show();\n            return;\n        }'''
)

# 4. 非终态补偿/人工处理禁止再接新出珠；RESOLVED 后才允许已处理异常收敛释放。
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/redemption/ThirdPartyRedemptionPolicy.java",
    '''        if ("FULL_DELIVERY".equals(fulfillment)) {\n            return STATE_SUCCEEDED;\n        }\n        if ("REDEEM_FAILED".equals(channel) && "CANCELED".equals(fulfillment)) {\n            return STATE_FAILED;\n        }\n        if ("MANUAL_REVIEW".equals(resolution)\n                || "PARTIAL_DELIVERY".equals(fulfillment)\n                || "RESULT_UNKNOWN".equals(fulfillment)) {\n            return STATE_MANUAL_REVIEW;\n        }\n        return STATE_FAILED;''',
    '''        if ("FULL_DELIVERY".equals(fulfillment)) {\n            return STATE_SUCCEEDED;\n        }\n        if ("REDEEM_FAILED".equals(channel) && "CANCELED".equals(fulfillment)) {\n            return STATE_FAILED;\n        }\n        if (!"RESOLVED".equals(resolution)\n                && ("MANUAL_REVIEW".equals(resolution)\n                || "PARTIAL_DELIVERY".equals(fulfillment)\n                || "RESULT_UNKNOWN".equals(fulfillment))) {\n            return STATE_MANUAL_REVIEW;\n        }\n        return STATE_FAILED;'''
)
replace_once(
    "GouzhuApp/app/src/test/java/com/gouzhu/redemption/ThirdPartyRedemptionPolicyTest.java",
    '''        assertEquals(ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW,\n                ThirdPartyRedemptionPolicy.terminalUiState(\n                        true, "REDEEMED", "PARTIAL_DELIVERY", "NORMAL"));''',
    '''        assertEquals(ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW,\n                ThirdPartyRedemptionPolicy.terminalUiState(\n                        true, "REDEEMED", "PARTIAL_DELIVERY", "NORMAL"));\n        assertEquals(ThirdPartyRedemptionPolicy.STATE_FAILED,\n                ThirdPartyRedemptionPolicy.terminalUiState(\n                        true, "REDEEMED", "PARTIAL_DELIVERY", "RESOLVED"));'''
)

# 状态先计算 UI 再生成默认中文消息，避免引用上一阶段 uiState。
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/redemption/ThirdPartyRedemptionManager.java",
    '''        session.terminal = result.isTerminal();\n        session.lastStatusCheckedAt = System.currentTimeMillis();\n        session.message = firstNonBlank(result.getMessage(), statusMessage(session));\n        session.uiState = ThirdPartyRedemptionPolicy.terminalUiState(\n                session.terminal,\n                session.channelStatus,\n                session.fulfillmentStatus,\n                session.resolutionStatus\n        );''',
    '''        session.terminal = result.isTerminal();\n        session.lastStatusCheckedAt = System.currentTimeMillis();\n        session.uiState = ThirdPartyRedemptionPolicy.terminalUiState(\n                session.terminal,\n                session.channelStatus,\n                session.fulfillmentStatus,\n                session.resolutionStatus\n        );\n        session.message = firstNonBlank(result.getMessage(), statusMessage(session));'''
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/redemption/ThirdPartyRedemptionManager.java",
    '''        if (!session.terminal) {\n            occupancy.transitionRedemption(\n                    session.clientRequestNo,\n                    TransactionOccupancyManager.PHASE_WAITING_DISPENSE\n            );\n            broadcast(session);\n            scheduleQuery(requestNo, ThirdPartyRedemptionPolicy.NORMAL_QUERY_DELAY_MS);\n            return;\n        }''',
    '''        if (!session.terminal) {\n            String resolution = normalize(session.resolutionStatus);\n            String channelStatus = normalize(session.channelStatus);\n            if ("MANUAL_REVIEW".equals(resolution)) {\n                occupancy.markBlocked("THIRD_PARTY_MANUAL_REVIEW");\n            } else if ("AUTO_COMPENSATING".equals(resolution)\n                    || "REVERSING".equals(channelStatus)\n                    || "REVERSED".equals(channelStatus)) {\n                if (!commandStore.hasActivePhysicalOrder()) {\n                    occupancy.transitionRedemption(\n                            session.clientRequestNo,\n                            TransactionOccupancyManager.PHASE_REFUNDING\n                    );\n                }\n            } else {\n                occupancy.transitionRedemption(\n                        session.clientRequestNo,\n                        TransactionOccupancyManager.PHASE_WAITING_DISPENSE\n                );\n            }\n            broadcast(session);\n            scheduleQuery(requestNo, ThirdPartyRedemptionPolicy.NORMAL_QUERY_DELAY_MS);\n            return;\n        }'''
)
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/redemption/ThirdPartyRedemptionManager.java",
    '''        if (session == null || !session.terminal) {\n            return;\n        }\n        if (commandStore.hasActivePhysicalOrder()) {''',
    '''        if (session == null || !session.terminal) {\n            return;\n        }\n        if (ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW.equals(session.uiState)) {\n            occupancy.markBlocked("THIRD_PARTY_TERMINAL_MANUAL_REVIEW");\n            return;\n        }\n        if (commandStore.hasActivePhysicalOrder()) {'''
)

# 顾客页面只显示中文业务摘要，不直接暴露后端内部英文枚举。
path = "GouzhuApp/app/src/main/java/com/gouzhu/redemption/RedemptionActivity.java"
text = read(path)
start = text.index('    private String buildThirdPartyDetail(')
end = text.index('    private void requestClose()', start)
replacement = '''    private String buildThirdPartyDetail(ThirdPartyRedemptionManager.UiSnapshot snapshot) {\n        StringBuilder builder = new StringBuilder();\n        if (!snapshot.channelName.isEmpty()) {\n            builder.append("渠道：").append(snapshot.channelName);\n        }\n        if (snapshot.requestedQuantity > 0) {\n            if (builder.length() > 0) {\n                builder.append("\\n");\n            }\n            builder.append("应出：").append(snapshot.requestedQuantity).append("珠");\n        }\n        if (snapshot.actualQuantity >= 0) {\n            builder.append("  实出：").append(snapshot.actualQuantity).append("珠");\n        }\n        if (ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW.equals(snapshot.uiState)) {\n            builder.append("\\n当前业务需要工作人员处理，请勿重复核销同一张券");\n        } else if (ThirdPartyRedemptionPolicy.STATE_SUCCEEDED.equals(snapshot.uiState)) {\n            builder.append("\\n核销和出珠已完成");\n        }\n        return builder.toString();\n    }\n\n'''
text = text[:start] + replacement + text[end:]
write(path, text)

print("review fixes applied")
