# Fcitx5 增强插件 (fcitx5-enhanced)

LSPosed 模块，为 fcitx5-android 提供键盘美化（毛玻璃、圆角、按键描边）和快捷工具（IME 切换、剪贴板历史、语音输入）。

## 项目概述

- **描述**：Hook fcitx5-android（靓企鹅 fx 版 + 原版）输入法进程，注入键盘视觉效果与底部工具栏
- **技术栈**：Java 17 + Android Gradle Plugin 9.2.0 + libxposed-api 101
- **运行时**：Android 12+ (API 31+)，需 LSPosed 激活
- **仓库地址**：`git@github.com:rebron1900/fcitx5-enhanced.git`

## 目录结构

```text
app/src/main/java/com/rebron1900/fcitx5enhanced/
├── MainHook.java              — LSPosed 入口，Hook setInputView/onWindowShown
├── SettingsActivity.java      — 设置 UI（单页：主题美化参数）
├── ConfigContract.java        — 配置键、默认值和 Cursor/ContentValues 契约
├── ConfigProvider.java        — 跨进程 ContentProvider（模块 SP → fcitx5 进程）
├── FrostedGlassHelper.java    — 毛玻璃 + 键盘圆角（真实 BlurDrawable）
├── GlassBorderDrawable.java   — 玻璃渐变描边 Drawable（键盘/按键两种模式）
├── KeyEffectsHelper.java      — 按键半透明 + 描边（View 树遍历，哈希去重）
├── ExtraButtonsHelper.java    — 底部工具栏（IME/剪贴板/语音波形按钮）
├── PreeditHelper.java         — 预编辑文本圆角靠左
├── VoiceInputClient.java      — 语音输入 AIDL 客户端（手写 Parcel）
├── SvgIcons.java              — Lucide SVG 图标运行时渲染
└── WaveformLineView.java      — 录音波形动画

app/src/main/res/
├── layout/activity_settings.xml — 设置页布局（卡片式，深浅色适配）
└── values/                      — strings / xposed_scope 数组 / tag id
```

## 环境与依赖

- 构建：`./gradlew assembleRelease`，产物在 `app/build/outputs/apk/release/`
- 依赖：`com.caverock:androidsvg`（图标渲染）；`compileOnly` 本地 jar `app/libs/xposed-api-101.0.1.jar`
- 环境变量：`JAVA_HOME=~/tools/jdk-21`、`ANDROID_HOME=~/android-sdk`、`ANDROID_SDK_ROOT=~/android-sdk`（已安装，`local.properties` 已写 `sdk.dir`）
- 网络注意：本机直连 `dl.google.com`/`repo.maven.apache.org` 会卡 IPv6/DNS 间歇失败；构建时用 `GRADLE_OPTS=-Djava.net.preferIPv4Stack=true`，且 `~/.gradle/init.gradle` 已配置阿里云镜像替换（用户级，不入库）
- debug 签名 keystore：`~/.android/debug.keystore`（别名 `androiddebugkey`，密码 `android`）

## 架构

### 配置流（双进程）

```
SettingsActivity（插件进程）                    fcitx5 进程（被 Hook）
┌─ 模块 SharedPreferences ────────────────────┐
└─ ContentResolver.update(ConfigProvider) ───→  ContentProvider.query()
                                      └──────→  ContentObserver → 强制重应用
```

- `ConfigContract`：集中定义配置键、默认值、Provider URI 和 Cursor 格式
- `ConfigProvider`：模块 SP 是唯一持久化来源；写入仅允许模块包，读取仅允许目标输入法包
- 不再跨 UID 写入 fcitx5 的 externalFilesDir，也不再依赖易丢失的动态广播
- `MainHook.Config` 快照 `sLastAppliedCfg`：仅非强制调用时跳过全量重绘
- 主题信息（isDark/keyBgColor 等）由 MainHook 一次性反射提取，各 Helper 复用，不重复反射

