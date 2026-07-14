RFB Android 本地 Debug 更新签名

rfb-local-debug.keystore 只用于本工程生成的 Debug APK，目的是让今后由
“latest”构建出的调试包可以覆盖安装并保留应用数据。

固定参数：
  alias: androiddebugkey
  store password: android
  key password: android

这不是安全的正式发布密钥。公开发布或上架时请构建未签名 Release APK，
再使用你自己妥善保管的正式密钥签名；不要用本目录的 Debug 密钥发布。
