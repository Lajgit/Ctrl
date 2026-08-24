from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def replace_once(path, old, new):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"未找到预期片段: {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

build = ROOT / "GouzhuApp/app/build.gradle"
replace_once(build, 'def appVersionName = "2.3.20"\ndef appVersionCode = 38',
                    'def appVersionName = "2.3.21"\ndef appVersionCode = 39')

activity = ROOT / "GouzhuApp/app/src/main/java/com/gouzhu/redemption/RedemptionActivity.java"
replace_once(activity,
'''    private LinearLayout channelSection;
    private Button douyinButton;
    private Button meituanButton;
    private LinearLayout scanSection;''',
'''    private LinearLayout channelSection;
    private LinearLayout douyinButton;
    private LinearLayout meituanButton;
    private TextView douyinStatusText;
    private TextView meituanStatusText;
    private LinearLayout scanSection;''')
replace_once(activity,
'''        channelSection = findViewById(R.id.layout_redemption_channels);
        douyinButton = findViewById(R.id.button_channel_douyin);
        meituanButton = findViewById(R.id.button_channel_meituan);
        scanSection = findViewById(R.id.layout_redemption_scan);''',
'''        channelSection = findViewById(R.id.layout_redemption_channels);
        douyinButton = findViewById(R.id.button_channel_douyin);
        meituanButton = findViewById(R.id.button_channel_meituan);
        douyinStatusText = findViewById(R.id.text_channel_douyin_status);
        meituanStatusText = findViewById(R.id.text_channel_meituan_status);
        scanSection = findViewById(R.id.layout_redemption_scan);''')
replace_once(activity,
'''    private void showChannelSelection(List<RedemptionCapabilityResolver.ChannelOption> channels) {
        channelSection.setVisibility(View.VISIBLE);
        douyinButton.setVisibility(View.GONE);
        meituanButton.setVisibility(View.GONE);
        for (RedemptionCapabilityResolver.ChannelOption channel : channels) {
            if (RedemptionCapabilityResolver.CHANNEL_DOUYIN.equals(channel.code)) {
                douyinButton.setText(channel.name);
                douyinButton.setVisibility(View.VISIBLE);
            } else if (RedemptionCapabilityResolver.CHANNEL_MEITUAN.equals(channel.code)) {
                meituanButton.setText(channel.name);
                meituanButton.setVisibility(View.VISIBLE);
            }
        }
        if (channels.isEmpty()) {
            statusText.setText(R.string.third_party_no_channel);
        }
    }
''',
'''    private void showChannelSelection(List<RedemptionCapabilityResolver.ChannelOption> channels) {
        channelSection.setVisibility(View.VISIBLE);
        boolean douyinAvailable = false;
        boolean meituanAvailable = false;
        if (channels != null) {
            for (RedemptionCapabilityResolver.ChannelOption channel : channels) {
                if (RedemptionCapabilityResolver.CHANNEL_DOUYIN.equals(channel.code)) {
                    douyinAvailable = true;
                } else if (RedemptionCapabilityResolver.CHANNEL_MEITUAN.equals(channel.code)) {
                    meituanAvailable = true;
                }
            }
        }

        // 两个平台入口始终并列展示；服务端未开放的渠道只置灰，绝不绕过 bootstrap 发起核销。
        applyChannelCardState(douyinButton, douyinStatusText, douyinAvailable);
        applyChannelCardState(meituanButton, meituanStatusText, meituanAvailable);
        statusText.setText(!douyinAvailable && !meituanAvailable
                ? R.string.third_party_no_channel
                : R.string.third_party_choose_channel_hint);
    }

    private void applyChannelCardState(LinearLayout card, TextView status, boolean available) {
        card.setEnabled(available);
        card.setClickable(available);
        card.setAlpha(available ? 1.0f : 0.42f);
        status.setText(available
                ? R.string.third_party_channel_available
                : R.string.third_party_channel_unavailable);
        status.setTextColor(getColor(available
                ? R.color.header_primary
                : R.color.text_hint));
    }
''')

