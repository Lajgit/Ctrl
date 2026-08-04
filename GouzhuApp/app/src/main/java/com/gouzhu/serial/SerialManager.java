package com.gouzhu.serial;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.gouzhu.AppConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * ttyS5 串口、购珠机 14 字节协议和控制板 Bootloader 升级协议管理器。
 *
 * <p>同一个串口只由本类打开。正常业务和固件升级通过模式切换共享同一读线程，
 * 避免两个模块同时读取 ttyS5 导致拆包和丢包。</p>
 */
public final class SerialManager {

    private static final String TAG = "GouzhuSerial";
    private static volatile SerialManager instance;

    private static final int NORMAL_FRAME_LENGTH = 14;
    private static final int BOOT_SOF_1 = 0xAA;
    private static final int BOOT_SOF_2 = 0x5A;
    private static final int BOOT_PROTOCOL_VERSION = 0x01;
    private static final int BOOT_MAX_DATA_SIZE = 1024;
    private static final int BOOT_TARGET_MAGIC = 0x41544F42;

    private static final int BOOT_CMD_HELLO = 0x01;
    private static final int BOOT_CMD_BEGIN = 0x02;
    private static final int BOOT_CMD_DATA = 0x03;
    private static final int BOOT_CMD_END = 0x04;
    private static final int BOOT_CMD_INSTALL = 0x05;
    private static final int BOOT_CMD_ABORT = 0x07;
    private static final int BOOT_CMD_ACK = 0x80;
    private static final int BOOT_CMD_NACK = 0x81;

    private final Context context;
    private final Object writeLock = new Object();
    private final Object upgradeLock = new Object();
    private final AtomicInteger messageId = new AtomicInteger(0);
    private final AtomicInteger bootSequence = new AtomicInteger(1);
    private final LinkedBlockingQueue<Byte> bootReceiveQueue =
            new LinkedBlockingQueue<>();

    private volatile boolean running;
    private volatile boolean bootMode;
    private volatile byte[] pendingEcho;
    private volatile CountDownLatch pendingEchoLatch;
    private volatile CountDownLatch boardVersionLatch;
    private volatile long boardVersionValue;

    private FileInputStream inputStream;
    private FileOutputStream outputStream;
    private Thread readThread;

    private final byte[] normalFrameBuffer = new byte[NORMAL_FRAME_LENGTH];
    private int normalFrameIndex;

    private SerialManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static SerialManager get(Context context) {
        if (instance == null) {
            synchronized (SerialManager.class) {
                if (instance == null) {
                    instance = new SerialManager(context);
                }
            }
        }
        return instance;
    }

