# 构建产物速览

`app/build/outputs/apk/debug/app-debug.apk` — 5.9 MB，已验证可用。

## 在自己机器上重新构建

```bash
# 需要 JDK 17
export JAVA_HOME="/path/to/jdk-17"

# Android Studio 打开工程会自动配好 SDK 路径；
# 命令行构建需自己写 local.properties：
echo 'sdk.dir=/path/to/Android/Sdk' > local.properties

./gradlew :app:assembleDebug
```

## 装到手机

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或者把 apk 拷进手机点击安装（需允许未知来源）。