layout = ROOT / "GouzhuApp/app/src/main/res/layout/activity_redemption.xml"
text = layout.read_text(encoding="utf-8")
text = text.replace('android:paddingStart="36dp"', 'android:paddingStart="28dp"', 1)
text = text.replace('android:paddingTop="36dp"', 'android:paddingTop="28dp"', 1)
text = text.replace('android:paddingEnd="36dp"', 'android:paddingEnd="28dp"', 1)
start = text.index('        <LinearLayout\n            android:id="@+id/layout_redemption_channels"')
end = text.index('        <LinearLayout\n            android:id="@+id/layout_redemption_scan"')
channel = r'''        <LinearLayout
            android:id="@+id/layout_redemption_channels"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:orientation="vertical"
            android:visibility="gone">

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="@string/third_party_choose_channel_title"
                android:textColor="@color/text_primary"
                android:textSize="23sp"
                android:textStyle="bold" />

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="5dp"
                android:text="@string/third_party_choose_channel_tip"
                android:textColor="@color/text_secondary"
                android:textSize="15sp" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                android:baselineAligned="false"
                android:orientation="horizontal">

                <LinearLayout
                    android:id="@+id/button_channel_douyin"
                    android:layout_width="0dp"
                    android:layout_height="170dp"
                    android:layout_marginEnd="8dp"
                    android:layout_weight="1"
                    android:background="@drawable/bg_redemption_channel_card"
                    android:clickable="true"
                    android:elevation="1dp"
                    android:focusable="true"
                    android:gravity="center"
                    android:orientation="vertical"
                    android:padding="14dp">

                    <ImageView
                        android:layout_width="142dp"
                        android:layout_height="58dp"
                        android:adjustViewBounds="true"
                        android:contentDescription="@string/third_party_douyin_logo_description"
                        android:scaleType="centerInside"
                        android:src="@drawable/ic_logo_douyin" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:text="@string/third_party_douyin_action"
                        android:textColor="@color/text_primary"
                        android:textSize="20sp"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/text_channel_douyin_status"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="6dp"
                        android:text="@string/third_party_channel_unavailable"
                        android:textColor="@color/text_hint"
                        android:textSize="14sp" />
                </LinearLayout>

                <LinearLayout
                    android:id="@+id/button_channel_meituan"
                    android:layout_width="0dp"
                    android:layout_height="170dp"
                    android:layout_marginStart="8dp"
                    android:layout_weight="1"
                    android:background="@drawable/bg_redemption_channel_card"
                    android:clickable="true"
                    android:elevation="1dp"
                    android:focusable="true"
                    android:gravity="center"
                    android:orientation="vertical"
                    android:padding="14dp">

                    <ImageView
                        android:layout_width="142dp"
                        android:layout_height="58dp"
                        android:adjustViewBounds="true"
                        android:contentDescription="@string/third_party_meituan_logo_description"
                        android:scaleType="centerInside"
                        android:src="@drawable/ic_logo_meituan" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:text="@string/third_party_meituan_action"
                        android:textColor="@color/text_primary"
                        android:textSize="20sp"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/text_channel_meituan_status"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="6dp"
                        android:text="@string/third_party_channel_unavailable"
                        android:textColor="@color/text_hint"
                        android:textSize="14sp" />
                </LinearLayout>
            </LinearLayout>
        </LinearLayout>

'''
layout.write_text(text[:start] + channel + text[end:], encoding="utf-8")

strings = ROOT / "GouzhuApp/app/src/main/res/values/redemption_strings.xml"
replace_once(strings,
'''    <string name="third_party_choose_channel_title">请选择团购平台</string>
    <string name="third_party_choose_channel_hint">请选择抖音或美团</string>
    <string name="third_party_no_channel">当前门店暂无可用团购核销渠道</string>''',
'''    <string name="third_party_choose_channel_title">请选择团购平台</string>
    <string name="third_party_choose_channel_tip">选择平台后再扫描对应团购券，未开通渠道将置灰</string>
    <string name="third_party_choose_channel_hint">请选择可用的团购平台</string>
    <string name="third_party_no_channel">当前门店暂无可用团购核销渠道</string>
    <string name="third_party_douyin_action">抖音核销</string>
    <string name="third_party_meituan_action">美团核销</string>
    <string name="third_party_channel_available">点击进入</string>
    <string name="third_party_channel_unavailable">暂未开通</string>
    <string name="third_party_douyin_logo_description">抖音</string>
    <string name="third_party_meituan_logo_description">美团</string>''')

drawable = ROOT / "GouzhuApp/app/src/main/res/drawable/bg_redemption_channel_card.xml"
drawable.write_text('''<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_enabled="false">
        <shape android:shape="rectangle">
            <solid android:color="#F1F4F8" />
            <stroke android:width="1dp" android:color="#E1E6EF" />
            <corners android:radius="20dp" />
        </shape>
    </item>
    <item android:state_pressed="true">
        <shape android:shape="rectangle">
            <solid android:color="@color/selected_background" />
            <stroke android:width="2dp" android:color="@color/header_secondary" />
            <corners android:radius="20dp" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/card_background" />
            <stroke android:width="1dp" android:color="@color/card_border" />
            <corners android:radius="20dp" />
        </shape>
    </item>
</selector>
''', encoding="utf-8")
