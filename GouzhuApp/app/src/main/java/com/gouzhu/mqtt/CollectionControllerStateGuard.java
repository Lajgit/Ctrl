package com.gouzhu.mqtt;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.serial.SerialManager;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.gouzhu.util.DeviceUtil;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Detects a controller-side collection stop that occurs before the requested quantity.
 *
 * <p>BeadStockStatus expandCode=1 means the collection motor is active and 0 means inactive.
 * The controller emits a stock status after normal stop, target completion and motor timeout.
 * This closes the old ambiguity where a sensor timeout could later be reported as a successful
 * manual finish merely because CollectStop transport echo was received.</p>
 */
final class CollectionControllerStateGuard {

    private static final String TAG = "GouzhuCollectGuard";
    private static final String SESSION_TABLE = "collection_sessions";
    private static final int CMD_HARDWARE_STATUS = 0x20;
    private static final int EVT_BEAD_STOCK = 0x20;
    private static final Object DB_LOCK = new Object();

    private final Context context;
    private final DeviceCommandStore store;
    private final TransactionOccupancyManager occupancy;
    private final SdkCommandDecoder decoder = new SdkCommandDecoder();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gouzhu-collection-controller-guard");
        thread.setDaemon(true);
        return thread;
    });
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            if (intent == null) {
                return;
            }
            if (TransactionOccupancyManager.ACTION_CHANGED.equals(intent.getAction())) {
                String owner = intent.getStringExtra(
                        TransactionOccupancyManager.EXTRA_OWNER_TYPE
                );
                String phase = intent.getStringExtra(
                        TransactionOccupancyManager.EXTRA_PHASE
                );
                if (TransactionOccupancyManager.OWNER_MEMBER_DEPOSIT.equals(owner)
                        && TransactionOccupancyManager.PHASE_COLLECTING.equals(phase)) {
                    executor.execute(() -> SerialManager.get(context).sendCommand(
                            CMD_HARDWARE_STATUS,
                            0L,
                            false
                    ));
                }
                return;
            }
            if (!AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())
                    || intent.getIntExtra("code2", -1) != EVT_BEAD_STOCK) {
                return;
            }
            int collectActive = intent.getIntExtra("expandCode", 0) & 0xFF;
            if (collectActive != 0) {
                return;
            }
            int stock = (int) Math.max(0L, Math.min(
                    0xFFFFL,
                    intent.getLongExtra("data", 0L)
            ));
            executor.execute(() -> handleControllerInactive(stock));
        }
    };

    CollectionControllerStateGuard(Context context) {
        this.context = context.getApplicationContext();
        this.store = new DeviceCommandStore(this.context);
        this.occupancy = TransactionOccupancyManager.get(this.context);
    }

    synchronized void start() {
        ensureSessionTableExists();
        if (registered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(AppConfig.ACTION_BOARD_EVENT);
        filter.addAction(TransactionOccupancyManager.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        registered = true;
    }

    synchronized void stop() {
        if (!registered) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        registered = false;
    }

    private void handleControllerInactive(int currentStock) {
        SessionSnapshot session = loadCollectingSession(currentStock);
        if (session == null) {
            return;
        }
        persistActual(session.messageId, session.actualQuantity);
        if (session.actualQuantity >= session.maximumQuantity) {
            DeviceCommandManager.get(context).finishPendingCollection();
            return;
        }
        persistEarlyStopFailure(session);
    }

    private SessionSnapshot loadCollectingSession(int currentStock) {
        ensureSessionTableExists();
        synchronized (DB_LOCK) {
            try (Cursor cursor = store.getReadableDatabase().query(
                    SESSION_TABLE,
                    new String[]{
                            "message_id",
                            "operation_no",
                            "source_topic",
                            "command_envelope",
                            "maximum_quantity",
                            "baseline_stock",
                            "actual_quantity",
                            "occupancy_session_id",
                            "state"
                    },
                    "id=1 AND state='COLLECTING'",
                    null,
                    null,
                    null,
                    null
            )) {
                if (!cursor.moveToFirst()) {
                    return null;
                }
                SessionSnapshot result = new SessionSnapshot();
                result.messageId = cursor.getString(0);
                result.operationNo = cursor.getString(1);
                result.sourceTopic = cursor.getString(2);
                result.commandEnvelope = cursor.getString(3);
                result.maximumQuantity = cursor.getInt(4);
                result.baselineStock = cursor.getInt(5);
                int storedActual = cursor.getInt(6);
                result.occupancySessionId = cursor.getString(7);
                int stockActual = result.baselineStock < 0
                        ? 0
                        : Math.max(0, currentStock - result.baselineStock);
                result.actualQuantity = Math.min(
                        result.maximumQuantity,
                        Math.max(storedActual, stockActual)
                );
                return result;
            } catch (Throwable error) {
                Log.e(TAG, "读取存珠控制板状态失败", error);
                return null;
            }
        }
    }

    private void persistActual(String messageId, int actualQuantity) {
        ContentValues values = new ContentValues();
        values.put("actual_quantity", Math.max(0, actualQuantity));
        values.put("updated_at", System.currentTimeMillis());
        synchronized (DB_LOCK) {
            store.getWritableDatabase().update(
                    SESSION_TABLE,
                    values,
                    "id=1 AND message_id=? AND state='COLLECTING'",
                    new String[]{messageId}
            );
        }
    }

    private void persistEarlyStopFailure(SessionSnapshot session) {
        final SdkCommandDecoder.DecodedCommand decoded;
        try {
            decoded = decoder.decode(
                    session.sourceTopic,
                    session.commandEnvelope.getBytes(StandardCharsets.UTF_8),
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            Log.e(TAG, "恢复提前停止的存珠指令失败", error);
            occupancy.markBlocked("COLLECT_CONTROLLER_STATE_INVALID");
            return;
        }

        final SdkCommandDecoder.EncodedResult terminal;
        try {
            terminal = decoded.physicalTerminal(
                    session.messageId + "-result",
                    false,
                    session.actualQuantity,
                    "COLLECT_CONTROLLER_STOPPED_EARLY",
                    "controller collection stopped before requested quantity",
                    System.currentTimeMillis()
            );
        } catch (Throwable physicalMappingError) {
            try {
                SdkCommandDecoder.EncodedResult fallback = decoded.genericTerminal(
                        session.messageId + "-result",
                        false,
                        "COLLECT_CONTROLLER_STOPPED_EARLY",
                        "controller collection stopped before requested quantity",
                        System.currentTimeMillis()
                );
                terminal = new SdkCommandDecoder.EncodedResult(
                        fallback.sourceMessageId,
                        fallback.eventNo,
                        fallback.resultStatus,
                        addCollectionFields(
                                fallback.payload,
                                session.operationNo,
                                session.actualQuantity
                        )
                );
            } catch (Throwable fallbackError) {
                Log.e(TAG, "编码提前停止存珠终态失败", fallbackError);
                occupancy.markBlocked("COLLECT_TERMINAL_ENCODING_FAILED");
                return;
            }
        }

        String payload = addCollectionFields(
                terminal.payload,
                session.operationNo,
                session.actualQuantity
        );
        boolean stored = false;
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                ContentValues blocked = new ContentValues();
                blocked.put("state", "BLOCKED");
                blocked.put("actual_quantity", session.actualQuantity);
                blocked.put("blocked_reason", "COLLECT_CONTROLLER_STOPPED_EARLY");
                blocked.put("updated_at", System.currentTimeMillis());
                if (db.update(
                        SESSION_TABLE,
                        blocked,
                        "id=1 AND message_id=? AND state='COLLECTING'",
                        new String[]{session.messageId}
                ) != 1) {
                    return;
                }

                ContentValues command = new ContentValues();
                command.put("state", "blocked");
                command.put("updated_at", System.currentTimeMillis());
                if (db.update(
                        "commands",
                        command,
                        "message_id=?",
                        new String[]{session.messageId}
                ) != 1) {
                    return;
                }
                if (!saveOutbox(
                        db,
                        session.messageId,
                        terminal.eventNo,
                        terminal.resultStatus,
                        payload
                )) {
                    return;
                }
                db.setTransactionSuccessful();
                stored = true;
            } finally {
                db.endTransaction();
            }
        }
        if (!stored) {
            return;
        }

        occupancy.markBlocked("COLLECT_CONTROLLER_STOPPED_EARLY");
        MqttManager.get(context).reportCommandResult(payload);
        MqttManager.get(context).reportFault(
                "COLLECT_CONTROLLER_STOPPED_EARLY",
                "collection motor or sensor stopped before requested quantity",
                3,
                "operationNo=" + session.operationNo
                        + ", actualQuantity=" + session.actualQuantity
                        + ", maximumQuantity=" + session.maximumQuantity
        );
        Intent event = new Intent(AppConfig.ACTION_COLLECTION_EVENT);
        event.setPackage(context.getPackageName());
        event.putExtra(
                DeviceCommandManager.EXTRA_COLLECTION_EVENT,
                DeviceCommandManager.COLLECTION_FAILED
        );
        event.putExtra(
                DeviceCommandManager.EXTRA_COLLECTION_MESSAGE,
                "存珠电机或计数传感器提前停止，已进入人工处理"
        );
        context.sendBroadcast(event);
    }

    private void ensureSessionTableExists() {
        synchronized (DB_LOCK) {
            store.getWritableDatabase().execSQL(
                    "CREATE TABLE IF NOT EXISTS " + SESSION_TABLE + " ("
                            + "id INTEGER PRIMARY KEY CHECK(id=1),"
                            + "message_id TEXT NOT NULL UNIQUE,"
                            + "operation_no TEXT NOT NULL,"
                            + "source_topic TEXT NOT NULL,"
                            + "command_envelope TEXT NOT NULL,"
                            + "maximum_quantity INTEGER NOT NULL,"
                            + "timeout_seconds INTEGER NOT NULL,"
                            + "state TEXT NOT NULL,"
                            + "baseline_stock INTEGER NOT NULL DEFAULT -1,"
                            + "last_stock INTEGER NOT NULL DEFAULT -1,"
                            + "actual_quantity INTEGER NOT NULL DEFAULT 0,"
                            + "occupancy_session_id TEXT NOT NULL,"
                            + "blocked_reason TEXT NOT NULL DEFAULT '',"
                            + "created_at INTEGER NOT NULL,"
                            + "updated_at INTEGER NOT NULL)"
            );
        }
    }

    private static boolean saveOutbox(
            SQLiteDatabase db,
            String messageId,
            String eventNo,
            String status,
            String payload
    ) {
        String receiptKey = messageId + "|" + eventNo + "|" + status;
        ContentValues values = new ContentValues();
        values.put("receipt_key", receiptKey);
        values.put("kind", "command_result");
        values.put("source_message_id", messageId);
        values.put("event_no", eventNo);
        values.put("result_status", status);
        values.put("payload", payload);
        values.put("created_at", System.currentTimeMillis());
        long inserted = db.insertWithOnConflict(
                "outbox",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
        );
        if (inserted != -1L) {
            return true;
        }
        try (Cursor cursor = db.query(
                "outbox",
                new String[]{"payload"},
                "receipt_key=?",
                new String[]{receiptKey},
                null,
                null,
                null
        )) {
            return cursor.moveToFirst() && payload.equals(cursor.getString(0));
        }
    }

    private static String addCollectionFields(
            String payload,
            String operationNo,
            int actualQuantity
    ) {
        try {
            JSONObject json = new JSONObject(payload);
            json.put("operationNo", operationNo);
            json.put("actualQuantity", Math.max(0, actualQuantity));
            return json.toString();
        } catch (Throwable error) {
            return payload;
        }
    }

    private static final class SessionSnapshot {
        String messageId;
        String operationNo;
        String sourceTopic;
        String commandEnvelope;
        int maximumQuantity;
        int baselineStock;
        int actualQuantity;
        String occupancySessionId;
    }
}
