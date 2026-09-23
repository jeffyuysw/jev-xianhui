# 部署教程 · 从零到跑起来

这份文档假设你**从没碰过 Android 开发**，照着做就能把 App 装到自己手机上。全程大约 30 分钟，其中大半时间是下载。

如果你只想要现成的 APK，跳到 [方式一](#方式一用现成-apk-最快3-分钟)。

---

## 目录

- [你需要的三样东西](#你需要的三样东西)
- [方式一：用现成 APK（最快，3 分钟）](#方式一用现成-apk-最快3-分钟)
- [方式二：自己编译（10–30 分钟）](#方式二自己编译1030-分钟)
  - [A. 装 JDK 17](#a-装-jdk-17)
  - [B. 装 Android SDK](#b-装-android-sdk)
  - [C. 配置工程](#c-配置工程)
  - [D. 编译](#d-编译)
- [装到手机](#装到手机)
- [首次配置（三步）](#首次配置三步)
- [拿到 API Key](#拿到-api-key)
- [验证是否成功](#验证是否成功)
- [悬浮窗怎么用](#悬浮窗怎么用)
- [让它在后台活下来](#让它在后台活下来必做)
- [出问题了看这里](#出问题了看这里)
- [不想写代码？改这几处就够了](#不想写代码改这几处就够了)

---

## 你需要的三样东西

| 东西 | 用途 | 是否必须 |
| --- | --- | --- |
| 一台 Android 手机 | 跑 App，**建议 Android 8.0 以上**（minSdk 26） | 必须 |
| 一个 OpenRouter 账号 | 提供判断用的模型接口 | 必须 |
| 电脑（Windows / macOS / Linux 都行） | 编译，或者只是下载 APK | 用现成 APK 的话可有可无 |

> **不需要**：不需要 Root，不需要刷机，不需要装 Xposed，不需要电脑常连着手机。

---

## 方式一：用现成 APK（最快，3 分钟）

如果仓库里带了 `app-debug.apk`（在 Releases 页面，或 `app/build/outputs/apk/debug/`），直接：

1. 把 `app-debug.apk` 传到手机（微信传文件、数据线拷贝、网盘都行）
2. 手机上点开这个文件
3. 系统会提示"不允许安装未知来源应用" → 点**设置** → 打开允许 → 返回继续安装

装完跳到 [首次配置](#首次配置四步)。

---

## 方式二：自己编译（10–30 分钟）

### A. 装 JDK 17

**这一步是强制的。** AGP 8.7 要求 JDK 11 以上，JDK 8 会直接报错。

先检查你现在的版本：

```bash
java -version
```

- 看到 `17.x` 或更高 → 跳过这步
- 看到 `1.8.x`（就是 JDK 8）或命令不存在 → 继续往下装

<details open>
<summary><b>Windows</b></summary>

用 winget 最省事：

```powershell
winget install --id EclipseAdoptium.Temurin.17.JDK --accept-source-agreements --accept-package-agreements
```

装完**关掉并重开终端**，再 `java -version` 确认。

或者手动下载：到 [Adoptium 官网](https://adoptium.net/temurin/releases/?version=17) 下 `.msi`，安装时勾选 **"Set JAVA_HOME variable"**。

装完记下路径，一般是 `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`。
</details>

<details>
<summary><b>macOS</b></summary>

```bash
brew install --cask temurin@17
```

Mac 上通常不用手动设 `JAVA_HOME`，Homebrew 会处理好。
</details>

<details>
<summary><b>Linux (Debian/Ubuntu)</b></summary>

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk
```
</details>

### B. 装 Android SDK

你有两条路，**推荐第一条**。

#### 路线 1：装 Android Studio（推荐，最省心）

到 [developer.android.com/studio](https://developer.android.com/studio) 下载安装。第一次启动时它会自己下载 SDK 组件。

装完打开 Android Studio → **Open** → 选中本工程根目录（**不是 `app` 子目录**）。它会自动：

- 生成 `local.properties` 并填好 SDK 路径
- 下载 Gradle 和依赖
- 同步完成

然后直接在菜单点 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**。

或者用界面下方的 **Terminal** 标签跑：

```bash
./gradlew :app:assembleDebug
```

> 用 Android Studio 的话，可以完全跳过下面「路线 2」和「C. 配置工程」。

#### 路线 2：只要命令行 SDK（不装庞大的 IDE）

<details>
<summary><b>Windows</b></summary>

1. 下载命令行工具：[commandlinetools-win-*_latest.zip](https://developer.android.com/studio#command-tools)
2. 解压，把 `cmdline-tools` 文件夹放到 `<SDK目录>\cmdline-tools\latest\`
   （比如 `C:\Users\你的用户名\AppData\Local\Android\Sdk\cmdline-tools\latest\`）
3. 设环境变量：

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
```

4. 接受许可并装组件：

```powershell
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$sdk" --licenses
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$sdk" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

第一个命令会连续问很多次 `Accept? (y/N)`，一路输 `y` 回车。
</details>

<details>
<summary><b>macOS / Linux</b></summary>

```bash
# 下载（版本号可能变化，去上面链接取最新的）
curl -O https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
# Linux 用 commandlinetools-linux-11076708_latest.zip

mkdir -p ~/Android/Sdk/cmdline-tools
unzip -q commandlinetools-*.zip -d /tmp/clt
mv /tmp/clt/cmdline-tools ~/Android/Sdk/cmdline-tools/latest

export ANDROID_HOME="$HOME/Android/Sdk"
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS 专用；Linux 自己指向 JDK 目录

yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```
</details>

### C. 配置工程

只有**命令行构建**才需要这步。在工程根目录建一个 `local.properties`：

<details open>
<summary><b>Windows</b>（注意反斜杠要写两遍）</summary>

```properties
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```
</details>

<details>
<summary><b>macOS / Linux</b></summary>

```properties
sdk.dir=/Users/你的用户名/Android/Sdk
```
</details>

> **这个文件千万别提交到 Git** —— 它写的是你个人的绝对路径。工程自带的 `.gitignore` 已经排除它了。

### D. 编译

```bash
# Windows 用 gradlew.bat，macOS/Linux 用 ./gradlew

# 命令行里要确保用的是 JDK 17，否则报 "requires at least JVM runtime version 11"
./gradlew :app:assembleDebug
```

第一次会下载 Gradle 8.9、AGP、依赖库，慢的话十几分钟。**工程已经带了 Gradle wrapper，不用另外装 Gradle。**

成功的话最后会看到：

```
BUILD SUCCESSFUL
```

产物在：

```
app/build/outputs/apk/debug/app-debug.apk
```

> **构建失败的常见原因**：`JAVA_HOME` 还指向 JDK 8。显式指定一下：
> ```bash
> # Windows (Git Bash)
> export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
> ./gradlew :app:assembleDebug
> ```

---

## 装到手机

**方式一：数据线 + adb**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**方式二：直接拷文件**

把 apk 拷到手机，用文件管理器点开安装，允许"未知来源"。

---

## 首次配置（三步）

打开 App，首页是三张卡片，每张对应一步。**卡片上的徽章会告诉你这步做完没有**——红色是没做，绿色是做完了。按钮在完成后会自动消失，所以看到按钮就说明还没完成。

顶部还有一条总览，直接写着"还差几步就能用了"。

### 1. 开启通知读取权限

点卡片上的 **「去开启」** → 跳到系统设置 → 找到 **「先回」** → 打开 **「通知使用权」**。

> 这一步 Android 只允许在系统设置里手动授权，任何 App 都无法代开——这是系统设计，不是缺陷。

### 2. 开启悬浮窗权限

同样点 **「去开启」** → 打开 **「允许显示在其他应用上层」**。

### 3. 填 API Key

Android 13+ 会先弹一次通知授权，点**允许**。

> 不授权也能用，但保活通知会被系统隐藏，后台更容易被回收。

然后粘贴你的 OpenRouter Key（见下一节），点 **保存**。

回到手机桌面，悬浮窗应该已经出现了。如果没出现，回 App 点 **「显示悬浮窗」**。

---

## 拿到 API Key

1. 打开 [openrouter.ai](https://openrouter.ai)，注册（支持 Google 账号）
2. 进 [Keys 页面](https://openrouter.ai/keys)，点 **Create Key**
3. 复制形如 `sk-or-v1-xxxxxxxx` 的字符串
4. 到 [Credits 页面](https://openrouter.ai/credits) 充一点钱 —— **充 1 美元够你用很久**

本应用默认用 `typesafe/jev-1.13` 这个模型，一次判断约 `$0.00004`。**每天 200 条消息大约半分钱人民币。**

充完钱回到 App，粘贴 Key，点 **「测试连接」**。

---

## 验证是否成功

点 **「测试连接」** 后应该看到类似：

```
紧急度 8/9 · 要马上回 · 类型 work_blocking · 把握 92%
```

这是 App 拿一条模拟消息（"方案今天下班前能发我吗？老板在等"）真的调了一次模型。

看到结果说明**链路全通了**——这一步真的调了一次模型。

然后点 **「显示悬浮窗」**，去微信里让别人给你发几条不同类型的消息（工作的、广告的、闲聊的）——悬浮窗会开始分类。

## 悬浮窗怎么用

| 操作 | 效果 |
| --- | --- |
| 按住顶部横条拖动 | 移动位置，**松手自动吸附到最近的屏幕边缘**（不会停在屏幕中间挡内容） |
| 点一下顶部横条 | 收起 / 展开列表 |
| 点某条消息 | 跳进那个会话，同时该条从列表和通知栏消失 |
| 点 **「全部已读」** | 清空列表，并清掉对应的系统通知 |
| 点右上角 **「×」** | 关闭悬浮窗，回主页可以重新打开 |

颜色含义：**红=马上回**，**黄=尽快**，**灰=可以晚点**，**蓝=正在判断**。

判断失败的条目会显示"判断失败，点重试"，**点一下就会重新判断**——通常是当时网络不通或余额不足。

主页的运行区下方还会实时显示"当前 N 条待处理"，悬浮窗关着的时候也能看到。

突然积压了很多条、想一次性清掉？主页有 **「清空待处理列表」** 按钮。

---

## 让它在后台活下来（必做）

**这一步绕不开。** 国产 ROM（小米/HyperOS、华为、OPPO、vivo）会主动冻结后台进程，不配置的话跑十几分钟就收不到消息了。

App 内置了前台服务保活，但 ROM 的白名单必须手动加：

| 品牌 | 要做的操作 |
| --- | --- |
| **小米 / Redmi (MIUI / HyperOS)** | 设置 → 应用设置 → 应用管理 → 先回 → **省电策略选「无限制」**；再开 **「自启动」**；最后在最近任务里**下拉该卡片加锁** |
| **华为 / 荣耀** | 设置 → 应用 → 应用启动管理 → 先回 → 关掉「自动管理」，三个开关全开 |
| **OPPO / 一加 / realme** | 设置 → 电池 → 应用耗电管理 → 先回 → 允许后台运行；再开自启动 |
| **vivo / iQOO** | 设置 → 电池 → 后台高耗电 → 允许；i管家 → 自启动管理 → 打开 |
| **三星 / 原生 Android** | 一般不用配，但建议关掉「自适应电池」对它的优化 |

> 找不到对应菜单？直接搜品牌 + "后台保活 设置"，各家教程很多。

---

## 出问题了看这里

### 悬浮窗里一直显示"还没有收到消息"

按顺序排查：

1. **通知读取权限真的开了吗？** 回 App 首页看第一步是否显示绿色「已开启」。如果显示未开启，重新去系统设置授权。
2. **微信有没有在发通知？** 有些人的微信把通知全关了，或者开了"消息免打扰"。
3. **你监听的应用勾对了吗？** 首页「运行」区有「监听微信」「监听 QQ」两个勾选框，确认勾上了。
4. **点「清空列表」再试**，有时是旧数据干扰。

### 消息收到了，但一直显示"未判断"

这是模型接口没通：

1. 点 **「测试连接」** 看具体报错
2. 常见原因：Key 写错了 / Key 所在账号**余额为 0** / 网络不通
3. 报错里如果带 `HTTP 401` → Key 错了；`HTTP 402` → 余额不足

### 跑一会儿就不收消息了

**99% 是 ROM 保活没配好。** 回去看[上一节](#让它在后台活下来必做)。

验证方法：App 首页第一步如果还是「已开启」，但悬浮窗不再更新，就是进程被冻结了。去最近任务里给它**加锁**。

### 编译报 `SDK location not found`

`local.properties` 没建，或者 `sdk.dir` 路径写错了。参考 [C. 配置工程](#c-配置工程)。

### 编译报 `requires at least JVM runtime version 11`

你在用 JDK 8。装 JDK 17，并确保构建时 `JAVA_HOME` 指向它。

### 点击消息条目提示"没有可跳转的入口"

那条通知本身没带跳转意图（常见于某些 App 的群消息、或通知聚合后重建的条目）。不影响判断，只是点不进去。

### 点击消息后没有跳进聊天

本应用跳转用的是**通知自带的 `contentIntent`**，也就是"你点系统通知栏里那条消息会去哪"。

- 如果**点系统通知栏也能正常进聊天**，那点悬浮窗条目应该同样能进
- 如果点系统通知栏只打开微信主页、不进具体会话，那是微信通知本身的构造方式如此，第三方应用无法改变
- 极少数机型需要额外开 **「后台弹出界面」** 权限（小米/OPPO/vivo 有这项），否则跳转会被系统拦截

另外，无论跳转是否成功，**点击后条目都会从列表里消失**，同时系统通知栏里那条也会被清除 —— 这表示你已经处理过它了。

---

## 不想写代码？改这几处就够了

| 想改什么 | 改哪个文件 |
| --- | --- |
| **判断准不准**（最重要） | `app/src/main/java/com/jev/priority/jev/PriorityQuestions.kt` |
| 换模型 | App 首页填 Key 的地方下方，或改 `core/Prefs.kt` 里的 `DEFAULT_MODEL` |
| 悬浮窗初始位置和大小 | `overlay/PriorityOverlay.kt` 底部常量 `WIDTH_DP` / `LIST_HEIGHT_DP` |
| 悬浮窗配色 | `overlay/PriorityOverlay.kt` 的 `refresh()` 和 `row()` 里的颜色值 |
| 哪些应用被监听 | App 首页勾选框，或改 `core/Prefs.kt` 的 `DEFAULT_WATCH` |
| 判断的紧急度分档线 | `core/MsgItem.kt` 里 `bucket` 的 `when` 条件 |

**关于 `PriorityQuestions.kt`**：这是全项目唯一需要反复打磨的地方。四道题分别是紧急度（1–9 打分）、要不要马上回（是非）、消息类型（单选）、为什么紧急（单选）。instructions 用英文写（模型的主要训练语言），类别名用英文 id、中文注释。想让它更懂你的场景，就往 prompts 里加你真实遇到过的例子。

---

## 关于隐私

- API Key 只存在 App 私有存储里，不会外传
- 消息原文**只在判断那一刻**发给模型接口，不落盘、不进日志、不存历史
- 消息列表在内存中，重启 App 即清空
- 代码里没有任何统计、上报、埋点

不放心的话可以全程抓包验证 —— 唯一的网络出口是 `HttpJson.kt` 里的那一个 POST。

---

## 免责与合规

本应用使用 **Android 官方 `NotificationListenerService` API**，不需要 Root、不伪造系统服务、不绕过任何应用的安全机制，也不读取聊天记录数据库。

请仅在自己拥有或已获授权的设备上使用，并遵守微信、QQ 等软件的用户协议及当地法律法规。

---

## 许可

MIT License，见 [LICENSE](LICENSE)。随意使用、修改、分发。
