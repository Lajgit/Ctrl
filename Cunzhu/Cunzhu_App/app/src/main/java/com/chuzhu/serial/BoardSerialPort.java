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
    private static volatile BoardSerialPort instance;

    private final Context context;
    private final Object writeLock = new Object();
    private final BoardFrameCodec codec = new BoardFrameCodec();
    private BoardSerialConfig config = BoardSerialConfig.defaultConfig();
    private FileInputStream inputStream;
    private FileOutputStream outputStream;
    private Thread readThread;
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
        byte[] buffer = new byte[512];
        while (running) {
            try {
                int count = inputStream.read(buffer);
                if (count < 0) {
                    throw new IOException("串口输入流已关闭");
                }
                BoardEvent event = codec.decode(buffer, count);
                if (event != null) {
                    BoardEventListener current = listener;
                    if (current != null) {
                        current.onBoardEvent(event);
                    }
                    broadcastBoardEvent(event);
                }
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
