````md
# Codex 全局开发规则

## 1. Git要求

### 1.1 提交信息规范
- 以后所有 Git 提交信息必须使用中文，不允许再使用英文提交标题。
- 提交信息继续使用 Conventional Commits 风格，格式统一为：`type(scope): 中文描述` 或 `type: 中文描述`
- 常用类型包括：`feat`、`fix`、`docs`、`refactor`、`style`、`chore`、`test`
- `type` 和可选的 `scope` 可以保留英文规范，但冒号后的正文说明必须是中文。
- 每次提交聚焦单一目标，避免把不相关修改混在同一个提交中。
- 提交前先执行 `git status`，确认暂存范围准确。
- 仓库中存在其他未完成改动时，只暂存本次任务相关文件。
- 未经用户明确要求，不执行 `git reset --hard`、`git checkout --`、强制推送等高风险操作。
- 只有在用户要求推送时，才执行 `git push origin <当前分支>`。

### 1.2 提交标题标准
- 以后提交标题默认以你项目中的这一类风格为标准：
  - `feat(settings): 新增可配置服务器地址与现代化设置页`
- 推荐写法：
  - `feat(settings): 新增设置页与服务器配置能力`
  - `fix(recording): 修复通话录音目录扫描失败问题`
  - `chore(build): 补充音频读取权限并更新打包配置`
- 不再使用以下风格：
  - `Add audio media permission for call recordings`
  - `Update bundled keepalive AAR`
  - `Capture MIUI call recording files`

### 1.3 提交后说明要求
- 提交完成后，必须向用户提供完整的提交说明，不能只回复“已提交”。
- 提交后的说明格式，默认以下面这类结构为标准：

  `Programmer-Lqh, 2 周前 (2026年3月24日 13:17)`

  `feat(settings): 新增可配置服务器地址与现代化设置页`

  `修改文件：manifest.json、pages.json、pages/index/index.vue、pages/setting/setting.vue、utils/api.js`

  `功能说明：`

  `新增设置页路由与首页设置入口`

  `将 SERVER_URL 改为本地可配置，默认值为 http://192.168.30.70:8014/sms/upload`

  `支持保存配置、连接测试、待重传数据管理与手动重传`

  `设置页改为现代化极简白色风格，并移除 emoji 图标`

  `更新应用版本与名称配置`

- 以后默认按这个标准向用户汇报提交结果，至少包含：
  - 提交信息
  - 提交哈希
  - 当前分支
  - 是否已推送
  - 修改文件
  - 功能说明
  - 是否执行验证

### 1.4 分支要求
- 默认在当前任务分支上工作，除非用户明确要求新建分支。
- 推送前确认本地分支与目标远程分支一致。
- 若存在自动化工作流依赖特定分支，优先遵循项目现有分支策略。

## 2. 当前项目技术栈

### 2.1 应用形态
- 项目是一个 `uni-app` Android 应用，不是普通 `Vue 3 + Vite` Web 项目。
- 前端页面基于 `Vue 2` 单文件组件开发，页面入口由 `pages.json` 管理。
- 应用主要运行在 HBuilderX / uni-app App 端环境，依赖 `APP-PLUS` 能力。

### 2.2 前端层
- 页面技术：`.vue` 单文件组件
- 页面管理：`pages.json`
- 全局入口：`App.vue`、`main.js`
- 样式方式：页面内 `scoped style` + 全局 `uni.scss`
- 本地存储：`uni.setStorageSync` / `uni.getStorageSync`
- 网络请求：`uni.request`

### 2.3 Native 能力接入
- 核心能力通过 `uni.requireNativePlugin('Capture-Keepalive')` 调用。
- 原生插件成品位于：
  - `nativeplugins/Capture-Keepalive/android/Capture-Keepalive.aar`
- 原生插件源码位于：
  - `android-plugin/keepalive`
- 原生插件使用：
  - `Kotlin`
  - Android `BroadcastReceiver`
  - Android `Service`
  - `ContentObserver`
  - `SubscriptionManager` / `TelephonyManager` / `CallLog`

### 2.4 Android 构建与发布链路
- 原生模块为 Android Library / AAR 形式。
- Gradle 工程位于 `android-plugin`
- GitHub Actions 工作流位于：
  - `.github/workflows/build-aar.yml`
- 工作流会构建 `keepalive` 模块，并自动回写新的 AAR 到：
  - `nativeplugins/Capture-Keepalive/android/Capture-Keepalive.aar`
- 因此前端 JS 改动通常可直接真机运行验证，但原生 Kotlin、Manifest、权限配置相关改动必须重新打包安装验证。

### 2.5 当前业务模块
- 短信监听与短信上传：
  - `utils/sms-monitor.js`
  - `utils/api.js`
- 未接来电记录：
  - `utils/missed-call-store.js`
  - `pages/missed-calls/index.vue`
- 通话录音记录：
  - `utils/call-recording-store.js`
  - `pages/call-recordings/index.vue`
- 设置页：
  - `pages/setting/setting.vue`

## 3. 与本项目协作时的默认认知

### 3.1 项目定位
- 这是一个以 Android 设备监听为核心的 uni-app 工具型项目。
- 重点不是 Web 官网展示，而是短信、来电、录音等设备侧能力接入。

### 3.2 协作原则
- 优先复用现有 uni-app 页面结构和原生插件链路。
- 不随意引入新的大型前端框架或状态库。
- 修改原生能力时，默认同时考虑权限、AAR 构建、插件回写和重新打包验证。
- 以后只要改动涉及需要重新打包的软件内容，我应默认继续帮用户提交、推送并跟进构建状态。

### 3.3 注释规范
- 注释重点说明“为什么这样做”以及“边界条件”。
- 注释默认使用中文，保持简洁准确。
- 对系统兼容、机型差异、权限限制、目录规则、时间窗口匹配等逻辑优先补充注释。
- 不添加逐行翻译式注释，不保留大段注释掉的旧代码。
````
