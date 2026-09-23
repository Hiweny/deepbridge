# DeepBridge · DeepSeek 微信桥

把 **微信 ClawBot（iLink）** 和 **DeepSeek 网页版** 桥接起来：在微信里直接和 DeepSeek 聊天，支持**长期记忆、多条消息、图片/文件转发、后台常驻**。App 本身不直接调用 DeepSeek 私有 API，而是在本地 WebView 中以「纯网页层」方式驱动 `chat.deepseek.com`（自动填字、点发送、截取流式回复），因此**登录态、模式选择、DeepThink/联网开关、人机校验全部沿用官网**，稳定且不碰账号密码。

> 安装包请到 [Releases](../../releases) 下载；本仓库从 v1.4.0 起开放完整源码，并用 GitHub Actions 自动构建 APK。

## 功能特性

- **微信即对话**：扫码连接 ClawBot 后，在微信里发消息即可与 DeepSeek 对话；令牌本地持久化，免重复扫码。
- **图片 / 文件传输（v1.4 新增）**：在微信直接发送图片或文件（PDF / Word / Excel / PPT / TXT / Markdown / 代码 / 图片等），App 自动从微信 CDN 下载并 AES 解密，把文件注入 DeepSeek 官网输入区（由官网完成上传、解析与 PoW 校验），再连同你的文字一起发送。可配一句问题，如「总结一下这个文件」。`/文件开|关` 控制总开关。
- **长期记忆 · 滑窗 + 分批合并（v1.6 重写）**：只需设置一个「上下文窗口轮数 N」（默认 20），它既是发给 AI 的可见上下文（最近 N 轮）、也是一批压缩的轮次。每满 N 轮新对话就把这一批折叠成摘要并与旧记忆合并，**原始历史完整保留、窗口不重置**；更早的批次滑出窗口后继续对新的 N 轮总结合并，所有对话都会被覆盖，兼顾连续性与完整性。
- **摘要篇幅自适应**：折叠时按「既有摘要 + 新批次」体量自适应估算，**下限 300、内部硬上限 3000 字**，信息少从简、信息多可展开。
- **可编辑人格提示词**：默认人设为可爱甜美的 **DeepSeek 娘**；人格提示词可在「更多设置 → 角色人设」修改，或微信发 `/人设 你的描述`，留空恢复默认。其余模板（多条消息、记忆总结）内置固定，避免误改。
- **多条消息（v1.5.1 强化）**：想拆多条就**必须**用单个半角反斜杠 `\` 分隔（内置正/反例、纠正换行与全角等错误写法、普通回复不拆、最多 6 条），App 按真人节奏依次发送；`\` 后接小写字母（如 LaTeX `\frac`）不会误拆。
- **思考过滤 + 撤回拦截**：只取 `RESPONSE` 片段（自动过滤思考过程）；当回复被官方撤回 / 内容过滤时，用本地缓存的真实内容兜底。
- **全屏沉浸 + 深色跟随（v1.5）**：edge-to-edge 全屏（延伸到状态栏/手势条），控制台与 DeepSeek 网页都跟随系统浅色/深色自动切换。
- **后台保活（v1.6 强化）**：前台服务 + WakeLock + 电池白名单 + 9 分钟 AlarmManager 心跳 + 看门狗自愈 + 划掉重启 + 开机自启，并新增**无障碍保活**（系统绑定、检测服务被回收/卡死自动拉起）；**常驻通知实时显示 AI 阶段、微信在线状态与收发条数/时间**，附「重启桥接」动作，内置 vivo 自启动 / 后台高耗电 / 卡片锁定指引。
- **可观测**：控制台实时状态、自检诊断、每个用户的记忆管理（压缩 / 查看摘要 / 清空）、运行日志，微信内 `/状态 /帮助` 等指令。

## 工作原理

```
微信消息（文本/语音/图片/文件）
   │  iLink 长轮询 getupdates；图片/文件走 CDN 下载 + AES-128-ECB 解密
   ▼
本地构造 Prompt（人设 + 时间 + 长期记忆摘要 + 上下文窗口 + 当前消息/附件说明）
   │  附件：base64 → File → 注入官网 <input type=file>，等待官网 upload_file/fetch_files 完成
   │  文本：原生 setter 填入输入框 → 等待发送按钮可用 → 点击发送
   ▼
