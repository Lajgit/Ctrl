package com.chuzhu.serial;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.chuzhu.AppConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * ttyS5 控制板串口封装。
 */
public final class BoardSerialPort {

    private static final String TAG = "CunzhuSerial";
    private static final int READ_BUFFER_SIZE = 512;
    private static final int RX_ACCUMULATOR_SIZE = 4096;
    private static volatile BoardSerialPort instance;

    private final Context context;
    private final Object writeLock = new Object();
    private final BoardFrameCodec codec = new BoardFrameCodec();
    private final byte[] receiveBuffer = new byte[RX_ACCUMULATOR_SIZE];
    private BoardSerialConfig config = BoardSerialConfig.defaultConfig();
    private FileInputStream inputStream;
    private FileOutputStream outputStream;
    private Thread readThread;
    private int receiveLength;
    private volatile boolean running;
    private volatile String lastError = "";
    private volatile BoardEventListener listener;

    private BoardSerialPort(Context context) {
        this.context = context.getApplicationContext();
    }

    public static BoardSerialPort get(Context context) {
        if (instance == null) {
            synchronized (BoardSerialPort.class) {
                if (instance == null) {
                    instance = new BoardSerialPort(context);
                }
            }
        }
        return instance;
    }

    public synchronized void setListener(BoardEventListener listener) {
        this.listener = listener;
    }

    public synchronized boolean open() {
        if (isOpen()) {
            return true;
        }
        try {
            configureSerialDevice();
            File device = new File(config.devicePath);
            inputStream = new FileInputStream(device);
            outputStream = new FileOutputStream(device);
            receiveLength = 0;
            running = true;
            lastError = "";
            readThread = new Thread(this::readLoop, "存珠机ttyS5读线程");
            readThread.start();
            broadcastSerialState("串口已打开");
            return true;
        } catch (Throwable error) {
            lastError = "打开串口失败：" + messageOf(error);
            Log.e(TAG, lastError, error);
            close();
            broadcastSerialState(lastError);
            BoardEventListener current = listener;
            if (current != null) {
                current.onSerialError(lastError, error);
            }
            return false;
        }
    }

    public synchronized void close() {
        running = false;
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
        receiveLength = 0;
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        broadcastSerialState("串口已关闭");
    }

    public boolean isOpen() {
        return running && inputStream != null && outputStream != null;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean write(byte[] data) {
        if (data == null || data.length == 0) {
            return true;
        }
        synchronized (writeLock) {
            if (!isOpen()) {
                lastError = "串口未打开，无法写入";
                return false;
            }
            try {
                outputStream.write(data);
                outputStream.flush();
                return true;
            } catch (Throwable error) {
                lastError = "串口写入失败：" + messageOf(error);
                Log.e(TAG, lastError, error);
                BoardEventListener current = listener;
                if (current != null) {
                    current.onSerialError(lastError, error);
                }
                return false;
            }
        }
    }

    public BoardFrameCodec getCodec() {
        return codec;
    }

    private void readLoop() {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        while (running) {
            try {
                int count = inputStream.read(buffer);
                if (count < 0) {
                    throw new IOException("串口输入流已关闭");
                }
                appendAndDispatch(buffer, count);
            } catch (Throwable error) {
                if (running) {
                    lastError = "串口读取异常：" + messageOf(error);
                    Log.e(TAG, lastError, error);
                    broadcastSerialState(lastError);
                    BoardEventListener current = listener;
                    if (current != null) {
                        current.onSerialError(lastError, error);
                    }
                }
                break;
            }
        }
    }

    private void appendAndDispatch(byte[] data, int count) {
        if (data == null || count <= 0) {
            return;
        }
        if (count > receiveBuffer.length) {
            /* 极端异常时只保留本次读取尾部，避免越界；正常 ttyS5 每次读取远小于该缓存。 */
            int start = count - receiveBuffer.length;
            System.arraycopy(data, start, receiveBuffer, 0, receiveBuffer.length);
            receiveLength = receiveBuffer.length;
        } else {
            if (receiveLength + count > receiveBuffer.length) {
                /*
                 * Linux 串口 read() 不保证按 14 字节帧返回。缓存溢出说明前面长期没有合法帧头，
                 * 丢弃旧噪声后继续接收，不能让一次异常数据永久堵死后续协议解析。
                 */
                receiveLength = 0;
            }
            System.arraycopy(data, 0, receiveBuffer, receiveLength, count);
            receiveLength += count;
        }
        processReceiveBuffer();
    }

    private void processReceiveBuffer() {
        int offset = 0;
        while (receiveLength - offset >= BoardFrameCodec.FRAME_LENGTH) {
            while (offset < receiveLength
                    && (receiveBuffer[offset] & 0xFF) != BoardFrameCodec.HEAD) {
                offset++;
            }
            if (receiveLength - offset < BoardFrameCodec.FRAME_LENGTH) {
                break;
            }
            int tailIndex = offset + BoardFrameCodec.FRAME_LENGTH - 1;
            if ((receiveBuffer[tailIndex] & 0xFF) != BoardFrameCodec.TAIL) {
                offset++;
                continue;
            }

            byte[] frame = new byte[BoardFrameCodec.FRAME_LENGTH];
            System.arraycopy(
                    receiveBuffer,
                    offset,
                    frame,
                    0,
                    BoardFrameCodec.FRAME_LENGTH
            );
            dispatchBoardEvent(codec.decode(frame, frame.length));
            offset += BoardFrameCodec.FRAME_LENGTH;
        }

        if (offset > 0) {
            int remaining = receiveLength - offset;
            if (remaining > 0) {
                System.arraycopy(receiveBuffer, offset, receiveBuffer, 0, remaining);
            }
            receiveLength = remaining;
        }
    }

    private void dispatchBoardEvent(BoardEvent event) {
        if (event == null) {
            return;
        }
        BoardEventListener current = listener;
        if (current != null) {
            current.onBoardEvent(event);
        }
        broadcastBoardEvent(event);
    }

    private void configureSerialDevice() throws Exception {
        String sttyCommand = "toybox stty -F " + config.devicePath
                + " " + config.baudRate
                + " cs8 -cstopb -parenb -crtscts raw -echo";
        String rootCommand = "chmod 666 " + config.devicePath + " && " + sttyCommand;
        int exitCode = executeShell(new String[]{"su", "0", "sh", "-c", rootCommand});
        if (exitCode != 0) {
            exitCode = executeShell(new String[]{"sh", "-c", sttyCommand});
        }
        if (exitCode != 0) {
            String fallback = "stty -F " + config.devicePath
                    + " " + config.baudRate
                    + " cs8 -cstopb -parenb -crtscts raw -echo";
            exitCode = executeShell(new String[]{"sh", "-c", fallback});
        }
        if (exitCode != 0) {
            throw new IOException("配置 ttyS5 失败，退出码=" + exitCode);
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

    private void broadcastBoardEvent(BoardEvent event) {
        Intent intent = new Intent(AppConfig.ACTION_BOARD_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra("type", event.type);
        intent.putExtra("actualQuantity", event.actualQuantity);
        intent.putExtra("errorCode", event.errorCode);
        intent.putExtra("errorMessage", event.errorMessage);
        intent.putExtra("raw", event.raw);
        context.sendBroadcast(intent);
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
