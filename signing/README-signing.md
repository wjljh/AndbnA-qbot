# 签名说明

构建需要把签名库放到这里：

    signing/wjljh-andbna_sign.jks

口令不写入源码。请在用户级 gradle 配置里设置（不进本仓库）：

    # ~/.gradle/gradle.properties
    andbnaStorePassword=<你的 store 口令>
    andbnaKeyPassword=<你的 key 口令>

或用环境变量：ANDBN_STORE_PASSWORD / ANDBN_KEY_PASSWORD。

签名者信息（用于核对）：

    CN=AndbnA, OU=personal, O=personal, L=KunMing, C=CN
    SHA-256 10:B5:07:E9:DE:C0:53:33:07:06:6E:E0:38:AB:08:68:00:B1:D5:C7:12:1B:C4:94:06:C8:3A:9C:E2:19:FA:A9
    2048-bit RSA / SHA256withRSA，有效期 2026-10-29 → 2126-10-29
