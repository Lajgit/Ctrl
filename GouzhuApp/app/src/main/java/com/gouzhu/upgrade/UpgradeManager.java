package com.gouzhu.upgrade;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.gouzhu.mqtt.MqttManager;
import com.gouzhu.serial.SerialManager;
import com.gouzhu.util.DeviceUtil;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * GouzhuApp 自升级和控制板 bin 升级。
 *
 * <p>只接受 OTA_XLH3566 后台已有的 ota、ball 两类升级指令，明确删除 game 分支。</p>
 */
public final class UpgradeManager {

    private static final String TAG = "GouzhuUpgrade";
    private static volatile UpgradeManager instance;

    private static final String PREF = "upgrade_pending";
    private static final String KEY_BOARD_VERSION = "board_version";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build();

    private UpgradeManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static UpgradeManager get(Context context) {
        if (instance == null) {
            synchronized (UpgradeManager.class) {
                if (instance == null) {
                    instance = new UpgradeManager(context);
                }
            }
        }
        return instance;
    }

    /** 处理 MQTT command/upgrade 消息。 */
    public void handleMqttCommand(String payload) {
        executor.execute(() -> handleInternal(payload));
    }

    /** App 被替换或设备重启后补报自升级最终结果。 */
    public void resumePendingResult() {
        executor.execute(() -> {
            SharedPreferences preferences = preferences();
            String pendingType = preferences.getString("pending_type", "");
            String targetVersion = preferences.getString("pending_target_version", "");
            if (!"ota".equals(pendingType) || targetVersion == null || targetVersion.isEmpty()) {
                return;
            }

            String currentVersion = DeviceUtil.getAppVersion(context);
            if (!targetVersion.equals(currentVersion)) {
                return;
            }

            boolean reported = report(
                    preferences.getString("pending_message_id", ""),
                    "success",
                    100,
                    currentVersion,
                    targetVersion,
                    preferences.getLong("pending_record_id", 0L),
                    preferences.getLong("pending_task_id", 0L),
                    "ota",
                    null,
                    null
            );
            if (reported) {
                clearPendingInstall();
            }
        });
    }

