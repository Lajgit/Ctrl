from pathlib import Path

path = Path('GouzhuApp/app/src/main/java/com/gouzhu/redemption/RedemptionActivity.java')
text = path.read_text(encoding='utf-8')
old = '''            confirmButton.setVisibility(View.GONE);\n            statusText.setText(R.string.third_party_choose_channel_hint);\n            resultDetailText.setText(\"\");'''
new = '''            confirmButton.setVisibility(View.GONE);\n            // 渠道选择方法已经根据实际 capability 设置提示，避免覆盖“暂无可用渠道”。\n            resultDetailText.setText(\"\");'''
if old not in text:
    raise SystemExit('未找到渠道提示覆盖片段')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