    /** 打开 ttyS5 并启动唯一读线程。 */
    public synchronized boolean open() {
        if (isOpen()) {
            return true;
        }

        try {
            configureSerialDevice();
            File device = new File(AppConfig.SERIAL_DEVICE);
            inputStream = new FileInputStream(device);
            outputStream = new FileOutputStream(device);
            running = true;
            bootMode = false;
            readThread = new Thread(this::readLoop, "购珠机-串口接收");
            readThread.start();
            broadcastSerialState("控制板串口已连接");
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "打开串口失败：" + AppConfig.SERIAL_DEVICE, error);
            close();
            broadcastSerialState("控制板串口连接失败");
            return false;
        }
    }

    public synchronized void close() {
        running = false;
        bootMode = false;
        bootReceiveQueue.clear();

        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (Throwable ignored) {
        }

        inputStream = null;
        outputStream = null;
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
    }

    public boolean isOpen() {
        return running && inputStream != null && outputStream != null;
    }

    /**
     * 发送普通购珠机命令。
     *
     * @param code2 功能码
     * @param data  Data1:Data4 大端整数
     * @param requireEcho 是否要求控制板原样应答
     */
    public boolean sendCommand(int code2, long data, boolean requireEcho) {
        if (!isOpen() || bootMode) {
            return false;
        }

        byte[] frame = buildNormalFrame(code2, data, requireEcho);
        return writeRaw(frame);
    }

    public boolean sendCommandAndWaitEcho(
            int code2,
            long data,
            long timeoutMs
    ) throws InterruptedException {
        if (!isOpen() || bootMode) {
            return false;
        }
        byte[] frame = buildNormalFrame(code2, data, true);
        CountDownLatch latch = new CountDownLatch(1);
        pendingEcho = frame;
        pendingEchoLatch = latch;

        if (!writeRaw(frame)) {
            pendingEcho = null;
            pendingEchoLatch = null;
            return false;
        }

        boolean confirmed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        pendingEcho = null;
        pendingEchoLatch = null;
        return confirmed;
    }

    /**
     * 通过当前 Bootloader 协议升级控制板 bin。
     */
    public void upgradeBoard(File binFile, int versionCode, UpgradeCallback callback) {
        new Thread(() -> {
            synchronized (upgradeLock) {
                performBoardUpgrade(binFile, versionCode, callback);
            }
        }, "购珠机-控制板升级").start();
    }

    private void performBoardUpgrade(
            File binFile,
            int versionCode,
            UpgradeCallback callback
    ) {
        try {
            if (binFile == null || !binFile.isFile() || binFile.length() <= 0L) {
                throw new IOException("控制板固件文件不存在或为空");
            }
            if (!open()) {
                throw new IOException("控制板串口未打开");
            }

            callback.onProgress(1, "正在通知控制板进入Bootloader");
            if (!sendCommandAndWaitEcho(0xF0, 0x424F5441L, 2500L)) {
                throw new IOException("控制板未确认进入Bootloader命令");
            }

            bootMode = true;
            normalFrameIndex = 0;
            bootReceiveQueue.clear();
            Thread.sleep(1200L);

            BootReply hello = sendBootRequest(BOOT_CMD_HELLO, new byte[0], 3000L);
            if (!hello.isOk()) {
                throw hello.toException("Bootloader握手失败");
            }

            byte[] image = readAllBytes(binFile);
            int imageCrc32 = calculateCrc32(image);
            byte[] beginPayload = new byte[16];
            writeU32Le(beginPayload, 0, BOOT_TARGET_MAGIC);
            writeU32Le(beginPayload, 4, versionCode);
            writeU32Le(beginPayload, 8, image.length);
            writeU32Le(beginPayload, 12, imageCrc32);

            callback.onProgress(3, "正在擦除控制板升级缓存");
            BootReply begin = sendBootRequest(BOOT_CMD_BEGIN, beginPayload, 15000L);
            if (!begin.isOk()) {
                throw begin.toException("控制板拒绝开始升级");
            }

            int offset = 0;
            while (offset < image.length) {
                int length = Math.min(BOOT_MAX_DATA_SIZE, image.length - offset);
                byte[] payload = new byte[6 + length];
                writeU32Le(payload, 0, offset);
                writeU16Le(payload, 4, length);
                System.arraycopy(image, offset, payload, 6, length);

                BootReply dataReply = sendBootRequest(BOOT_CMD_DATA, payload, 5000L);
                if (!dataReply.isOk()) {
                    throw dataReply.toException("控制板写入固件失败");
                }

                int nextOffset = dataReply.value;
                if (nextOffset <= offset || nextOffset > image.length) {
                    throw new IOException("控制板返回的升级偏移无效：" + nextOffset);
                }
                offset = nextOffset;
                int progress = 5 + (int) ((long) offset * 85L / image.length);
                callback.onProgress(progress, "正在传输控制板固件");
            }

            callback.onProgress(92, "正在校验控制板固件");
            BootReply end = sendBootRequest(BOOT_CMD_END, new byte[0], 10000L);
            if (!end.isOk()) {
                throw end.toException("控制板固件校验失败");
            }

            callback.onProgress(96, "正在安装控制板固件");
            BootReply install = sendBootRequest(BOOT_CMD_INSTALL, new byte[0], 20000L);
            if (!install.isOk()) {
                throw install.toException("控制板固件安装失败");
            }

            if (install.value != versionCode) {
                throw new IOException(
                        "控制板安装版本不匹配，期望="
                                + Integer.toUnsignedString(versionCode)
                                + "，实际="
                                + Integer.toUnsignedString(install.value)
                );
            }

            bootMode = false;
            normalFrameIndex = 0;
            bootReceiveQueue.clear();

            callback.onProgress(98, "正在等待控制板重启并确认版本");
            Thread.sleep(3000L);

            CountDownLatch versionLatch = new CountDownLatch(1);
            boardVersionValue = -1L;
            boardVersionLatch = versionLatch;
            if (!sendCommandAndWaitEcho(0x00, 0L, 2500L)) {
                throw new IOException("控制板重启后未确认版本查询命令");
            }
            if (!versionLatch.await(5000L, TimeUnit.MILLISECONDS)) {
                throw new IOException("控制板重启后未上报固件版本");
            }
            if ((int) boardVersionValue != versionCode) {
                throw new IOException(
                        "控制板重启后版本不匹配，期望="
                                + Integer.toUnsignedString(versionCode)
                                + "，实际="
                                + Long.toUnsignedString(boardVersionValue)
                );
            }

            callback.onProgress(100, "控制板升级完成");
            callback.onSuccess((int) boardVersionValue);
        } catch (Throwable error) {
            Log.e(TAG, "控制板升级失败", error);
            try {
                if (bootMode) {
                    sendBootRequest(BOOT_CMD_ABORT, new byte[0], 1000L);
                }
            } catch (Throwable ignored) {
            }
            callback.onFailure(error);
        } finally {
            bootMode = false;
            bootReceiveQueue.clear();
            pendingEcho = null;
            boardVersionLatch = null;
            CountDownLatch latch = pendingEchoLatch;
            pendingEchoLatch = null;
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    private byte[] buildNormalFrame(int code2, long data, boolean requireEcho) {
        byte[] frame = new byte[NORMAL_FRAME_LENGTH];
        frame[0] = (byte) 0xAA;
        frame[1] = 0;
        frame[2] = (byte) (messageId.getAndIncrement() & 0xFF);
        frame[3] = 0x01;
        frame[4] = (byte) code2;
        frame[5] = (byte) ((data >>> 24) & 0xFF);
        frame[6] = (byte) ((data >>> 16) & 0xFF);
        frame[7] = (byte) ((data >>> 8) & 0xFF);
        frame[8] = (byte) (data & 0xFF);
        frame[9] = (byte) (requireEcho ? 0x01 : 0x00);
        frame[10] = 0;

        int crc = calculateCrc16(frame, 0, 11);
        frame[11] = (byte) ((crc >>> 8) & 0xFF);
        frame[12] = (byte) (crc & 0xFF);
        frame[13] = 0x55;
        return frame;
    }

    private void readLoop() {
        byte[] buffer = new byte[512];
        while (running) {
            try {
                int count = inputStream.read(buffer);
                if (count < 0) {
                    throw new IOException("串口输入流已关闭");
                }

                for (int index = 0; index < count; index++) {
                    byte value = buffer[index];
                    if (bootMode) {
                        bootReceiveQueue.offer(value);
                    } else {
                        consumeNormalByte(value);
                    }
                }
            } catch (Throwable error) {
                if (running) {
                    Log.e(TAG, "串口读取失败", error);
                    broadcastSerialState("控制板串口读取异常");
                }
                break;
            }
        }
    }

    private void consumeNormalByte(byte value) {
        if (normalFrameIndex == 0 && (value & 0xFF) != 0xAA) {
            return;
        }

        normalFrameBuffer[normalFrameIndex++] = value;
        if (normalFrameIndex < NORMAL_FRAME_LENGTH) {
            return;
        }

        byte[] frame = Arrays.copyOf(normalFrameBuffer, NORMAL_FRAME_LENGTH);
        normalFrameIndex = 0;

        if (!isValidNormalFrame(frame)) {
            return;
        }
        handleNormalFrame(frame);
    }

    private boolean isValidNormalFrame(byte[] frame) {
        if (frame.length != NORMAL_FRAME_LENGTH
                || (frame[0] & 0xFF) != 0xAA
                || (frame[13] & 0xFF) != 0x55) {
            return false;
        }

        int expected = ((frame[11] & 0xFF) << 8) | (frame[12] & 0xFF);
        int actual = calculateCrc16(frame, 0, 11);
        return expected == actual;
    }

    private void handleNormalFrame(byte[] frame) {
        byte[] echo = pendingEcho;
        CountDownLatch echoLatch = pendingEchoLatch;
        if (echo != null && echoLatch != null && Arrays.equals(echo, frame)) {
            echoLatch.countDown();
            return;
        }

        int direction = frame[3] & 0xFF;
        if (direction != 0x00) {
            return;
        }

        // 主板要求确认时，安卓按协议原样回传该帧。
        if ((frame[9] & 0xFF) == 0x01) {
            writeRaw(frame);
        }

        long data = ((long) (frame[5] & 0xFF) << 24)
                | ((long) (frame[6] & 0xFF) << 16)
                | ((long) (frame[7] & 0xFF) << 8)
                | (long) (frame[8] & 0xFF);

        if ((frame[4] & 0xFF) == 0x00) {
            CountDownLatch versionLatch = boardVersionLatch;
            if (versionLatch != null) {
                boardVersionValue = data;
                versionLatch.countDown();
            }
        }

        Intent intent = new Intent(AppConfig.ACTION_BOARD_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra("frameId", frame[2] & 0xFF);
        intent.putExtra("code2", frame[4] & 0xFF);
        intent.putExtra("data", data);
        intent.putExtra("expandCode", frame[10] & 0xFF);
        intent.putExtra("raw", frame);
        context.sendBroadcast(intent);
    }

    private BootReply sendBootRequest(
            int command,
            byte[] payload,
            long timeoutMs
    ) throws Exception {
        int sequence = bootSequence.getAndIncrement() & 0xFFFF;
        byte[] frame = buildBootFrame(command, sequence, payload);
        Throwable lastError = null;

        for (int retry = 0; retry < 3; retry++) {
            bootReceiveQueue.clear();
            if (!writeRaw(frame)) {
                lastError = new IOException("Bootloader数据发送失败");
                continue;
            }

            try {
                BootReply reply = readBootReply(timeoutMs);
                if (reply.sequence != sequence) {
                    lastError = new IOException(
                            "Bootloader应答序号不匹配，期望=" + sequence
                                    + "，实际=" + reply.sequence
                    );
                    continue;
                }
                if (reply.requestCommand != command) {
                    lastError = new IOException(
                            "Bootloader应答命令不匹配，期望=" + command
                                    + "，实际=" + reply.requestCommand
                    );
                    continue;
                }
                return reply;
            } catch (Throwable error) {
                lastError = error;
            }
        }

        if (lastError instanceof Exception) {
            throw (Exception) lastError;
        }
        throw new IOException("Bootloader命令无应答");
    }

    private byte[] buildBootFrame(int command, int sequence, byte[] payload) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        ByteArrayOutputStream output = new ByteArrayOutputStream(12 + safePayload.length);
        output.write(BOOT_SOF_1);
        output.write(BOOT_SOF_2);
        output.write(BOOT_PROTOCOL_VERSION);
        output.write(command & 0xFF);
        writeU16Le(output, sequence);
        writeU16Le(output, safePayload.length);
        output.write(safePayload, 0, safePayload.length);

        byte[] withoutCrc = output.toByteArray();
        int crc = calculateCrc32(withoutCrc, 2, withoutCrc.length - 2);
        writeU32Le(output, crc);
        return output.toByteArray();
    }

    private BootReply readBootReply(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;

        int first;
        while ((first = pollBootByte(deadline)) != BOOT_SOF_1) {
            // 丢弃帧头前的残留字节。
        }
        int second = pollBootByte(deadline);
        if (second != BOOT_SOF_2) {
            throw new IOException("Bootloader帧头错误");
        }

        byte[] header = new byte[6];
        readBootBytes(header, deadline);
        int version = header[0] & 0xFF;
        int command = header[1] & 0xFF;
        int sequence = readU16Le(header, 2);
        int payloadLength = readU16Le(header, 4);

        if (version != BOOT_PROTOCOL_VERSION || payloadLength < 0 || payloadLength > 2048) {
            throw new IOException("Bootloader应答头无效");
        }

        byte[] payload = new byte[payloadLength];
        readBootBytes(payload, deadline);
        byte[] crcBytes = new byte[4];
        readBootBytes(crcBytes, deadline);
        int receivedCrc = readU32Le(crcBytes, 0);

        ByteArrayOutputStream crcSource = new ByteArrayOutputStream(6 + payloadLength);
        crcSource.write(header, 0, header.length);
        crcSource.write(payload, 0, payload.length);
        byte[] crcData = crcSource.toByteArray();
        int actualCrc = calculateCrc32(crcData, 0, crcData.length);
        if (receivedCrc != actualCrc) {
            throw new IOException("Bootloader应答CRC32错误");
        }

        if (command != BOOT_CMD_ACK && command != BOOT_CMD_NACK) {
            throw new IOException("Bootloader返回了未知应答命令：" + command);
        }
        if (payload.length < 10) {
            throw new IOException("Bootloader应答负载长度不足");
        }

        BootReply reply = new BootReply();
        reply.sequence = sequence;
        reply.ackCommand = command;
        reply.requestCommand = payload[0] & 0xFF;
        reply.result = payload[1] & 0xFF;
        reply.state = payload[2] & 0xFF;
        reply.value = readU32Le(payload, 4);
        reply.maxDataSize = readU16Le(payload, 8);
        return reply;
    }

    private int pollBootByte(long deadline) throws Exception {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0L) {
            throw new IOException("等待Bootloader应答超时");
        }

        Byte value = bootReceiveQueue.poll(remaining, TimeUnit.MILLISECONDS);
        if (value == null) {
            throw new IOException("等待Bootloader应答超时");
        }
        return value & 0xFF;
    }

    private void readBootBytes(byte[] target, long deadline) throws Exception {
        for (int index = 0; index < target.length; index++) {
            target[index] = (byte) pollBootByte(deadline);
        }
    }

    private boolean writeRaw(byte[] data) {
        synchronized (writeLock) {
            if (outputStream == null) {
                return false;
            }

            try {
                outputStream.write(data);
                outputStream.flush();
                return true;
            } catch (Throwable error) {
                Log.e(TAG, "串口发送失败", error);
                return false;
            }
        }
    }

    private void configureSerialDevice() throws Exception {
        String sttyCommand = "toybox stty -F " + AppConfig.SERIAL_DEVICE
                + " " + AppConfig.SERIAL_BAUD_RATE
                + " cs8 -cstopb -parenb -crtscts raw -echo";
        String rootCommand = "chmod 666 " + AppConfig.SERIAL_DEVICE
                + " && " + sttyCommand;

        int exitCode = executeShell(new String[]{"su", "0", "sh", "-c", rootCommand});
        if (exitCode != 0) {
            // 量产镜像可通过 ueventd 和 SELinux 预先授权，此时不需要 App 修改权限。
            exitCode = executeShell(new String[]{"sh", "-c", sttyCommand});
        }
        if (exitCode != 0) {
            String fallback = "stty -F " + AppConfig.SERIAL_DEVICE
                    + " " + AppConfig.SERIAL_BAUD_RATE
                    + " cs8 -cstopb -parenb -crtscts raw -echo";
            exitCode = executeShell(new String[]{"sh", "-c", fallback});
        }
        if (exitCode != 0) {
            throw new IOException("配置ttyS5失败，退出码=" + exitCode);
        }
    }

    private int executeShell(String[] command) throws Exception {
        Process process = Runtime.getRuntime().exec(command);
        try {
            return process.waitFor();
        } finally {
            process.destroy();
        }
    }

    private void broadcastSerialState(String state) {
        Intent intent = new Intent(AppConfig.ACTION_SERVICE_STATUS);
        intent.setPackage(context.getPackageName());
        intent.putExtra("key", "serial");
        intent.putExtra("value", state);
        context.sendBroadcast(intent);
    }

    private static byte[] readAllBytes(File file) throws IOException {
        if (file.length() > Integer.MAX_VALUE) {
            throw new IOException("控制板固件文件过大");
        }

        byte[] result = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < result.length) {
                int count = input.read(result, offset, result.length - offset);
                if (count < 0) {
                    throw new IOException("控制板固件读取不完整");
                }
                offset += count;
            }
        }
        return result;
    }

    private static int calculateCrc16(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int index = 0; index < length; index++) {
            crc ^= data[offset + index] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private static int calculateCrc32(byte[] data) {
        return calculateCrc32(data, 0, data.length);
    }

    private static int calculateCrc32(byte[] data, int offset, int length) {
        CRC32 crc32 = new CRC32();
        crc32.update(data, offset, length);
        return (int) crc32.getValue();
    }

    private static void writeU16Le(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private static void writeU32Le(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void writeU16Le(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }

    private static void writeU32Le(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private static int readU16Le(byte[] source, int offset) {
        return (source[offset] & 0xFF)
                | ((source[offset + 1] & 0xFF) << 8);
    }

    private static int readU32Le(byte[] source, int offset) {
        return (source[offset] & 0xFF)
                | ((source[offset + 1] & 0xFF) << 8)
                | ((source[offset + 2] & 0xFF) << 16)
                | ((source[offset + 3] & 0xFF) << 24);
    }

    public interface UpgradeCallback {
        void onProgress(int progress, String message);

        void onSuccess(int installedVersionCode);

        void onFailure(Throwable error);
    }

    private static final class BootReply {
        int sequence;
        int ackCommand;
        int requestCommand;
        int result;
        int state;
        int value;
        int maxDataSize;

        boolean isOk() {
            return ackCommand == BOOT_CMD_ACK && result == 0;
        }

        IOException toException(String prefix) {
            return new IOException(
                    prefix
                            + "，结果码=0x" + Integer.toHexString(result)
                            + "，状态=" + state
                            + "，value=" + Integer.toUnsignedString(value)
            );
        }
    }
}