### 视觉 Hook 关键流程

```
setInputView (hook) → LayoutChangeListener → applyAllEffects
  ├─ FrostedGlassHelper.apply   （BlurDrawable + 每个背景 View 独占 tint 位图）
  ├─ roundToolbarTop            （工具栏圆角，尺寸未就绪重试）
  ├─ PreeditHelper.apply        （单手模式跳过）
  ├─ ExtraButtonsHelper.add     （按钮 Tag 防重复）
  └─ KeyEffectsHelper.apply     （透明度+描边单次遍历）

onWindowShown (hook) → 重读配置 + applyAllEffects
```

- 模糊/描边查找大量依赖反射与字段名猜测（R8 混淆后字段名不可靠），Helper 内部有类型匹配与多路径回退
- `KeyEffectsHelper`：`appearanceView` 反射结果使用弱引用缓存；View 树有限深度 identityHashCode 检测内部按键结构变化

### 语音输入（仅 fx 版）

- 长按波形线录音 → 裸 `Binder.transact` 连接 bibi keyboard 的 `IExternalSpeechService`（事务码硬编码）
- 两次 onFinal：第一次原始文本进 composing，800ms 内无 AI 润色版则兜底提交；`mConsumed` 标记防重复提交
- 语音使用目标输入法进程自身的 RECORD_AUDIO 权限；未安装 fx 版时设置页隐藏语音开关

## 已知约束

- Android 12+ (API 31+)、LSPosed 已激活
- 目标包名 `org.fcitx.fcitx5.android.fx`（靓企鹅）或 `org.fcitx.fcitx5.android`（原版）
- Hook 目标类 `org.fcitx.fcitx5.android.input.FcitxInputMethodService` / `InputView`
- 升级安装前需先卸载旧版 APK（签名冲突）

## 代码规范

- Java 17；4 空格缩进；类/方法有中文注释说明意图
- 反射访问一律 try-catch 包裹并静默回退（多路径尝试），日志 TAG 统一 `Fcitx5Enh`
- 性能敏感路径（onDraw / layout 遍历 / applyAllEffects）避免重复分配与重复遍历，必要时用缓存（标注缓存失效条件）
- 不提交生成目录（`app/build/`）、`.gradle/`、密钥与 `.env`；`local.properties` 不入库

## Git 规范

- 默认分支：`main`
- Commit：Conventional Commits（`feat/fix/perf/chore/docs`，如历史 `perf: v1.9.0 — …`）
- 提交前：`git status` 确认无生成物混入

## 测试

- 无自动化测试框架；改动后验证方式为真机安装（LSPosed 激活 + 重启 fcitx5 进程）
- 本机可做的静态验证：`grep` 残留引用检查、XML 格式校验（`python3 -c "import xml.dom.minidom; xml.dom.minidom.parse(...)"`）
- 版本号同步：`app/build.gradle.kts` 的 `versionCode`/`versionName` 与 Release tag（`v*`）保持一致

## 构建与签名

```bash
# 构建
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release-unsigned.apk

# debug 签名
APKSIGNER=$ANDROID_HOME/build-tools/35.0.0/lib/apksigner.jar
$JAVA_HOME/bin/java -jar $APKSIGNER sign \
  --ks ~/.android/debug.keystore --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out <输出.apk> app/build/outputs/apk/release/app-release-unsigned.apk
```

- 交付产物放 `~/workspace/output/exports/`
- CI（`.github/workflows/build.yml`）：tag 推送时构建 + 用 GitHub Secrets 签名发布（发布密钥路径/别名见 `.env`，不写入本文件）

## CodeGraph

- 状态：未使用（WSL 未安装 `codegraph`）
- 初始化：`cd ~/workspace/projects/active/fcitx5-enhanced && codegraph init`
- 常用查询：

  ```bash
  codegraph status
  codegraph query <symbol>
  codegraph callers <symbol>
  ```
