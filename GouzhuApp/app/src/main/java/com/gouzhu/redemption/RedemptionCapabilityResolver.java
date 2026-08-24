package com.gouzhu.redemption;

import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppRedemptionRouting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 根据本次 bootstrap 动态解析会员取珠和第三方团购核销能力。
 *
 * <p>设备只信任服务端下发的 feature 与 redemptionRouting，不使用本地旧缓存强行开放
 * 入口，也不根据扫码内容猜测抖音/美团渠道。</p>
 */
public final class RedemptionCapabilityResolver {

    public static final String FEATURE_MEMBER_WITHDRAW = "MEMBER_WITHDRAW";
    public static final String FEATURE_THIRD_PARTY_REDEMPTION = "THIRD_PARTY_REDEMPTION";
    public static final String CHANNEL_DOUYIN = "DOUYIN";
    public static final String CHANNEL_MEITUAN = "MEITUAN";

    private RedemptionCapabilityResolver() {
    }

    public static FeatureGate memberWithdrawal(DeviceAppBootstrapResult bootstrap) {
        return feature(bootstrap, FEATURE_MEMBER_WITHDRAW);
    }

    public static FeatureGate thirdPartyRedemption(DeviceAppBootstrapResult bootstrap) {
        FeatureGate gate = feature(bootstrap, FEATURE_THIRD_PARTY_REDEMPTION);
        if (!gate.visible || !gate.available) {
            return gate;
        }
        if (bootstrap == null || bootstrap.getRedemptionRouting() == null) {
            return new FeatureGate(
                    true,
                    false,
                    gate.title,
                    gate.description,
                    "核销路由尚未加载"
            );
        }
        if (thirdPartyChannels(bootstrap).isEmpty()) {
            return new FeatureGate(
                    true,
                    false,
                    gate.title,
                    gate.description,
                    "当前门店暂无可用团购渠道"
            );
        }
        return gate;
    }

    public static DeviceAppRedemptionRouting requireRouting(DeviceAppBootstrapResult bootstrap) {
        if (bootstrap == null || bootstrap.getRedemptionRouting() == null) {
            throw new IllegalStateException("核销路由尚未加载，请稍后重试");
        }
        return bootstrap.getRedemptionRouting();
    }

    /**
     * 当前产品界面只展示抖音和美团，但可用性完全来自 bootstrap 白名单。
     * 未下发的渠道绝不展示，也不会尝试调用。
     */
    public static List<ChannelOption> thirdPartyChannels(DeviceAppBootstrapResult bootstrap) {
        if (bootstrap == null || bootstrap.getRedemptionRouting() == null) {
            return Collections.emptyList();
        }
        List<DeviceAppRedemptionRouting.ThirdPartyChannel> channels =
                bootstrap.getRedemptionRouting().getThirdPartyChannels();
        if (channels == null || channels.isEmpty()) {
            return Collections.emptyList();
        }
        List<ChannelOption> result = new ArrayList<>();
        for (DeviceAppRedemptionRouting.ThirdPartyChannel channel : channels) {
            if (channel == null) {
                continue;
            }
            String code = normalize(channel.getChannelCode());
            if (!CHANNEL_DOUYIN.equals(code) && !CHANNEL_MEITUAN.equals(code)) {
                continue;
            }
            String defaultName = CHANNEL_DOUYIN.equals(code) ? "抖音" : "美团";
            result.add(new ChannelOption(
                    code,
                    firstNonBlank(channel.getChannelName(), defaultName)
            ));
        }
        return result;
    }

    public static ChannelOption findChannel(
            DeviceAppBootstrapResult bootstrap,
            String channelCode
    ) {
        String target = normalize(channelCode);
        for (ChannelOption option : thirdPartyChannels(bootstrap)) {
            if (target.equals(option.code)) {
                return option;
            }
        }
        return null;
    }

    private static FeatureGate feature(DeviceAppBootstrapResult bootstrap, String code) {
        if (bootstrap == null || bootstrap.getFeatures() == null) {
            return FeatureGate.hidden();
        }
        for (DeviceAppBootstrapResult.FeatureInfo feature : bootstrap.getFeatures()) {
            if (feature == null || !normalize(code).equals(normalize(feature.getCode()))) {
                continue;
            }
            return new FeatureGate(
                    feature.isVisible(),
                    feature.isAvailable(),
                    safe(feature.getTitle()),
                    safe(feature.getDescription()),
                    safe(feature.getUnavailableReason())
            );
        }
        return FeatureGate.hidden();
    }

    private static String normalize(String value) {
        return safe(value).toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    public static final class FeatureGate {
        public final boolean visible;
        public final boolean available;
        public final String title;
        public final String description;
        public final String unavailableReason;

        FeatureGate(
                boolean visible,
                boolean available,
                String title,
                String description,
                String unavailableReason
        ) {
            this.visible = visible;
            this.available = available;
            this.title = safe(title);
            this.description = safe(description);
            this.unavailableReason = safe(unavailableReason);
        }

        static FeatureGate hidden() {
            return new FeatureGate(false, false, "", "", "");
        }
    }

    public static final class ChannelOption {
        public final String code;
        public final String name;

        ChannelOption(String code, String name) {
            this.code = normalize(code);
            this.name = safe(name);
        }
    }
}
