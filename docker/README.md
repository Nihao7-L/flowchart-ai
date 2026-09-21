# ChartFlow 本地 Docker 一键起（Redis 阶段）

> 本阶段只编排 **Redis（热缓存）**。`spring-boot-starter-data-redis` 已加入 `pom.xml`，
> `application-docker.yml` 提供 Redis 连接配置（`host=redis` 容器名）。
> 应用经 `RedisTemplate` 实际读写会话热层，待 **M5（S7）** 落地；当前仅把依赖与连接就绪。
> **PostgreSQL / pgvector（持久化 + RAG）推迟到 RAG（M2）阶段**。

## 前置
- 安装并启动 Docker Desktop。
- 镜像 `redis:7.4-alpine`：本机没有会自动拉一次（约 10~20MB，alpine 版）。

## 一键起
```bash
cp .env.example .env
# 编辑 .env，填入 LLM_API_KEY=你的key
docker compose up -d --build
```

## 验证
```bash
docker compose ps            # flowai + redis 均 healthy
docker compose logs flowai   # 看启动日志（应连上 redis 容器）
docker compose exec redis redis-cli info server | grep redis_version   # ≥ 7.4
```

## 降级（Redis 连不上）
当前 Redis 读写逻辑由 M5 落地；在此之前即使 Redis 不可用，应用启动也不受影响
（仅 `spring-boot-starter-data-redis` 自动配置的连接工厂，未被业务代码使用）。
M5 落地后会保证「连不上即降级内存」，详见 `docs/constraint.md` §三。

## 范围与后续（2026-09-20）
- ✅ 已做：pom 加 redis 依赖；compose 加 `redis` 服务 + `flowai` 依赖/环境；
  `application-docker.yml`（redis 连接）、`.dockerignore`（排除 application.yml/.env）、`.env.example`、`docker/README.md`。
- ⏳ 推迟到 RAG（M2）：加 `postgresql` / `spring-boot-starter-jdbc` 依赖、compose 的 `db` 服务、
  `docker/initdb/01-vector.sql`（建 pgvector 扩展）、`application-docker.yml` 的 datasource 配置、`.env.example` 补 `POSTGRES_*`。
  详见 `plans/masterPlan/m12-docker-infra.md`（v2-48b / v2-49b）。
