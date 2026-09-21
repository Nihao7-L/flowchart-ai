# 模块计划 · 本地编排 / 基础设施（并行底座轨 M12）

> 状态：规划中（**Redis 阶段已落地 2026-09-20；PostgreSQL / pgvector 推迟至 RAG（M2）**）
> 关联架构：`docs/architecture.md` 2.3 部署视图（本模块即部署视图的改变项）；**不新增 Java 包、不改包依赖方向**
> 所属阶段：**并行底座轨**（不占 S 顺序槽位；**硬前置**：S6（M2 / pgvector 向量库）与 S7（M5 / Redis 持久化）开工前须就绪，见 `roadmap.md`）
> 覆盖任务：v2-48 ~ v2-50（Redis 阶段 v2-48/v2-49 已落地；Postgres 阶段 v2-48b/v2-49b 推迟至 RAG）
> 方向来源：用户 2026-09-20 要求把本地依赖容器化——用 `redis:7.4-alpine` 替代本机 Windows Redis **3.0**；用 `pgvector/pgvector:pg16` 提供 PostgreSQL + pgvector（持久化 + RAG）。目标是**"新克隆 `docker compose up -d` 即用"** + 方便后期迁移。
> **2026-09-20 用户范围裁定**：**Redis 依赖（缓存）现在加 + 现在用 Docker；PostgreSQL / JDBC 依赖与 pgvector 服务推迟到 RAG（M2）**。故本模块拆为两阶段，互不阻塞。

## 元信息
- 模块编号：M12（基础设施轨：不对应业务代码包，**不进 `coreFun.md` 功能清单**）
- 状态：规划中（Redis 阶段已落地）
- 创建：2026-09-20 + AI；**Redis 阶段落地：2026-09-20**

## 目标
把"本机手工装 / 手工起依赖"换成**一条 `docker compose up -d` 起齐 Redis 与（RAG 阶段）PostgreSQL+pgvector**，且**新克隆仓库的人不改任何系统设置、不放密钥进镜像就能起**：

- **Redis（热缓存）**：`redis:7.4-alpine`，替代本机 Windows Redis **3.0**（跨 7 个大版本）；开 AOF 持久化，命名卷存盘。**✅ 本阶段已编排。**
- **PostgreSQL + pgvector（持久化 + RAG 向量库）**：`pgvector/pgvector:pg16`（Windows 上装 pgvector 扩展很痛苦，官方镜像一键解决）；容器首启自动 `CREATE EXTENSION vector`。**⏳ 推迟至 RAG（M2）阶段**。
- 应用侧连接信息**随仓库提交**（非密钥）；密钥仍只走 `.env`（gitignored）。
- 为 **M2**（RAG 向量库）与 **M5**（会话持久化）铺好底座，二者落地时直接连，不再各自折腾本机安装。

## 边界
- **允许新增**：`docker-compose.yml` 里的基础设施服务（`redis` / `db`）、`docker/initdb/01-vector.sql`、`application-docker.yml`、`.dockerignore`、`.env.example` 补条目、`docker/README.md`（一键用法）
- **允许修改**：`Dockerfile`（如需要）、`pom.xml`（加 `spring-boot-starter-data-redis` ✅已加；`postgresql` 驱动 / `spring-boot-starter-jdbc` ⏳RAG 阶段加 —— **属网络下载，按协作红线须先征得用户同意**）
- **禁止改动**：
  - `application.yml`（含明文 key，`gitignore` 第 40 行**精确忽略**，不进库）；**禁止把 key 写进任何提交文件**
  - **任何系统级环境变量 / 系统设置**（`constraint.md` §一）—— 容器内的 `.env` 变量属**项目级**配置，不算系统级 env 变更
  - **禁止把 Redis 变成硬依赖**：必须保留"连不上即降级内存"（`constraint.md` §三）
  - 禁止引入需专人运维的组件（K8s / 独立 MQ / 观测栈，`constraint.md` §二）
- **不做**：多实例 / 多租户 / 生产级高可用（维持单进程单实例，`architecture.md` 2.3）

## 分层边界
**纯部署面**：只改三样——编排文件（compose）、启动配置（新 profile 的 yml）、依赖声明（pom）。**不新增 Java 包、不改 2.4 包表**。
业务代码接线归 **M5**（Redis 实现 / 持久层）与 **M2**（pgvector 向量库）；本模块只保证"**容器起得来、应用连得上、连不上能降级**"。
**理由**：把"基础设施"与"用它"分开，M2 / M5 可互不阻塞地推进；也避免基础设施被误当成一个业务能力塞进 `coreFun`。

## 前置
- Docker Desktop 可用（用户已就绪；镜像已拉，见下「镜像与 tag 说明」）
- **Redis 阶段**：已落地（依赖已加、compose 已编排），无需额外同意。
- **Postgres 阶段**：待用户同意新增 Maven 依赖（下载红线）

