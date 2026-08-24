from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "GouzhuApp/app/src/main/java/com/gouzhu/redemption/ThirdPartyRedemptionManager.java"
text = PATH.read_text(encoding="utf-8")
old = '''        if (!ThirdPartyRedemptionPolicy.canConfirm(\n                session.uiState,\n                candidate.redeemable,\n                session.sessionExpireTime,\n                System.currentTimeMillis(),\n                session.confirmRequestedAt\n        )) {\n            session.uiState = ThirdPartyRedemptionPolicy.STATE_FAILED;\n            session.message = "验券会话已过期或当前券不可核销，请重新扫码";\n            store.saveThirdParty(session);\n            broadcast(session);\n            return false;\n        }\n'''
new = '''        long now = System.currentTimeMillis();\n        if (!ThirdPartyRedemptionPolicy.canConfirm(\n                session.uiState,\n                candidate.redeemable,\n                session.sessionExpireTime,\n                now,\n                session.confirmRequestedAt\n        )) {\n            if (session.confirmRequestedAt <= 0L\n                    && session.sessionExpireTime > 0L\n                    && session.sessionExpireTime <= now / 1000L) {\n                // prepare 没有消费券。会话过期后结束旧 requestNo，并按同一已选渠道\n                // 自动创建全新的业务请求回到扫码态，禁止继续使用过期 certificateId。\n                return restartExpiredPrepare(session);\n            }\n            session.uiState = ThirdPartyRedemptionPolicy.STATE_FAILED;\n            session.message = candidate.redeemable\n                    ? "当前核销状态已变化，请重新扫码"\n                    : firstNonBlank(candidate.unavailableReason, "当前券不可核销");\n            store.saveThirdParty(session);\n            broadcast(session);\n            return false;\n        }\n'''
if text.count(old) != 1:
    raise RuntimeError(f"confirm expiry anchor mismatch: {text.count(old)}")
text = text.replace(old, new, 1)

anchor = '''    public synchronized boolean abandonBeforeConfirm() {\n'''
method = '''    /** prepare 会话过期时自动切换到新的 requestNo；旧会话尚未 confirm，因此不会消费券。 */\n    private boolean restartExpiredPrepare(RedemptionSessionStore.ThirdPartySession expired) {\n        if (expired == null || expired.confirmRequestedAt > 0L) {\n            return false;\n        }\n        String channelCode = expired.channelCode;\n        cancelQuery();\n        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();\n        if (snapshot != null\n                && TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType)\n                && expired.clientRequestNo.equals(snapshot.clientRequestNo)) {\n            // 新会话马上再次确认现金 mask=0；这里不先恢复现金，避免开关现金入口形成竞态。\n            occupancy.release(snapshot.sessionId, "third party prepare expired", false);\n        }\n        store.clearThirdParty();\n        return startChannel(channelCode);\n    }\n\n'''
if text.count(anchor) != 1:
    raise RuntimeError("abandon anchor mismatch")
text = text.replace(anchor, method + anchor, 1)
PATH.write_text(text, encoding="utf-8")
print("fourth review fix applied")
