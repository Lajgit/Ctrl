package com.gouzhu.scanner;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.mqtt.MqttManager;
import com.gouzhu.payment.AuthCodePaymentManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * ttyS6 反扫模块管理器。
 *
 * <p>串口由本类独占打开，按 CR、LF、ETX 或字节空闲间隔切分扫码帧。付款码只在
 * 用户已明确进入 AUTH_CODE 支付会话时交给支付管理器；其他业务码仍交给
 * {@link ScannerBusinessRouter}，按最近一次 bootstrap 的 redemptionRouting 精确匹配。</p>
 *
 * <p>反扫读取、付款码支付和核销接口都不能直接驱动控制板；真实出珠只能执行平台
 * 下发并通过 SDK 校验的 MQTT dispense_marbles 指令。</p>
 */
public final class ReverseScannerManager {

    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_CODE_TYPE = "codeType";
    public static final String EXTRA_MASKED_CODE = "maskedCode";

    public static final String EVENT_CONNECTED = "connected";
    public static final String EVENT_DISCONNECTED = "disconnected";
    public static final String EVENT_SCAN_ACCEPTED = "scanAccepted";
    public static final String EVENT_SCAN_UNSUPPORTED = "scanUnsupported";
    public static final String EVENT_ERROR = "error";

    public static final String TYPE_INTERNAL_REDEMPTION = "internalRedemption";
    public static final String TYPE_MEMBER_WITHDRAWAL = "memberWithdrawal";
    public static final String TYPE_THIRD_PARTY_REDEMPTION = "thirdPartyRedemption";
    public static final String TYPE_PAYMENT_AUTH_CODE = "paymentAuthCode";
    public static final String TYPE_UNSUPPORTED = "unsupported";

    private static final String TAG = "GouzhuReverseScanner";
    private static final int MAX_SCAN_BYTES = 2048;
    private static final int MIN_SCAN_CHARACTERS = 4;
    private static final long IDLE_FRAME_DELAY_MS = 180L;
    private static final long DUPLICATE_WINDOW_MS = 1500L;
    private static final long NOISE_LOG_INTERVAL_MS = 60_000L;
    private static final long FAULT_REPORT_INTERVAL_MS = 60_000L;

    private static volatile ReverseScannerManager instance;

    private final Context context;
    private final Object bufferLock = new Object();
    private final ByteArrayOutputStream scanBuffer = new ByteArrayOutputStream(128);

    private volatile boolean running;
    private volatile long lastByteAt;
    private volatile String lastScanFingerprint = "";
    private volatile long lastScanAt;
    private volatile long lastNoiseLogAt;
    private volatile int ignoredNoiseFrames;
    private volatile long lastFaultReportAt;

    private FileInputStream inputStream;
    private Thread readThread;
    private Thread idleFlushThread;

    private ReverseScannerManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static ReverseScannerManager get(Context context) {
        if (instance == null) {
            synchronized (ReverseScannerManager.class) {
                if (instance == null) {
                    instance = new ReverseScannerManager(context);
                }
            }
        }
        return instance;
    }

