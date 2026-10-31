#!/usr/bin/env bash
# 上传前自检：确认仓库里没有签名库与口令
set -u
fail=0
echo '== 1) 签名库/密钥文件 =='
if git ls-files | grep -E '\.(jks|keystore)$|\.keystore$|keystore\.properties$'; then
  echo '  ✗ 发现签名库/密钥文件，请从索引移除'; fail=1
else echo '  ✓ 无'; fi
echo '== 2) 本地路径文件 =='
if git ls-files | grep -E 'local\.properties$'; then echo '  ✗ local.properties 在索引里'; fail=1; else echo '  ✓ 无'; fi
echo '== 3) 内容里的口令/密钥 =='
if git grep -nE 'wjljh951753|andbna2026|sk-[A-Za-z0-9]{20,}|enc:v1:[A-Za-z0-9+/=]{20,}' -- . >/dev/null 2>&1; then
  echo '  ✗ 命中可疑字符串：'; git grep -nE 'wjljh951753|andbna2026|sk-[A-Za-z0-9]{20,}' -- . | head -5; fail=1
else echo '  ✓ 无'; fi
echo '== 4) 构建产物 =='
if git ls-files | grep -E '(^|/)build/|.*\.apk$'; then echo '  ✗ 构建产物或 APK 在索引里'; fail=1; else echo '  ✓ 无'; fi
echo '== 5) 仓库体积 =='
du -sh .git | cut -f1
echo
[ $fail -eq 0 ] && echo '结论：可以上传 ✓' || echo '结论：先修掉上面的问题 ✗'
exit $fail
