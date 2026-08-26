package com.chuzhu.member;

import android.content.Context;
import android.util.Log;

import com.chuzhu.data.MemberDepositStore;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 会员存珠二维码自动刷新调度器。
 *
 * <p>createMemberDepositSession 返回的 refreshAfterSeconds 是平台给出的二维码刷新周期。
 * 只要当前会话仍处于等待扫码状态，到期后就重新调用 Create，保存新的 qrContent，
 * 再通过 MemberDepositStore 广播驱动首页替换二维码图片。</p>
 */
public final class MemberQrRefreshScheduler {

    private static final String TAG = "CunzhuQrRefresh";
    private static final int MIN_REFRESH_SECONDS = 5;
    private static final Object LOCK = new Object();

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "存珠机会员二维码刷新");
                    thread.setDaemon(true);
                    return thread;
                }
            }
    );

    private static ScheduledFuture<?> currentFuture;
    private static String scheduledSessionId = "";
    private static String scheduledQrContent = "";
    private static long scheduledAtMillis;

    private MemberQrRefreshScheduler() {
    }

    public static void schedule(Context context, MemberDepositStore.Snapshot snapshot) {
        if (context == null || snapshot == null || !canRefresh(snapshot)) {
            cancel();
            return;
        }
        Context appContext = context.getApplicationContext();
        long now = System.currentTimeMillis();
        int delaySeconds = Math.max(MIN_REFRESH_SECONDS, snapshot.refreshAfterSeconds);
        long nextAt = now + TimeUnit.SECONDS.toMillis(delaySeconds);

        synchronized (LOCK) {
            if (snapshot.sessionId.equals(scheduledSessionId)
                    && snapshot.qrContent.equals(scheduledQrContent)
                    && currentFuture != null
                    && !currentFuture.isDone()) {
                return;
            }
            cancelLocked();
            scheduledSessionId = snapshot.sessionId;
            scheduledQrContent = snapshot.qrContent;
            scheduledAtMillis = nextAt;
            currentFuture = EXECUTOR.schedule(
                    () -> refreshIfStillWaiting(appContext, snapshot.sessionId, snapshot.qrContent),
                    delaySeconds,
                    TimeUnit.SECONDS
            );
            Log.i(TAG, "已安排会员存珠二维码自动刷新：sessionId=" + mask(snapshot.sessionId)
                    + "，delaySeconds=" + delaySeconds);
        }
    }

    public static void cancel() {
        synchronized (LOCK) {
            cancelLocked();
        }
    }

    private static void cancelLocked() {
        if (currentFuture != null) {
            currentFuture.cancel(false);
            currentFuture = null;
        }
        scheduledSessionId = "";
        scheduledQrContent = "";
        scheduledAtMillis = 0L;
    }

    private static void refreshIfStillWaiting(Context context, String sessionId, String qrContent) {
        try {
            MemberDepositStore store = new MemberDepositStore(context);
            MemberDepositStore.Snapshot current = store.loadWithoutScheduling();
            if (!same(current.sessionId, sessionId)
                    || !same(current.qrContent, qrContent)
                    || !canRefresh(current)) {
                Log.i(TAG, "跳过会员存珠二维码刷新：会话已变化或已绑定");
                cancel();
                return;
            }

            Log.i(TAG, "开始自动刷新会员存珠二维码：sessionId=" + mask(sessionId));
            MemberDepositStore.Snapshot next = new MemberDepositRepository(context).createSession();
            if (next == null || !next.hasSession() || !next.hasQrContent()) {
                throw new IllegalStateException("平台未返回新的会员存珠二维码 Session");
            }
            store.saveSession(next);
            Log.i(TAG, "会员存珠二维码已自动刷新：oldSession=" + mask(sessionId)
                    + "，newSession=" + mask(next.sessionId)
                    + "，qrChanged=" + !same(qrContent, next.qrContent)
                    + "，refreshAfterSeconds=" + next.refreshAfterSeconds);
        } catch (Throwable error) {
            Log.e(TAG, "会员存珠二维码自动刷新失败", error);
            synchronized (LOCK) {
                currentFuture = EXECUTOR.schedule(
                        () -> refreshIfStillWaiting(context, sessionId, qrContent),
                        MIN_REFRESH_SECONDS,
                        TimeUnit.SECONDS
                );
            }
        }
    }

    private static boolean canRefresh(MemberDepositStore.Snapshot snapshot) {
        return snapshot != null
                && snapshot.refreshAfterSeconds > 0
                && snapshot.hasSession()
                && snapshot.hasQrContent()
                && snapshot.isWaitingScan()
                && !snapshot.isBound();
    }

    private static boolean same(String left, String right) {
        if (left == null) {
            return right == null || right.isEmpty();
        }
        return left.equals(right == null ? "" : right);
    }

    private static String mask(String value) {
        if (value == null || value.length() <= 8) {
            return value == null ? "" : value;
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }
}