    /** 打开 ttyS6 并启动独立扫码读线程。 */
    public synchronized boolean open() {
        if (isOpen()) {
            return true;
        }

        closeInternal(false);
        try {
            configureSerialDevice();
            File device = new File(AppConfig.REVERSE_SCANNER_DEVICE);
            if (!device.exists()) {
                throw new IOException("反扫串口不存在：" + device.getAbsolutePath());
            }

            inputStream = new FileInputStream(device);
            running = true;
            resetBuffer();

            readThread = new Thread(this::readLoop, "购珠机-反扫串口接收");
            idleFlushThread = new Thread(this::idleFlushLoop, "购珠机-反扫分帧");
            readThread.start();
            idleFlushThread.start();

            broadcast(
                    EVENT_CONNECTED,
                    "反扫模块已连接：" + AppConfig.REVERSE_SCANNER_DEVICE
                            + "，" + AppConfig.REVERSE_SCANNER_BAUD_RATE + " 8N1",
                    "",
                    ""
            );
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "打开反扫模块失败", error);
            reportScannerFault("反扫模块连接失败：" + messageOf(error));
            closeInternal(false);
            broadcast(
                    EVENT_ERROR,
                    "反扫模块连接失败：" + messageOf(error),
                    "",
                    ""
            );
            return false;
        }
    }

    public synchronized void close() {
        boolean wasOpen = running || inputStream != null;
        closeInternal(false);
        if (wasOpen) {
            broadcast(
                    EVENT_DISCONNECTED,
                    "反扫模块已断开：" + AppConfig.REVERSE_SCANNER_DEVICE,
                    "",
                    ""
            );
        }
    }

    public boolean isOpen() {
        return running && inputStream != null;
    }

    private void closeInternal(boolean fromReadThread) {
        running = false;
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (Throwable ignored) {
        }
        inputStream = null;

        Thread current = Thread.currentThread();
        if (readThread != null && (!fromReadThread || readThread != current)) {
            readThread.interrupt();
        }
        if (idleFlushThread != null && idleFlushThread != current) {
            idleFlushThread.interrupt();
        }
        readThread = null;
        idleFlushThread = null;
        resetBuffer();
    }

    private void readLoop() {
        byte[] buffer = new byte[256];
        try {
            while (running) {
                FileInputStream stream = inputStream;
                if (stream == null) {
                    throw new IOException("反扫串口输入流不存在");
                }

                int count = stream.read(buffer);
                if (count < 0) {
                    throw new IOException("反扫串口输入流已关闭");
                }
                for (int index = 0; index < count; index++) {
                    consumeByte(buffer[index] & 0xFF);
                }
            }
        } catch (Throwable error) {
            if (running) {
                Log.e(TAG, "反扫串口读取失败", error);
                reportScannerFault("反扫串口读取失败：" + messageOf(error));
                broadcast(
                        EVENT_ERROR,
                        "反扫模块读取异常：" + messageOf(error),
                        "",
                        ""
                );
            }
        } finally {
            synchronized (this) {
                closeInternal(true);
            }
        }
    }

    private void idleFlushLoop() {
        while (running) {
            try {
                Thread.sleep(60L);
            } catch (InterruptedException ignored) {
                break;
            }

            long lastAt = lastByteAt;
            if (lastAt > 0L
                    && SystemClock.elapsedRealtime() - lastAt >= IDLE_FRAME_DELAY_MS) {
                flushPendingScan();
            }
        }
    }

    private void consumeByte(int value) {
        // STX 表示新帧开始。若前一帧没有正常结束，直接清空，避免把噪声拼进二维码。
        if (value == 0x02) {
            resetBuffer();
            return;
        }
        // ETX、CR、LF 均作为完整扫码帧结束符。
        if (value == 0x03 || value == '\r' || value == '\n') {
            flushPendingScan();
            return;
        }
        // NUL 和其他控制字符属于线路空闲、模块状态字节或噪声，不进入正文。
        if (value == 0x00 || value < 0x20) {
            return;
        }

        boolean overflow = false;
        synchronized (bufferLock) {
            if (scanBuffer.size() >= MAX_SCAN_BYTES) {
                scanBuffer.reset();
                lastByteAt = 0L;
                overflow = true;
            } else {
                scanBuffer.write(value);
                lastByteAt = SystemClock.elapsedRealtime();
            }
        }
        if (overflow) {
            reportScannerFault("反扫数据超过最大长度，已丢弃");
            broadcast(EVENT_ERROR, "反扫数据超过最大长度，已丢弃", "", "");
        }
    }

    private void flushPendingScan() {
        byte[] payload;
        synchronized (bufferLock) {
            if (scanBuffer.size() == 0) {
                lastByteAt = 0L;
                return;
            }
            payload = scanBuffer.toByteArray();
            scanBuffer.reset();
            lastByteAt = 0L;
        }
        handleScan(payload);
    }

    private void handleScan(byte[] payload) {
        String content = new String(payload, StandardCharsets.UTF_8)
                .replace("\u0000", "")
                .trim();
        if (content.isEmpty()) {
            return;
        }

        // 真正二维码不会只有一个可打印字符。模块空闲状态或串口毛刺形成的短帧静默丢弃。
        if (content.length() < MIN_SCAN_CHARACTERS) {
            recordNoiseFrame(content.length());
            return;
        }

        String fingerprint = fingerprint(content);
        long now = SystemClock.elapsedRealtime();
        if (fingerprint.equals(lastScanFingerprint)
                && now - lastScanAt < DUPLICATE_WINDOW_MS) {
            // 只对完整二维码做短时间幂等，不再为模块空闲单字节持续打印 Info 日志。
            Log.d(TAG, "忽略短时间重复二维码，长度=" + content.length());
            return;
        }
        lastScanFingerprint = fingerprint;
        lastScanAt = now;

        /*
         * 付款码仅在明确的 AUTH_CODE 会话内优先消费。完整付款码不进入本类原有的
         * 脱敏广播/核销元数据路径，避免敏感支付凭证被持久化或传播。
         */
        AuthCodePaymentManager.ScanSubmission paymentSubmission =
                AuthCodePaymentManager.get(context).handleScanIfArmed(content);
        if (paymentSubmission.handled) {
            broadcast(
                    paymentSubmission.accepted
                            ? EVENT_SCAN_ACCEPTED : EVENT_SCAN_UNSUPPORTED,
                    paymentSubmission.message,
                    TYPE_PAYMENT_AUTH_CODE,
                    ""
            );
            return;
        }

        String maskedCode = maskCode(content);
        ScannerBusinessRouter.Submission submission =
                ScannerBusinessRouter.get(context).submit(content);
        if (submission.accepted) {
            broadcast(
                    EVENT_SCAN_ACCEPTED,
                    submission.message + "，码值=" + maskedCode,
                    submission.codeType,
                    maskedCode
            );
            return;
        }
        if (submission.unsupported) {
            broadcast(
                    EVENT_SCAN_UNSUPPORTED,
                    submission.message + "；长度=" + content.length()
                            + "，尾号=" + maskedCode,
                    TYPE_UNSUPPORTED,
                    maskedCode
            );
            return;
        }
        broadcast(EVENT_ERROR, submission.message, "", maskedCode);
    }

    /** 线路空闲短帧只做低频 Debug 统计，避免污染正常联调日志。 */
    private void recordNoiseFrame(int length) {
        ignoredNoiseFrames++;
        long now = SystemClock.elapsedRealtime();
        if (now - lastNoiseLogAt < NOISE_LOG_INTERVAL_MS) {
            return;
        }
        Log.d(
                TAG,
                "反扫串口已静默过滤短帧噪声：count=" + ignoredNoiseFrames
                        + "，lastLength=" + length
        );
        ignoredNoiseFrames = 0;
        lastNoiseLogAt = now;
    }

    /** 使用不可逆摘要完成短时间去重，不在内存中长期保留完整业务码。 */
    private static String fingerprint(String content) {
        return content.length() + ":" + Integer.toHexString(content.hashCode());
    }

    /** 串口真正故障按统一设备故障协议上报，普通空闲噪声不作为故障。 */
    private void reportScannerFault(String description) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastFaultReportAt < FAULT_REPORT_INTERVAL_MS) {
            return;
        }
        lastFaultReportAt = now;
        MqttManager.get(context).reportFault(
                "SCANNER_ERROR",
                "扫码器异常",
                2,
                description
        );
    }

    private void configureSerialDevice() throws Exception {
        String device = AppConfig.REVERSE_SCANNER_DEVICE;
        int baudRate = AppConfig.REVERSE_SCANNER_BAUD_RATE;
        String sttyCommand = "toybox stty -F " + device
                + " " + baudRate
                + " cs8 -cstopb -parenb -crtscts raw -echo";
        String rootCommand = "chmod 666 " + device + " && " + sttyCommand;

        int exitCode = executeShell(new String[]{"su", "0", "sh", "-c", rootCommand});
        if (exitCode != 0) {
            // 量产系统若已在 ueventd/SELinux 中授权，可直接配置串口参数。
            exitCode = executeShell(new String[]{"sh", "-c", sttyCommand});
        }
        if (exitCode != 0) {
            String fallback = "stty -F " + device
                    + " " + baudRate
                    + " cs8 -cstopb -parenb -crtscts raw -echo";
            exitCode = executeShell(new String[]{"sh", "-c", fallback});
        }
        if (exitCode != 0) {
            throw new IOException("配置" + device + "失败，退出码=" + exitCode);
        }
    }

    private int executeShell(String[] command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        try (InputStream stream = process.getInputStream()) {
            byte[] discard = new byte[256];
            while (stream.read(discard) >= 0) {
                // 丢弃 stty/chmod 输出，避免子进程管道阻塞。
            }
        }
        return process.waitFor();
    }

    private void resetBuffer() {
        synchronized (bufferLock) {
            scanBuffer.reset();
            lastByteAt = 0L;
        }
    }

    private void broadcast(
            String event,
            String message,
            String codeType,
            String maskedCode
    ) {
        Intent intent = new Intent(AppConfig.ACTION_REVERSE_SCANNER_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_EVENT, event);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_CODE_TYPE, codeType);
        intent.putExtra(EXTRA_MASKED_CODE, maskedCode);
        context.sendBroadcast(intent);
    }

    private static String maskCode(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        int visible = Math.min(4, content.length());
        String suffix = content.substring(content.length() - visible);
        return "***" + suffix;
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
