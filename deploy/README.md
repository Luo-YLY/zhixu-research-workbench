# 容器部署

此目录提供 Spring Boot、Vue 和独立 PostgreSQL 的 Compose 示例。当前产品面向单用户；应用入口只绑定服务器回环地址，远程使用时通过 SSH 本地转发访问。不要直接把端口发布到公网或局域网：应用尚无登录、用户角色和项目权限。

## 准备

1. 在目标服务器上安装 Docker 和 Compose，并核对 `deploy/compose.yaml` 中的镜像版本、数据挂载路径与端口。示例数据路径为 `/srv/appdata/research-workbench/`；部署前由管理员创建应用和数据库目录，分别赋予容器所需权限。
2. 在 `deploy/secrets/db_password` 保存随机生成的数据库密码，限制宿主机访问。该目录已被 Git 忽略。不要把密码写进 Compose、提交记录或命令行参数。
3. 在仓库根目录执行 `docker compose -f deploy/compose.yaml config -q`，确认配置有效，应用只发布在 `127.0.0.1:18081`，数据库没有发布端口。
4. 构建并启动此项目的服务：`docker compose -f deploy/compose.yaml up -d --build`。随后检查容器健康、Flyway 迁移和 `/api/system`。首次上线真实数据前，准备数据库和文件的配对备份并演练恢复。

## 结构与边界

- 应用容器以非 root 用户运行，使用只读根文件系统；持久文件放在 `/app/data`。PostgreSQL 使用单独的数据目录，不与其他项目共用数据库或卷。
- Compose 将数据库放在内部网络，将应用入口绑定到服务器的 `127.0.0.1:18081`。可通过 SSH 本地转发在自己的电脑访问，例如把本机 `18082` 转到服务器的 `127.0.0.1:18081`。
- 模型提供方默认 `disabled`。容器不会携带 Codex CLI、模型密钥、SSH 密钥或 Docker socket。启用模型前应单独审查服务地址、出站网络和凭据管理。
- 本地 H2 与服务器 PostgreSQL 是不同数据库；切换配置不会迁移记录。升级前保存数据库和应用文件备份；Flyway 迁移后的回滚须与数据库状态一并核对。

部署命令需要在你管理的服务器上执行。此文件不包含任何特定服务器的主机名、账户、历史故障日志或备份文件名。

## Windows 本地容器验收

本机使用 `compose.yaml` 加 `compose.local.yaml` 运行相同的应用和 PostgreSQL 镜像。覆盖文件只改本机端口、项目名与数据卷：页面为 `127.0.0.1:18085`，Docker 数据库默认为 `127.0.0.1:15432`，也可通过 `LOCAL_DB_PORT` 调整。两者只绑定本机回环地址。数据库另接一个用于端口转发的桥接网络，应用与数据库仍通过私有网络通信。数据库及应用文件保存在本机 Docker 命名卷，与服务器 `/srv/appdata` 完全独立。

先启动 Docker Desktop，再在仓库根目录运行：

```powershell
./scripts/local-docker.ps1 -Action config
./scripts/local-docker.ps1 -Action up
./scripts/local-docker.ps1 -Action status
```

脚本首次运行会在被 Git 忽略的 `.local/docker-db-password` 生成随机数据库密码，限制为当前 Windows 用户读取。停止容器用 `./scripts/local-docker.ps1 -Action down`；此命令保留数据卷，便于重启回读。不要用 `down -v`，除非明确要删除本机测试数据。Docker Desktop 的 WSL 数据磁盘位置应在首次启动前设到空间充足的磁盘。

本地构建前端和 Java 依赖时使用镜像源，以降低下载中断概率；npm 仍校验锁文件中的包哈希。服务器构建仍默认使用官方仓库。如果本地镜像源不可用，可调整 `compose.local.yaml` 的 `NPM_REGISTRY` 或 `MAVEN_MIRROR_URL` 后重试。Maven 依赖使用 BuildKit 缓存，重试时无需全部重新下载。

## 本机跨语言模型实验

优先使用 `compose.llama.local.yaml`：它在本机 Compose 内启动独立的向量与回答容器，模型端口不发布到宿主机，权重只读挂载。模型来自 Hugging Face，下载到 `.local/models/` 后运行 `-Action llama-config` 和 `-Action llama-up`。脚本会先按官方 SHA-256 检查两个 GGUF 文件，再构建应用并启动容器。`-Action llama-status` 查看状态。使用官方 `llama.cpp:server` CPU 镜像；首版先测准确性与延迟，GPU 镜像需另行评估。页面只监听 `127.0.0.1:18085`。

所需文件分别为 [BGE-M3 Q8_0](https://huggingface.co/ggml-org/bge-m3-Q8_0-GGUF) 的 `bge-m3-q8_0.gguf`（634,553,760 字节，SHA-256 `aa473d51f451a22f0fcf39ba3330c14bed38a385712b1113440f69df4047a173`），以及 [Qwen3-4B Q4_K_M](https://huggingface.co/Qwen/Qwen3-4B-GGUF) 的 `qwen3-4b-q4_k_m.gguf`（2,497,280,256 字节，SHA-256 `7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5`）。这两个名称是本地挂载名称；下载时应选择发布页上的对应 GGUF，核对字节数和哈希后再放入 `.local/models/`。该目录已被 Git 忽略，不应提交模型文件。

Docker Desktop 自带 Model Runner 仍是可选路径：在 Settings → AI 中开启 **Enable Docker Model Runner** 和 Windows NVIDIA 的 **Enable GPU-backed inference**；本项目只需容器访问，无须开启 host-side TCP。`-Action runner-config` 检查覆盖配置，`-Action runner-up` 依次下载模型并重建应用，`-Action runner-status` 查看状态。Windows 下 Model Runner 的推理进程属于 Docker Desktop 的沙箱，不在本项目 Compose 内；它的本地 API 没有认证，不要开放宿主 TCP。若显示引擎错误或 HTTP 502，应先修复 Docker Desktop，而不是重建应用。

原来的 Ollama 容器方案仍保留为备选：`-Action models-config`、`-Action models-up`、`-Action models-status`，停止用 `-Action models-down`。该方案另拉取约 3.6 GB 的运行镜像；若下载中断，不影响上述 Model Runner 路径。两种覆盖配置只选一种运行。

服务器的 `compose.yaml` 继续默认关闭模型。不要将本机模型覆盖文件用于服务器；将来部署服务器模型需要单独评估 GPU、内存、网络、数据许可和备份。文献双语实验的具体接口、索引方式与限制见 [文献与 RAG](../docs/LITERATURE-RAG.md)。
