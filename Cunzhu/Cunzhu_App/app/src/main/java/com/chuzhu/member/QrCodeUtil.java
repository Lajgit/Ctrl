package com.chuzhu.member;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.Map;

/**
 * 设备屏二维码生成工具。
 */
public final class QrCodeUtil {

    private QrCodeUtil() {
    }

    public static Bitmap create(String content, int sizePx) throws Exception {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        int size = Math.max(160, sizePx);
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix matrix = new QRCodeWriter().encode(
                content.trim(),
                BarcodeFormat.QR_CODE,
                size,
                size,
                hints
        );

        /*
         * 旧实现对 420x420 位图逐像素调用 Bitmap.setPixel，RK3566 上会产生明显主线程卡顿。
         * 先在普通 int[] 中完成填充，再一次 setPixels 写入 Bitmap，避免十几万次 JNI/边界检查。
         */
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            int row = y * size;
            for (int x = 0; x < size; x++) {
                pixels[row + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        return bitmap;
    }
}
