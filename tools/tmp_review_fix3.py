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


# 1. 核销进入可出珠阶段必须保持单调：READY/PREPARING 可前进，物理阶段不回退，异常阶段不重开。
path = "GouzhuApp/app/src/main/java/com/gouzhu/transaction/TransactionOccupancyManager.java"
text = read(path)
needle = '''    public boolean transitionRedemption(String clientRequestNo, String phase) {\n        Snapshot snapshot = current();\n        if (snapshot == null\n                || !isRedemptionOwner(snapshot.ownerType)\n                || !safe(clientRequestNo).equals(snapshot.clientRequestNo)) {\n            return false;\n        }\n        return transitionAnyPhase(snapshot.sessionId, phase, "");\n    }\n\n'''
insert = '''    public boolean transitionRedemption(String clientRequestNo, String phase) {\n        Snapshot snapshot = current();\n        if (snapshot == null\n                || !isRedemptionOwner(snapshot.ownerType)\n                || !safe(clientRequestNo).equals(snapshot.clientRequestNo)) {\n            return false;\n        }\n        return transitionAnyPhase(snapshot.sessionId, phase, "");\n    }\n\n    /**\n     * 把已经提交/确认的核销业务推进到“等待平台出珠授权”。\n     * HTTP 查询可能晚于 MQTT 物理动作，因此已进入物理阶段时只返回成功而绝不回退；\n     * BLOCKED/REFUNDING 等异常阶段也绝不被普通查询重新打开。\n     */\n    public boolean markRedemptionWaitingDispense(String clientRequestNo) {\n        Snapshot snapshot = current();\n        if (snapshot == null\n                || !isRedemptionOwner(snapshot.ownerType)\n                || !safe(clientRequestNo).equals(snapshot.clientRequestNo)) {\n            return false;\n        }\n        if (PHASE_WAITING_DISPENSE.equals(snapshot.phase)\n                || TransactionOccupancyPolicy.isPhysicalPhase(snapshot.phase)) {\n            return true;\n        }\n        if (!(PHASE_PREPARING.equals(snapshot.phase) || PHASE_READY.equals(snapshot.phase))) {\n            return false;\n        }\n        return transition(\n                snapshot.sessionId,\n                snapshot.phase,\n                PHASE_WAITING_DISPENSE,\n                null,\n                null,\n                null,\n                null,\n                ""\n        );\n    }\n\n'''
if text.count(needle) != 1:
    raise RuntimeError("TransactionOccupancyManager transitionRedemption anchor mismatch")
write(path, text.replace(needle, insert, 1))

# 2. 团购 confirm、普通非终态和重启恢复全部走单调迁移。
path = "GouzhuApp/app/src/main/java/com/gouzhu/redemption/ThirdPartyRedemptionManager.java"
text = read(path)
text = text.replace(
    '''        if (!occupancy.transitionRedemption(\n                session.clientRequestNo,\n                TransactionOccupancyManager.PHASE_WAITING_DISPENSE\n        )) {\n            block("THIRD_PARTY_CONFIRM_OCCUPANCY_FAILED", "正式核销前交易状态切换失败");\n            return false;\n        }''',
    '''        if (!occupancy.markRedemptionWaitingDispense(session.clientRequestNo)) {\n            block("THIRD_PARTY_CONFIRM_OCCUPANCY_FAILED", "正式核销前交易状态切换失败");\n            return false;\n        }''',
    1
)
text = text.replace(
    '''            } else {\n                occupancy.transitionRedemption(\n                        session.clientRequestNo,\n                        TransactionOccupancyManager.PHASE_WAITING_DISPENSE\n                );\n            }''',
    '''            } else {\n                // HTTP 非终态不得把已经开始的物理出珠回退到 WAITING_DISPENSE。\n                occupancy.markRedemptionWaitingDispense(session.clientRequestNo);\n            }''',
    1
)
old = '''        if (session.confirmRequestedAt > 0L\n                || ThirdPartyRedemptionPolicy.STATE_CONFIRMING.equals(session.uiState)\n                || ThirdPartyRedemptionPolicy.STATE_WAITING_FINAL_STATUS.equals(session.uiState)\n                || ThirdPartyRedemptionPolicy.STATE_WAITING_DISPENSE_COMMAND.equals(session.uiState)\n                || ThirdPartyRedemptionPolicy.STATE_DISPENSING.equals(session.uiState)\n                || ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW.equals(session.uiState)) {\n            scheduleQuery(session.clientRequestNo, 0L);\n        } else {\n            broadcast(session);\n        }'''
new = '''        if (session.confirmRequestedAt > 0L\n                || ThirdPartyRedemptionPolicy.STATE_CONFIRMING.equals(session.uiState)\n                || ThirdPartyRedemptionPolicy.STATE_WAITING_FINAL_STATUS.equals(session.uiState)\n                || ThirdPartyRedemptionPolicy.STATE_WAITING_DISPENSE_COMMAND.equals(session.uiState)\n                || ThirdPartyRedemptionPolicy.STATE_DISPENSING.equals(session.uiState)\n                || ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW.equals(session.uiState)) {\n            // confirm 已经越过消费边界；如果 occupancy 是重建出来的 PREPARING，\n            // 先恢复到 WAITING_DISPENSE，避免合法 MQTT 比 HTTP query 更早到达时被误拒绝。\n            if (!ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW.equals(session.uiState)) {\n                occupancy.markRedemptionWaitingDispense(session.clientRequestNo);\n            }\n            scheduleQuery(session.clientRequestNo, 0L);\n        } else {\n            broadcast(session);\n        }'''
if text.count(old) != 1:
    raise RuntimeError("ThirdParty resume anchor mismatch")
