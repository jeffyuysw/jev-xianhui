# 开源发布步骤

把这份工程推上 GitHub 并让别人能用，按下面走一遍即可。

## 一、先在本地确认

```bash
cd jev-xianhui
git status          # 应该看不到 local.properties / build/ / .gradle/```

确认 `local.properties` **不在**待提交列表里 —— 它含你本机的绝对路径，提交了别人也编译不过，还泄漏你的用户名。

## 二、发到 GitHub

### 方式 A：命令行

```bash
git init
git add -A
git commit -m "feat: 先回 —— 用 Jev 判断通知该不该马上回"
git branch -M main
git remote add origin https://github.com/<你的用户名>/xianhui.git
git push -u origin main
```

### 方式 B：先用桌面工具

GitHub Desktop / VS Code 的 Git 面板都可以，注意提交前看一眼文件列表，确认没有 `local.properties`。

## 三、写仓库描述

**About 栏**（建议填）：

```
用通知判断哪条消息必须马上回 —— Android 通知监听 + Jev 判断模型 + 可拖动悬浮窗
```

**Topics 标签**（建议加）：

```
android  kotlin  notification-listener  ai  llm  jev  overlay  productivity
```

## 四、发布 APK（重要）

别人最需要的是能直接装的东西。

1. 本地编译：`./gradlew :app:assembleDebug`
2. 到仓库 → **Releases** → **Draft a new release**
3. 填 tag（如 `v1.0.0`），标题写版本号
4. 把 `app/build/outputs/apk/debug/app-debug.apk` 拖进附件区
5. 说明里写清：**这是 debug 签名包，仅供试用；长期使用请自行编译 release 版**

> 想发 release 版的话，需要自己生成 keystore 并在 `app/build.gradle.kts` 里配 `signingConfigs`。debug 包能装能用，只是每次重建签名会变，覆盖安装可能提示冲突。

## 五、可选但推荐的补充

| 文件 | 作用 |
| --- | --- |
| `.github/workflows/build.yml` | 每次 push 自动编译，保证仓库始终可构建 |
| `CONTRIBUTING.md` | 告诉别人怎么提 PR |
| `CHANGELOG.md` | 记录版本变化 |
| 截图 | README 里放张悬浮窗实拍图，转化率高很多 |

## 六、提一句署名

本工程的判断思路参考了 [jev-chat-jarvis](https://github.com/jev-chat/jev-chat-jarvis)，模型用的是 [Jev](https://github.com/typesafeinc/jev)。README 里已经写明区别，保留即可。

## 七、注意事项

- **别把 API Key 提交上去**。工程设计上密钥只存在手机 App 里，源码里没有任何密钥，这点是安全的。
- **`local.properties` 必须忽略** —— `.gitignore` 已处理。
- **`gradle-wrapper.jar` 要提交** —— 它是二进制文件，但缺了别人就跑不了 `./gradlew`。`.gitignore` 里用 `!gradle/wrapper/gradle-wrapper.jar` 做了例外，别改掉。
- **合规表述别删** —— README 里的合规段落说明了本应用只用官方 API，这在开源社区是加分项，也避免被误解成外挂工具。
