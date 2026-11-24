"""APK 里跟中文相关的固定路径。

发版门禁（tools/release/verify-apk.py）和本地核验（tools/verify/apk.py）
都要认这些路径，写两份迟早漂。改路径只改这里。
"""

ASSETS = "assets/linuxfs"
FONT = ASSETS + "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"
FONTCONF = ASSETS + "/etc/fonts/conf.d/99-zh-fallback.conf"
SESSION = ASSETS + "/usr/local/bin/droiddeck-session"
ZH_PRESET = ASSETS + "/usr/local/bin/droiddeck-zh-steam-preset"
ZH_LOCALE = ASSETS + "/usr/lib/locale/zh_CN.utf8/LC_CTYPE"
