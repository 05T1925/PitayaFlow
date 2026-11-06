# PitayaFlow

**开发预览版。** 当前已验证的是合成 WAV/MP4、ASR HTTP fixture、固定版本 DeepSeek Harness 与脚本模型组成的工程链。获准真实录音样本为 0，未调用真实 ASR 或付费模型供应商，真实面试效果尚未验证。四个固定 digest 镜像在清洁冷环境的获取结果为 0/4（registry 代理不可达）；合成链仅在本机缓存镜像下跑通。没有相同缓存的读者可能无法完成下方运行命令。此版本不是正式 MVP。

PitayaFlow 面向求学者和求职者，探索把私有音视频、转写和逐题面试复盘接在一起。当前工程演示覆盖 WAV/MP4 上传、转写任务、回听与修订、逐题证据、可选背景材料、练习卡、文字新版回答和文本导出。

这是一份工程原型演示，不代表已验证的面试产品。端到端示例使用合成媒体、本地 ASR 协议 fixture 和脚本模型，不衡量真实语音识别、模型质量或用户结果。请勿将敏感录音或个人材料上传到你无法控制的安装环境。

## 运行前提

文档所列本地运行方式面向 Windows 11 x64，依赖 PowerShell 7.6、Node.js 24、Java 21、Git、Docker Desktop 和 `ffmpeg`/`ffprobe`。首次运行会从网络获取 JavaScript 包、Maven 依赖、固定版本的 DeepSeek Harness 源码和固定 digest 的容器镜像；需要访问 GitHub、npm、Maven Central 和镜像仓库。具体版本与 digest 记录在锁文件、Maven Wrapper、运行脚本和 Compose 配置中。

项目把固定提交 `ce7073d51254c26cd63b22a60835b8f948deb447` 的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 作为外部 Agent 运行时。合成示例由本地脚本模型和受限只读来源工具驱动，不调用付费模型。真实 ASR 需要管理员提供 HTTPS 服务地址、API key 和模型名；未配置时应用会报告 ASR 不可用。下面的演示开关只启用本地协议 fixture，不能当作真实转写。

## 安装并运行合成示例

在全新工作副本中打开 PowerShell 7。每次运行都使用新的运行目录：

```powershell
$run = ".local/runtime-runs/run-demo-001"
pwsh -NoProfile -File scripts/runtime.ps1 -Action all -RunDir $run -UseAsrFixture
```

该命令会为本次运行生成隔离配置和随机本地凭据，安装锁定的 npm 与 Maven 依赖，获取并核对固定版 DeepSeek Harness，构建 Web 应用与后端，启动固定版 MySQL、Redis、MinIO 和 RocketMQ 镜像，再运行合成 WAV/MP4 与逐题复盘示例。fixture 不读取音频内容，只返回预先定义的合成文本；整个流程不会调用外部 ASR 或付费模型。无论演示成功或失败，`all` 都会尝试停止仅属于本次运行的进程和 Compose 项目。

也可以分步观察流程。所有步骤使用同一个新运行目录：

```powershell
pwsh -NoProfile -File scripts/runtime.ps1 -Action init -RunDir $run -UseAsrFixture
pwsh -NoProfile -File scripts/runtime.ps1 -Action build -RunDir $run
pwsh -NoProfile -File scripts/runtime.ps1 -Action start -RunDir $run
pwsh -NoProfile -File scripts/runtime.ps1 -Action health -RunDir $run
pwsh -NoProfile -File scripts/runtime.ps1 -Action demo -RunDir $run
```

演示脚本使用仓库生成的合成媒体和合成文本，覆盖上传、回听、转写修订、题目边界、证据检查、材料与发言来源分离、练习回答修订、取消和删除。脚本会报告自身的通过／失败结果；即使演示成功，也不代表真实 ASR 或面试复盘质量通过。

## 配置

运行脚本会在所选 `.local` 运行目录下生成本次专用配置。不要把配置复制进源码管理或分享其内容。真实 ASR 部署应通过密钥管理器或本地受保护配置提供 HTTPS `/v1/listen` 服务地址 `PF_ASR_ENDPOINT`、`PF_ASR_API_KEY` 和 `PF_ASR_MODEL`。不要把凭据写进命令行参数、截图、问题报告或导出文件。只有明确传入 `-UseAsrFixture` 时，示例才会启用本地 HTTP fixture。

仓库中的 [`deploy/s1-01/.env.example`](deploy/s1-01/.env.example) 仅含 `replace-with-local-random-value` 等占位文本，不是可用凭据。运行 `init` 会生成新的每次运行配置；不支持直接把该示例文件用于启动部署。

可选复盘模型配置与 ASR 相互独立。当前合成示例使用本地脚本模型；真实模型提供方配置不属于本演示范围。背景材料是可选评价背景，不作为用户说过的话。说话人身份未知或证据不足时会保留未确认状态。

## 停止与恢复

`all` 会自动停止本次运行。分步运行时，使用以下命令停止本次拥有的应用进程和容器：

```powershell
pwsh -NoProfile -File scripts/runtime.ps1 -Action stop -RunDir $run
```

使用 `-Action status` 查看本次运行的进程状态。停止操作会保留本次生成的凭据、日志和命名数据卷，以便复核与恢复。不要按进程名手动结束任务，也不要手动删除 Docker 卷。如果停止结果无法确认整个进程树已退出，保留运行目录并按运行脚本给出的恢复说明处理；可能仍被进程使用的临时文件不要删除。重新演示时使用新的运行目录和不同的 Compose project。

## 已知限制

- 示例使用脚本模型和合成媒体；没有调用真实 ASR 服务或付费模型。
- 本演示未验证语音识别准确率、说话人归属、面试结果或生产负载下的隐私行为。
- 目前验证过的本地运行方式是 Windows。其他操作系统、生产部署、多节点运行和冷缓存恢复需要另行验证。
- 本演示不表示完整面试复盘 MVP 或公开发布要求已经通过。

PitayaFlow 自身许可见 [LICENSE](LICENSE)。
