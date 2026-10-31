#!/usr/bin/env bash
# 上传前自检：确认仓库里没有签名库与口令
# 注意：本脚本自身不得包含任何真实口令字面量（否则自检会命中自己）。
set -u
fail=0

P1='wjljh'; P2='951753'
P3='andbna'; P4='2026'
NEEDLES="${P1}${P2}|${P3}${P4}"

echo '== 1) 签名库/密钥文件 =='
if git ls-files | grep -E '\.(jks|keystore)$|keystore\.properties$'; then
  echo '  x 发现签名库/密钥文件，请从索引移除'; fail=1
else echo '  ok 无'; fi

echo '== 2) 本地路径文件 =='
if git ls-files | grep -E 'local\.properties$'; then echo '  x local.properties 在索引里'; fail=1; else echo '  ok 无'; fi

echo '== 3) 内容里的口令/密钥 =='
if git grep -nE "$NEEDLES|sk-[A-Za-z0-9]{20,}|enc:v1:[A-Za-z0-9+/=]{20,}" -- . >/dev/null 2>&1; then
  echo '  x 命中可疑字符串：'; git grep -nE "$NEEDLES|sk-[A-Za-z0-9]{20,}" -- . | head -5; fail=1
else echo '  ok 无'; fi

echo '== 4) 构建产物 =='
if git ls-files | grep -E '(^|/)build/|.*\.apk$'; then echo '  x 构建产物或 APK 在索引里'; fail=1; else echo '  ok 无'; fi

echo '== 5) 仓库体积 =='
du -sh .git | cut -f1
echo
[ $fail -eq 0 ] && echo '结论：可以上传' || echo '结论：先修掉上面的问题'
exit $fail