WebView 内 hook XHR/fetch，解析 /api/v0/chat/completion 的 SSE 流
   │  只取 RESPONSE、过滤 THINKING、拦截撤回
   ▼
Markdown 清洗为微信友好纯文本 → 按 \ 拆多条 / 按长度切块 → sendmessage 发回微信
```

DeepSeek 侧的所有动作都发生在你自己登录的网页里：不内置 API Key、不绕过登录、不伪造会话。

## 微信指令

| 指令 | 作用 |
| --- | --- |
| `/帮助` `/help` | 使用帮助 |
| `/状态` `/status` | 桥接与记忆状态 |
| `/压缩` `/compress` | 立即压缩本用户记忆 |
| `/重置` `/reset` | 清空本用户对话与记忆 |
| `/人设 描述` | 查看 / 修改角色人设 |
| `/多条开|关` | 多条消息模式开关 |
| `/文件开|关` | 图片/文件转发 DeepSeek 开关（v1.4） |

## 技术栈与目录结构

- 原生 Android（Java 8），`minSdk 24 / targetSdk 34`，无 AndroidX/AppCompat 依赖，UI 纯代码构建。
- 二维码：[ZXing core](https://github.com/zxing/zxing)。
- 网络：`HttpURLConnection`；加解密：`javax.crypto`（AES/ECB/PKCS7）；持久化：`org.json` + 内部存储。

```
app/src/main/
├─ assets/bridge.js            # 注入 DeepSeek 页面的桥接脚本（SSE 解析/防撤回/DOM 发送/附件注入）
└─ java/com/hiweny/deepbridge/
   ├─ MainActivity.java        # 双 Tab UI、全屏沉浸/深色、扫码、WebView、Prompt 工程/诊断/记忆管理
   ├─ BotService.java          # 前台服务：长轮询、指令、调度、带附件发送、看门狗自愈
   ├─ ConversationEngine.java  # 人设/上下文窗口/自适应摘要压缩/会话轮换/持久化
   ├─ KeepAlive.java           # AlarmManager 心跳与服务重启（v1.5）
   ├─ SystemReceiver.java      # 开机自启 + 心跳接收器（v1.5）
   ├─ DeepSeekController.java  # 原生 ↔ bridge.js 同步通道（reqId/latch），sendWithFiles
   ├─ ILinkClient.java         # 微信 iLink HTTP 协议
   ├─ WeChatMedia.java         # 微信 CDN 媒体下载 + AES 解密 + MIME 推断
   ├─ MediaPrepare.java        # 图片尺寸/体积治理，文档原样透传
   ├─ MediaFile.java           # 附件模型
   ├─ Util.java / Theme.java   # 工具与配色
.github/workflows/build.yml    # 打 tag 自动构建签名 APK 并发布 Release
```

## 自行构建

### 方式一：GitHub Actions（推荐）

推送 `v*` 标签即自动构建并创建 Release：

1. 仓库 Settings → Secrets and variables → Actions 添加：
   - `ANDROID_KEYSTORE_BASE64`：`base64 -w0 release.jks`
   - `KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`
2. `git tag v1.x.x && git push origin v1.x.x`，工作流产出已签名 APK 并挂到 Release。
3. 未配置密钥时会自动回退 debug 签名（仍可安装，仅不能跨签名覆盖升级）。

### 方式二：Android Studio / 命令行

```bash
# JDK 17 + Android SDK 34
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

需要正式签名时，在工程根目录放 `keystore.properties`：

```properties
storeFile=release.jks
storePassword=***
keyAlias=deepbridge
keyPassword=***
```

## 隐私与安全说明

- DeepSeek 登录态只存在于本机 WebView/Cookie；微信令牌、会话与记忆只保存在 App 内部存储，不上传任何第三方服务器。
- 图片/文件仅在「微信 CDN → 本机 → DeepSeek 官网」之间流转。
- 请遵守 DeepSeek 与微信的使用条款，本项目仅供学习与个人效率使用，不对账号风控负责。

## 许可证

[MIT](LICENSE)