    private void handleInternal(String payload) {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "已有升级任务正在执行");
            return;
        }

        UpgradeTask task = null;
        try {
            task = UpgradeTask.parse(payload);
            String validationError = task.validate();
            if (validationError != null) {
                reportTaskFailure(task, "PARAM_INVALID", validationError);
                return;
            }

            if (!"ota".equals(task.type) && !"ball".equals(task.type)) {
                reportTaskFailure(
                        task,
                        "UNSUPPORTED_TYPE",
                        "当前单应用仅支持 ota 自升级和 ball 控制板升级"
                );
                return;
            }

            String currentVersion = getCurrentVersion(task.type);
            if (task.version.equals(currentVersion)) {
                report(
                        task.messageId,
                        "skipped",
                        100,
                        currentVersion,
                        task.version,
                        task.recordId,
                        task.taskId,
                        task.type,
                        "ALREADY_LATEST",
                        "当前已是目标版本，无需升级"
                );
                return;
            }

            String lastMessageKey = "last_message_" + task.type;
            String lastMessage = preferences().getString(lastMessageKey, "");
            if (!task.messageId.isEmpty() && task.messageId.equals(lastMessage)) {
                report(
                        task.messageId,
                        "downloading",
                        1,
                        currentVersion,
                        task.version,
                        task.recordId,
                        task.taskId,
                        task.type,
                        "DUPLICATE_MESSAGE",
                        "重复升级指令，继续恢复或重试该任务"
                );
            }
            preferences().edit().putString(lastMessageKey, task.messageId).apply();

            if ("ota".equals(task.type)) {
                executeAppUpgrade(task, currentVersion);
            } else {
                executeBoardUpgrade(task, currentVersion);
            }
        } catch (Throwable error) {
            Log.e(TAG, "处理升级指令失败", error);
            if (task != null) {
                reportTaskFailure(task, "UPGRADE_EXCEPTION", messageOf(error));
            }
        } finally {
            running.set(false);
        }
    }

    private void executeAppUpgrade(UpgradeTask task, String currentVersion) throws Exception {
        File apk = download(
                task,
                "gouzhu_update.apk",
                progress -> report(
                        task.messageId,
                        "downloading",
                        progress,
                        currentVersion,
                        task.version,
                        task.recordId,
                        task.taskId,
                        "ota",
                        null,
                        null
                )
        );

        savePendingInstall(task);
        report(
                task.messageId,
                "installing",
                100,
                currentVersion,
                task.version,
                task.recordId,
                task.taskId,
                "ota",
                null,
                null
        );

        InstallResult installResult = installSelf(apk);
        if (!installResult.success) {
            clearPendingInstall();
            report(
                    task.messageId,
                    "failed",
                    100,
                    currentVersion,
                    task.version,
                    task.recordId,
                    task.taskId,
                    "ota",
                    installResult.errorCode,
                    installResult.errorMessage
            );
            return;
        }

        // pm install 成功后当前进程通常会被系统替换；最终 success 由新版本启动后补报。
        try {
            Thread.sleep(1500L);
            Intent launchIntent = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
            }
        } catch (Throwable error) {
            Log.w(TAG, "自升级后重新拉起应用失败，等待开机广播恢复", error);
        }
    }

    private void executeBoardUpgrade(UpgradeTask task, String currentVersion) throws Exception {
        File bin = download(
                task,
                "control_board.bin",
                progress -> report(
                        task.messageId,
                        "downloading",
                        Math.min(40, progress * 40 / 100),
                        currentVersion,
                        task.version,
                        task.recordId,
                        task.taskId,
                        "ball",
                        null,
                        null
                )
        );

        final Object monitor = new Object();
        final Throwable[] failure = new Throwable[1];
        final boolean[] completed = new boolean[1];

        SerialManager.get(context).upgradeBoard(
                bin,
                DeviceUtil.parseBoardVersionCode(task.version),
                new SerialManager.UpgradeCallback() {
                    @Override
                    public void onProgress(int progress, String message) {
                        int mappedProgress = 40 + progress * 60 / 100;
                        report(
                                task.messageId,
                                "installing",
                                mappedProgress,
                                currentVersion,
                                task.version,
                                task.recordId,
                                task.taskId,
                                "ball",
                                null,
                                null
                        );
                    }

                    @Override
                    public void onSuccess(int installedVersionCode) {
                        preferences().edit()
                                .putString(KEY_BOARD_VERSION, task.version)
                                .apply();
                        report(
                                task.messageId,
                                "success",
                                100,
                                task.version,
                                task.version,
                                task.recordId,
                                task.taskId,
                                "ball",
                                null,
                                null
                        );
                        synchronized (monitor) {
                            completed[0] = true;
                            monitor.notifyAll();
                        }
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        failure[0] = error;
                        report(
                                task.messageId,
                                "failed",
                                40,
                                currentVersion,
                                task.version,
                                task.recordId,
                                task.taskId,
                                "ball",
                                "BOARD_UPGRADE_FAIL",
                                messageOf(error)
                        );
                        synchronized (monitor) {
                            completed[0] = true;
                            monitor.notifyAll();
                        }
                    }
                }
        );

        synchronized (monitor) {
            long deadline = System.currentTimeMillis() + 15L * 60L * 1000L;
            while (!completed[0] && System.currentTimeMillis() < deadline) {
                monitor.wait(1000L);
            }
        }

        if (!completed[0]) {
            throw new IllegalStateException("控制板升级等待超时");
        }
        if (failure[0] != null) {
            // 失败结果已在串口升级回调中上报，避免外层再次重复上报。
            return;
        }
    }

    private File download(
            UpgradeTask task,
            String finalName,
            ProgressCallback progressCallback
    ) throws Exception {
        File directory = context.getExternalFilesDir("upgrade");
        if (directory == null) {
            throw new IllegalStateException("无法获取升级目录");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建升级目录");
        }

        File finalFile = new File(directory, finalName);
        File temporaryFile = new File(directory, finalName + ".part");
        String identityKey = "download_identity_" + task.type;
        String identity = task.version + "\n" + task.url + "\n"
                + task.md5.toLowerCase(Locale.ROOT);
        String oldIdentity = preferences().getString(identityKey, "");
        if (!identity.equals(oldIdentity) && temporaryFile.exists()) {
            temporaryFile.delete();
        }
        preferences().edit().putString(identityKey, identity).apply();

        long existingLength = temporaryFile.exists() ? temporaryFile.length() : 0L;
        Request.Builder requestBuilder = new Request.Builder().url(task.url);
        if (existingLength > 0L) {
            requestBuilder.header("Range", "bytes=" + existingLength + "-");
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("下载失败，HTTP状态码=" + response.code());
            }

            boolean append = existingLength > 0L && response.code() == 206;
            if (!append) {
                existingLength = 0L;
            }

            long remainingLength = response.body().contentLength();
            long totalLength = remainingLength > 0L
                    ? existingLength + remainingLength
                    : -1L;

            try (InputStream input = response.body().byteStream();
                 RandomAccessFile output = new RandomAccessFile(temporaryFile, "rw")) {
                if (append) {
                    output.seek(existingLength);
                } else {
                    output.setLength(0L);
                }

                byte[] buffer = new byte[8192];
                long written = existingLength;
                int lastProgress = -1;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) {
                        continue;
                    }
                    output.write(buffer, 0, count);
                    written += count;

                    if (totalLength > 0L) {
                        int progress = (int) (written * 100L / totalLength);
                        if (progress == 100 || progress - lastProgress >= 5) {
                            lastProgress = progress;
                            progressCallback.onProgress(progress);
                        }
                    }
                }
            }
        }

        if (!checkMd5(temporaryFile, task.md5)) {
            temporaryFile.delete();
            throw new IllegalStateException("下载文件MD5校验失败");
        }

        if (finalFile.exists() && !finalFile.delete()) {
            throw new IllegalStateException("无法删除旧升级文件");
        }
        if (!temporaryFile.renameTo(finalFile)) {
            throw new IllegalStateException("升级临时文件重命名失败");
        }
        progressCallback.onProgress(100);
        return finalFile;
    }

    private InstallResult installSelf(File apk) {
        Process process = null;
        try {
            String command = "pm install -r --user 0 \"" + apk.getAbsolutePath() + "\"";
            process = Runtime.getRuntime().exec(new String[]{"su", "0", "sh", "-c", command});

            StringBuilder output = new StringBuilder();
            try (BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
                 BufferedReader stderr = new BufferedReader(
                         new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = stdout.readLine()) != null) {
                    output.append(line).append('\n');
                }
                while ((line = stderr.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && output.toString().contains("Success")) {
                return InstallResult.success();
            }
            return InstallResult.failure(
                    parseInstallErrorCode(output.toString()),
                    parseInstallErrorMessage(output.toString(), exitCode)
            );
        } catch (Throwable error) {
            return InstallResult.failure("INSTALL_EXCEPTION", messageOf(error));
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private void savePendingInstall(UpgradeTask task) {
        preferences().edit()
                .putString("pending_type", "ota")
                .putString("pending_target_version", task.version)
                .putString("pending_message_id", task.messageId)
                .putLong("pending_record_id", task.recordId)
                .putLong("pending_task_id", task.taskId)
                .commit();
    }

    private void clearPendingInstall() {
        preferences().edit()
                .remove("pending_type")
                .remove("pending_target_version")
                .remove("pending_message_id")
                .remove("pending_record_id")
                .remove("pending_task_id")
                .apply();
    }

    private String getCurrentVersion(String type) {
        if ("ball".equals(type)) {
            return preferences().getString(KEY_BOARD_VERSION, "");
        }
        return DeviceUtil.getAppVersion(context);
    }

    private void reportTaskFailure(UpgradeTask task, String code, String message) {
        report(
                task == null ? "" : task.messageId,
                "failed",
                0,
                task == null ? "" : getCurrentVersion(task.type),
                task == null ? "" : task.version,
                task == null ? 0L : task.recordId,
                task == null ? 0L : task.taskId,
                task == null ? "" : task.type,
                code,
                message
        );
    }

    private boolean report(
            String messageId,
            String status,
            int progress,
            String currentVersion,
            String targetVersion,
            long recordId,
            long taskId,
            String type,
            String errorCode,
            String errorMessage
    ) {
        return MqttManager.get(context).reportUpgradeProgress(
                messageId,
                status,
                progress,
                currentVersion,
                targetVersion,
                recordId,
                taskId,
                type,
                errorCode,
                errorMessage
        );
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static boolean checkMd5(File file, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }

        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        }
        return builder.toString().equalsIgnoreCase(expected.trim());
    }

    private static String parseInstallErrorCode(String message) {
        if (message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                || message.contains("signatures do not match")) {
            return "INSTALL_FAILED_UPDATE_INCOMPATIBLE";
        }
        if (message.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) {
            return "INSTALL_FAILED_VERSION_DOWNGRADE";
        }
        if (message.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) {
            return "INSTALL_FAILED_INSUFFICIENT_STORAGE";
        }
        if (message.contains("INSTALL_FAILED_NO_MATCHING_ABIS")) {
            return "INSTALL_FAILED_NO_MATCHING_ABIS";
        }
        if (message.contains("INSTALL_PARSE_FAILED") || message.contains("Failed parse")) {
            return "INSTALL_PARSE_FAILED";
        }
        return "INSTALL_FAIL";
    }

    private static String parseInstallErrorMessage(String message, int exitCode) {
        String code = parseInstallErrorCode(message);
        switch (code) {
            case "INSTALL_FAILED_UPDATE_INCOMPATIBLE":
                return "APK签名与已安装版本不一致";
            case "INSTALL_FAILED_VERSION_DOWNGRADE":
                return "目标versionCode低于当前版本";
            case "INSTALL_FAILED_INSUFFICIENT_STORAGE":
                return "设备存储空间不足";
            case "INSTALL_FAILED_NO_MATCHING_ABIS":
                return "APK不包含RK3566所需ABI";
            case "INSTALL_PARSE_FAILED":
                return "APK解析失败或文件损坏";
            default:
                return "系统安装命令返回码=" + exitCode + "，输出=" + message.trim();
        }
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

    private interface ProgressCallback {
        void onProgress(int progress);
    }

    private static final class UpgradeTask {
        String type;
        String messageId;
        String version;
        String url;
        String md5;
        long recordId;
        long taskId;

        static UpgradeTask parse(String payload) throws Exception {
            JSONObject json = new JSONObject(payload);
            UpgradeTask task = new UpgradeTask();
            task.type = json.optString("type", "").trim();
            task.messageId = json.optString("messageId", "").trim();
            task.version = json.optString("version", "").trim();
            task.url = json.optString("url", "").trim();
            task.md5 = json.optString("md5", "").trim();
            task.recordId = json.optLong("recordId", 0L);
            task.taskId = json.optLong("taskId", 0L);
            return task;
        }

        String validate() {
            if (type == null || type.isEmpty()) {
                return "服务器未下发升级类型";
            }
            if (version == null || version.isEmpty()) {
                return "服务器未下发目标版本号";
            }
            if (url == null || url.isEmpty()) {
                return "服务器未下发下载地址";
            }
            if (md5 == null || md5.isEmpty()) {
                return "服务器未下发MD5";
            }
            return null;
        }
    }

    private static final class InstallResult {
        boolean success;
        String errorCode;
        String errorMessage;

        static InstallResult success() {
            InstallResult result = new InstallResult();
            result.success = true;
            return result;
        }

        static InstallResult failure(String code, String message) {
            InstallResult result = new InstallResult();
            result.success = false;
            result.errorCode = code;
            result.errorMessage = message;
            return result;
        }
    }
}