## 现状核对（2026-09-20 实读，不是推测）
- `docker-compose.yml`：**原只有 `flowai` 应用服务**；**✅ 本阶段已加 `redis` 服务 + `flowai` 的 `depends_on`/`SPRING_PROFILES_ACTIVE=docker`/`REDIS_*` 环境**。
- `Dockerfile`：两阶段（maven 编译 → jre 运行），`COPY src ./src` —— 会把本机 `application.yml`（含明文 key）烤进镜像层；而**新克隆没有 `application.yml`** → 镜像里没有 llm 配置。**✅ 本阶段靠 `.dockerignore` 排除 `src/main/resources/application.yml` 解决**（密钥只走运行时 env，不进构建上下文）。
- `pom.xml`：**原无 redis / postgresql / jdbc 依赖**；**✅ 本阶段已加 `spring-boot-starter-data-redis`**（BOM 管版本，无需显式 version）；`postgresql` / `spring-boot-starter-jdbc` ⏳RAG 阶段加。
- `.gitignore`：精确忽略 `src/main/resources/application.yml`、`.env`、`.env.*`，但**放行 `!.env.example`** —— "提交模板、忽略真身"是既定套路，直接复用。**✅ `.env.example` 已建（仅 `LLM_API_KEY=`）**。
- `constraint.md` §三：Redis 目前"可选依赖（未注册为服务，需手动启动）"，并写明"**生产化前需把 Redis 纳入部署**" —— 本模块即兑现这句（Redis 部分）。

## 三个「up 即用」缺口（不先解，"别人 up 即用"做不到）
1. **配置来源**：`application.yml` 不进库 → 新克隆无 `llm.base-url` / `model` 等**非密钥**配置，容器起不来。
   **解**：提交 `application-docker.yml`（`profile=docker`）；compose 里 `SPRING_PROFILES_ACTIVE=docker`。**⚠️ 本阶段 `application-docker.yml` 只含 redis 连接配置**（不覆盖本地 `application.yml` 的 llm 真实值，避免本地 docker 跑挂）；非密钥的 llm 默认值在 RAG 阶段随 datasource 一并补全。**密钥**仍由 compose 的 `LLM_API_KEY`（来自 `.env`）注入。
2. **密钥不进镜像**：`Dockerfile` 的 `COPY src ./src` 会把本机明文 key 打进镜像层。
   **✅ 解**：`.dockerignore` 排除 `src/main/resources/application.yml`（并确保 `application-docker.yml` 兜底），密钥只走运行时 env。
3. **依赖缺失**：`pom.xml` 无 redis/jdbc 驱动 → 加了服务也没用。
   **✅ Redis 依赖已加（用户同意，2026-09-20）**；`postgresql` / `spring-boot-starter-jdbc` ⏳RAG 阶段加（下载红线：须用户同意）。

## 决策落地（源自本模块，2026-09-20 G6 已拍板 + 范围裁定）
- **G6 · 会话持久化的存储分层 = 三层**：① **Redis 热层**（`flowai:session:<id>` 存当前 IR + 拖拽坐标 + 校验问题，ms 读写 + TTL，重启不丢、容量上限淘汰）；② **PostgreSQL 冷层**（`session`/`message`/`diagram` 三表，历史/快照长期留、重启还在）；③ **pgvector 向量层**（`embedding` 表，历史 IR + 提示词语义向量，供 RAG 召回）。M5 已按此重写（Redis 热 + PG 冷 + 写 embedding），M2 接 pgvector 召回侧。本模块只保证"容器起得来、应用连得上、连不上能降级"——不新增 Java 包。
- **2026-09-20 范围裁定**：**Redis 阶段现在做**（依赖 + compose 编排，已落地）；**PostgreSQL / pgvector 推迟至 RAG（M2）**——届时补 db 服务、`01-vector.sql`、datasource 配置与 `postgresql`/`jdbc` 依赖。

## 步骤

### ✅ Redis 阶段（已落地 2026-09-20）
1. **v2-48 编排落盘（compose + redis + 提交版配置）**：
   - `docker-compose.yml` 增 `redis` 服务：`image: redis:7.4-alpine`，`command: ["redis-server","--appendonly","yes"]`，`volumes: [redis-data:/data]`，`healthcheck: redis-cli ping`，端口 `6379:6379`；`flowai` 加 `depends_on`（`redis: condition: service_healthy`）与 `SPRING_PROFILES_ACTIVE=docker` + `REDIS_HOST/REDIS_PORT` 环境；顶部 `volumes: redis-data:`。
   - 新增 `src/main/resources/application-docker.yml`（**仅 redis 连接**：`spring.data.redis.host=${REDIS_HOST:redis}` / `port=${REDIS_PORT:6379}`；不写 llm.*，避免覆盖本地 `application.yml` 真实值）。
   - 新增 `.dockerignore`（排除 `src/main/resources/application.yml` 与 `.env`）、`.env.example`（仅 `LLM_API_KEY=`）、`docker/README.md`（一键用法 + 范围说明）。
   - **不提交任何密钥**。
2. **v2-49 Redis 依赖**：`pom.xml` 加 `spring-boot-starter-data-redis`（BOM 管版本，**无需显式 version**；用户同意下载，2026-09-20）。

