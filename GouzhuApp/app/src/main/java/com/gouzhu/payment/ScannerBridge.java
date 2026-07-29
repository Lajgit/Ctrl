package com.gouzhu.payment;

import android.content.Context;

/**
 * 扫码模块接入桥。
 *
 * <p>扫码枪、串口扫码模块或厂商 SDK 得到二维码字符串后，只需调用
 * {@link #onQrDecoded(Context, String)}。当前服务器接口未提供，因此只生成待上报 JSON。</p>
 */
public final class ScannerBridge {

    private ScannerBridge() {
    }

    public static String onQrDecoded(Context context, String decodedText) {
        if (context == null) {
            return "";
        }
        return PaymentManager.get(context).submitScannerQrString(decodedText);
    }
}