text = text.replace(old, new, 1)
write(path, text)

# 3. 会员取珠提交、HTTP 更新和重启恢复使用相同单调迁移。
path = "GouzhuApp/app/src/main/java/com/gouzhu/redemption/MemberWithdrawalManager.java"
text = read(path)
text = text.replace(
    '''        if (!occupancy.transitionRedemption(\n                session.clientRequestNo,\n                TransactionOccupancyManager.PHASE_WAITING_DISPENSE\n        )) {\n            occupancy.markBlocked("MEMBER_WITHDRAW_OCCUPANCY_FAILED");''',
    '''        if (!occupancy.markRedemptionWaitingDispense(session.clientRequestNo)) {\n            occupancy.markBlocked("MEMBER_WITHDRAW_OCCUPANCY_FAILED");''',
    1
)
text = text.replace(
    '''            occupancy.transitionRedemption(\n                    session.clientRequestNo,\n                    TransactionOccupancyManager.PHASE_WAITING_DISPENSE\n            );''',
    '''            // 查询晚于 MQTT 时保持 DISPENSING/FINISHING，不把物理阶段回退。\n            occupancy.markRedemptionWaitingDispense(session.clientRequestNo);''',
    1
)
old = '''        } else if (STATE_SUBMITTING.equals(session.uiState)\n                || STATE_WAITING_DISPENSE.equals(session.uiState)\n                || STATE_WAITING_FINAL.equals(session.uiState)) {\n            // 原扫码内容不落库；一旦曾经提交，重启后只查询原请求，禁止再次提交。\n            scheduleQuery(session.clientRequestNo, 0L);\n        } else {'''
new = '''        } else if (STATE_SUBMITTING.equals(session.uiState)\n                || STATE_WAITING_DISPENSE.equals(session.uiState)\n                || STATE_WAITING_FINAL.equals(session.uiState)) {\n            // 原扫码内容不落库；一旦曾经提交，重启后只查询原请求，禁止再次提交。\n            // occupancy 若是重建的 PREPARING，先恢复出珠资格以接住可能先到的 MQTT。\n            occupancy.markRedemptionWaitingDispense(session.clientRequestNo);\n            scheduleQuery(session.clientRequestNo, 0L);\n        } else {'''
if text.count(old) != 1:
    raise RuntimeError("Member resume anchor mismatch")
text = text.replace(old, new, 1)
write(path, text)

# 4. 首页即使没有购珠套餐也要根据设备占用重新门控两个服务入口。
replace_once(
    "GouzhuApp/app/src/main/java/com/gouzhu/MainActivity.java",
    '''        if (packageOptions.isEmpty()) {\n            disablePackages();\n            paymentStatusText.setText(R.string.sdk_no_purchase_tier);\n            return;\n        }''',
    '''        if (packageOptions.isEmpty()) {\n            disablePackages();\n            paymentStatusText.setText(R.string.sdk_no_purchase_tier);\n            applyTransactionOccupancy();\n            return;\n        }'''
)

print("third review fixes applied")