### ⏳ Postgres 阶段（推迟至 RAG / M2）
3. **v2-48b 编排落盘（db + pgvector）**：`docker-compose.yml` 增 `db` 服务（`image: pgvector/pgvector:pg16`，`environment: POSTGRES_USER/PASSWORD/DB` 取自 `.env`，`volumes: [pg-data:/var/lib/postgresql/data, ./docker/initdb:/docker-entrypoint-initdb.d:ro]`，`healthcheck: pg_isready`）；`flowai` 加 `depends_on db`；新增 `docker/initdb/01-vector.sql`：`CREATE EXTENSION IF NOT EXISTS vector;`。**不提交任何密钥**。
4. **v2-49b Postgres 依赖 + 配置**：`pom.xml` 加 `postgresql` 驱动 / `spring-boot-starter-jdbc`（**下载红线：须用户同意**）；`application-docker.yml` 补 `spring.datasource.url/username/password`（`password: ${POSTGRES_PASSWORD}`）；`.env.example` 补 `POSTGRES_USER/POSTGRES_PASSWORD/POSTGRES_DB`；`docker/README.md` 补 db 段。
5. **v2-50 迁移与降级验证**：
   - Redis 连不上仍降级内存（`constraint.md` §三不破）；验证 pgvector 扩展已启用（`\dx` 见 `vector`）；
   - 记录"本机 Windows Redis **3.0** → 容器 **7.4**"的迁移步骤与旧数据处置（RDB 迁移 or 明确丢弃，**不静默丢**）；
   - 端口可经 `.env` 覆盖（避开宿主 8080/6379/5432 冲突与沙箱 `SERVER__PORT` 坑）。

## 验收口径
1. ✅（Redis 阶段）`docker compose up -d` → `docker compose ps` 见 `redis` healthy、`flowai` 连上 redis（日志可见连接）。
2. ⏳（Postgres 阶段）`docker compose ps` 三个服务全 healthy，`flowai` 连上 redis 与 db；`\dx` 列出 `vector`。
3. ✅（Redis 阶段）`docker compose exec redis redis-cli info server | grep redis_version` ≥ **7.4**。
4. **降级不破**：`docker compose stop redis` 后应用仍能启动（当前无 Redis 业务读写；M5 落地后走内存会话，无异常退出）。
5. **镜像不含明文 key**：`docker history <image>` / 容器内 `ls /app` 无 `application.yml`（或其中无 key）—— 由 `.dockerignore` 排除保证。
6. 门禁不受影响：`run-verify.ps1` 仍绿（第 ① 层不启容器、不依赖 docker）。

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常·一键起（Redis） | `docker compose up -d` | redis healthy，flowai 连上 |
| 新克隆（Redis） | 空目录 clone → 按 `docker/README` 三步 | 不报"缺配置"，能起（llm 配置来自本机 application.yml 或 RAG 阶段补齐） |
| 降级（Redis） | `docker compose stop redis` | 应用不崩，会话降级内存 |
| 扩展（Postgres，⏳） | `psql -c '\dx'` | `vector` 在列 |
| 端口冲突 | 宿主 8080/6379/5432 被占 | 由 `.env` 改端口，容器不硬失败 |
| 迁移（⏳） | 旧本机 Redis 3.0 数据 | 明确处置（RDB 迁移或明确丢弃），不静默丢 |

## 镜像与 tag 说明（与用户截图核对，2026-09-20）
- 用户本机已拉：`redis:7`（169.61 MB，**非 alpine**）+ `pgvector/pgvector:pg16`（620.97 MB）。
- 本计划按用户口径统一写 **`redis:7.4-alpine`**。这与本机已有的 `redis:7` 是**两个不同 tag**：采用 7.4-alpine 时本机会**再拉该 tag**（更小）；若想省一次下载，也可退用已有 `redis:7`（但非 alpine、与"统一 7.4-alpine"口径不一致）。**建议采用 `redis:7.4-alpine`**，并顺手 `docker image rm redis:7` 清冗余 tag。
- `pgvector/pgvector:pg16` 与用户已有镜像**一致 → 零额外下载**（RAG 阶段才用到）。

## 完成后
移入 `state/completed/masterPlan/`；`state/activeLog/<YYYY-MM-DD>.md` 追加一行带证据。

## 状态跟踪
- [x] v2-48 compose 基础设施服务（`redis:7.4-alpine`）+ `application-docker.yml`（仅 redis）+ `.dockerignore` + `.env.example` + `docker/README.md` ✅ 已落地 2026-09-20
- [x] v2-49 `pom.xml` 加 `spring-boot-starter-data-redis` ✅ 已落地 2026-09-20（用户同意下载）
- [ ] v2-48b compose `db` 服务（`pgvector/pgvector:pg16`）+ `docker/initdb/01-vector.sql` ⏳ 推迟至 RAG（M2）
- [ ] v2-49b `pom.xml` 加 `postgresql` / `spring-boot-starter-jdbc` + `application-docker.yml` datasource + `.env.example` POSTGRES_* ⏳ 推迟至 RAG（M2，须用户同意下载）
- [ ] v2-50 迁移与降级验证（Redis 连不上降级内存 / pgvector 扩展启用 / 端口可配）⏳ Postgres 部分推迟至 RAG
