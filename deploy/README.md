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
