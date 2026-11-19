# CI 构建与签名配置

Beta 构建、Baseline Profile 自动更新和签名配置说明。

## 每五个 Beta 版本更新性能基线

每个分支单独计数。`Beta Build` 成功上传 APK 后，累计五个不同源码提交的成功版本会触发 `Baseline Profile`；失败、取消及同一提交的重跑不重复计数。首次启用从最近一次修改 `app/src/main/baseline-prof.txt` 的提交时间开始统计。模型元数据的每日同步任务不参与这个计数。

采集流程绑定触发版本的完整 SHA；冷启动和聊天两份规则都必须有效且包含未混淆应用符号，才合并去重并生成 profile 更新提交。随后运行全量单测并构建签名 Beta，检查 APK 内确有编译后的 profile，成功后才普通快进推送回原分支，并在本次 **Baseline Profile** 运行的 Artifacts 上传 `aicove-beta-<更新提交完整SHA>`。该产物含 APK 与 R8 mapping，不创建 GitHub Release。profile 更新提交不触发下一个采集循环。

`.github/baseline-cycle.json` 由自动流程维护，记录已计入的版本与触发 run；不要手工改计数。采集失败、规则损坏或新包构建失败不会覆盖远端旧 profile。采集期间若分支前进，旧结果丢弃；新版本成功构建后会排队采集当前源码。普通推送被分支保护拒绝时工作流会失败，不会强推绕过保护。

无需新增凭据：Beta 工作流使用内置 `GITHUB_TOKEN` 的 `actions: write` 权限触发采集，采集工作流使用 `contents: write` 回写，并复用下方签名 Secrets。手动运行 `Baseline Profile` 时不填写输入，仅采集 artifact；同时填写 `source_sha` 和 `beta_run_id` 才执行校验、回写和自动回包，输入必须对应同分支的成功 Beta。

自动化离线回归：`python3 scripts/test_baseline_cycle.py -v`。测试包含真实临时 Git 仓库快进推送；PRoot 环境的本地 Git 接收端可能报 `bad pack`，该用例仍需在 Ubuntu CI 上完整通过，不能跳过。

采集器沿用冷启动和聊天滑动场景；profile 中存在聊天符号不等于覆盖了所有长会话与工具链路，也不是帧率提升测量结果。

## 需要配置的 GitHub Secrets

仓库 `Settings → Secrets and variables → Actions → New repository secret`，添加 4 个：

| Secret 名称 | 取值 |
|---|---|
| `AICODE_KEYSTORE_BASE64` | `app/aicode.jks` 文件的 base64 编码（见下） |
| `AICODE_KEYSTORE_PASSWORD` | keystore 的 `storePassword` |
| `AICODE_KEY_ALIAS` | 签名 key 的 `keyAlias` |
| `AICODE_KEY_PASSWORD` | key 的 `keyPassword`（通常与 keystore 口令相同） |

`GITHUB_TOKEN` 由 Actions 自动注入，无需手动配置。

## 生成 keystore 的 base64

仓库根目录用 PowerShell 复制到剪贴板：

```powershell
[System.Convert]::ToBase64String([System.IO.File]::ReadAllBytes("app\aicode.jks")) | Set-Clipboard
```

粘贴到 `AICODE_KEYSTORE_BASE64`。

若还没有 keystore，先用 keytool 生成（别名/口令自定，回填到对应 Secret）：

```powershell
keytool -genkeypair -v -keystore app\aicode.jks -alias aicode -keyalg RSA -keysize 2048 -validity 10000
```

> `aicode.jks` 与 `keystore.properties` 都在 `.gitignore` 里，不会入仓库，本地签名不受影响。
