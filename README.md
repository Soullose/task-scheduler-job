# task-scheduler

基于 Spring 调度与虚拟线程的轻量级进程内任务调度框架。通过 YAML 定义任务，支持两种调度模式：Spring 六字段秒级 Cron（按墙钟网格触发）与固定间隔 `interval`（PeriodicTrigger，如每 200 秒），在虚拟线程中执行业务逻辑，并提供防重、超时、重试、执行历史与优雅停机等能力。

## 功能特性

- **DB 优先 + YAML 兜底的任务定义**：默认（`scheduler.task-source: auto`）启动时先读 PostgreSQL `t_scheduler_job` 任务表，校验后注册 `enable=true` 的任务；表为空或读库失败时自动整份兜底读取 `scheduler.task-config-location`（默认 `classpath:scheduler/tasks.yaml`）中的任务。可配 `scheduler.task-source: yaml` 强制只读 YAML（跳过 DB）。
- **秒级 Cron 调度**：基于 Spring 6 六字段 Cron 表达式（秒 分 时 日 月 周），支持配置调度时区。
- **固定间隔调度（interval）**：任务可配置 `trigger: interval` + `interval`（如 `200s`、`3m`、`2h`）改用 PeriodicTrigger 按固定周期触发——注册后一个 `interval` 才首次触发（**不会注册即执行**），之后每 `interval` 执行一次；如需启动立即执行一次可配 `run-on-startup: true`。推进方式可选 `interval-mode: rate`（默认，节奏不漂移）或 `delay`。支持 cron 表达不了的任意秒级周期（如每 200 秒）。
- **启动即执行**：任务可配置 `run-on-startup`，程序启动后立即执行一次（cron 与 interval 任务均适用；interval 任务的周期首次触发在其后一个 interval，二者不重叠），适合启动时的缓存预热、数据初始化等场景。
- **虚拟线程执行**：任务业务逻辑运行在虚拟线程中，不占用平台触发线程，适合 IO 密集型任务。
- **并发防重**：通过 Semaphore 闸门控制，默认不允许同一任务重叠执行（可按任务或全局覆盖）。
- **超时中断**：单次执行超过 `timeout` 通过 `FutureTask.cancel(true)` 真正中断业务虚拟线程并记录 `TIMEOUT`。
- **失败重试**：按 `max-retries` 顺序重试；任务异常被隔离，不影响调度器与其他任务。
- **执行记录持久化**：每次触发生成一条终态执行记录（SUCCESS / FAILED / TIMEOUT / SKIPPED / INTERRUPTED），通过 JPA 持久化到 PostgreSQL `t_job_execution`。
- **终态记录幂等**：同一次触发由 `AtomicBoolean` 保证只落一条终态记录，超时取消与业务线程补跑不会产生重复记录；成功记录写入真实尝试次数。
- **优雅停机**：容器关闭时取消调度、等待虚拟线程收尾，超时后强制中断。
- **Spring 容器 Handler 优先**：按 bean 名称 / 目标类名 / 实现类名匹配容器内 `ScheduledTaskHandler` Bean（兼容 AOP 代理），未实现接口的普通类走反射兜底。

## 环境要求

| 依赖 | 版本 |
| --- | --- |
| JDK | 21 |
| Spring Boot | 4.1.0 |
| Maven | 3.9+（仓库自带 `mvnw` / `mvnw.cmd`） |

## 快速开始

```bash
# Windows
mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

启动后，调度器会自动加载 `src/main/resources/scheduler/tasks.yaml` 中的 5 个示例任务（其中 `job_execution_cleanup` 默认启用）并按各自的调度触发：

- `data_sync`：每秒触发（秒级 cron），模拟数据同步（示例执行约 2 秒）；
- `sample_task`：每 2 秒触发（秒级 cron），输出 executionId 与参数；
- `cache_cleanup`：每 3 秒触发（秒级 cron），输出日志；
- `job_execution_cleanup`：每天 00:00（`cron 0 0 0 * * ?`）物理清理 `t_job_execution` 中 `SUCCESS`
  且 `end_at` 早于「当前时间 - 12 小时」的记录（保留最近 12 小时，每批 1000 条循环删除，非 SUCCESS 记录不清理）；
- `heartbeat_interval`：`interval: 200s` 固定间隔触发（PeriodicTrigger 模式，每 200 秒一次）。

运行测试：

```bash
mvnw.cmd test
```

## 配置说明

调度相关配置集中在 `application.yaml` 的 `scheduler` 节点下：

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `scheduler.timezone` | 系统默认时区（示例为 `Asia/Shanghai`） | Cron 计算的基准时区 |
| `scheduler.shutdown-timeout` | `30s` | 优雅停机时等待任务收尾的时长 |
| `scheduler.scheduler-pool-size` | `2` | 触发线程池大小（平台线程，仅负责触发，不执行业务） |
| `scheduler.allow-concurrent` | `false` | 全局默认：是否允许同一任务重叠执行 |
| `scheduler.execution-history-size` | `1000` | 执行记录容量上限（当前未接入该属性，执行记录直接落库） |
| `scheduler.task-config-location` | 无（示例为 `classpath:scheduler/tasks.yaml`） | 任务定义 YAML 位置（DB 为空 / 读库失败 / 强制 `yaml` 时使用） |
| `scheduler.task-source` | `auto` | 启动任务源：`auto`（默认）先读 `t_scheduler_job`，空表/读库失败兜底 YAML；`yaml` 强制只读 YAML（跳过 DB） |

### 任务来源：`t_scheduler_job`（DB 优先）

`auto` 模式下调度进程对 `t_scheduler_job` **只读**，任务行由外部维护（手工 SQL / 将来的管理端），
灌入数据后**重启进程**即以 DB 为准（运行期不重读；enable/disable/手动触发等运行期控制仍只改内存）。

建表/旧表升级/示例灌数脚本见 `docs/sql/t_scheduler_job.sql`。库行与 YAML 任务字段等价（能力不减），列包括：
`id`（即调度 taskId，必填）、`name`、`enable`、`trigger`(`cron`/`interval`，可空自动推断)、`cron`、`interval`、
`interval_mode`(`rate`/`delay`，缺省 `rate`)、`run_on_startup`(缺省 `false`)、`handler`、`description`(仅展示)、
`time_out`、`max_retries`、`retry_delay`、`allow_concurrent`(空=跟随全局默认)、`params`(JSON 对象文本)。
时延列与 YAML 同写法（`60s`/`200s`/`2h`/`PT1H`）。行数据不合法（坏 cron、无法推断 trigger、params 非 JSON 等）
会在启动时快速失败报错，**不**触发 YAML 兜底（兜底仅针对表为空/读库层失败）。

## 任务定义

任务在 `tasks.yaml` 中声明，字段说明如下：

| 字段 | 说明 |
| --- | --- |
| `name` | 任务名称，用于日志与执行记录 |
| `enabled` | 是否启用；启动时只注册 `enabled: true` 的任务 |
| `trigger` | 调度模式选择（必填）：`cron` 或 `interval` |
| `cron` | Spring 六字段秒级 Cron（秒 分 时 日 月 周），按墙钟网格触发；仅 `trigger: cron` 时配置 |
| `interval` | 固定间隔调度（如 `200s`、`3m`、`2h`，PeriodicTrigger）：注册后一个 `interval` 才首次触发（不会立即执行），之后每 `interval` 一次；如需启动即执行配 `run-on-startup: true`；仅 `trigger: interval` 时配置 |
| `interval-mode` | interval 的推进模式（可选）：`rate`（默认，fixed-rate：每次 = 上次计划触发时刻 + interval，节奏不漂移）或 `delay`（fixed-delay：每次 = 上次完成时刻 + interval） |
| `handler` | 处理器全限定类名 |
| `timeout` | 单次执行超时（如 `60s`），超时中断并记录 `TIMEOUT` |
| `max-retries` | 失败后的最大重试次数（默认 `0`，即不重试） |
| `retry-delay` | 重试间隔（字段已定义，当前实现未使用） |
| `allow-concurrent` | 任务级覆盖全局并发设置 |
| `run-on-startup` | 程序启动后是否立即执行一次（默认 `false`；enabled=true 时生效；cron 与 interval 均适用——interval 任务的周期首次触发在其后一个 interval，与本次即时执行不重叠） |
| `params` | 自定义参数，通过 `TaskContext.params()` 获取 |

> `taskId` 无需配置：YAML 加载时为每条任务生成 UUID；DB 优先时直接以 `t_scheduler_job.id` 作为 taskId。

示例：

```yaml
tasks:
  - name: "data_sync"
    enabled: true
    trigger: cron
    cron: "* * * * * ?" # 每秒触发（演示秒级 cron）
    handler: "com.w3.taskscheduler.jobs.handler.DataSyncHandler"
    timeout: 60s
    max-retries: 3
    retry-delay: 5s
    allow-concurrent: false
    params:
      source: "api"
      batch-size: 100

  - name: "sample_task"
    enabled: true
    trigger: cron
    cron: "0/2 * * * * ?" # 每 2 秒触发
    handler: "com.w3.taskscheduler.jobs.task.SampleTask"
    params:
      format: "pdf"

  - name: "cache_cleanup"
    enabled: true
    trigger: cron
    cron: "0/3 * * * * ?" # 每 3 秒触发
    handler: "com.w3.taskscheduler.jobs.handler.TestHandler"
    run-on-startup: true # 程序启动后立即执行一次（不依赖 cron）

  # interval 模式示例：每 200 秒执行一次（PeriodicTrigger）
  # interval 任务注册后一个 interval 才首次触发；如需启动即执行一次再配 run-on-startup: true
  - name: "heartbeat_interval"
    enabled: true
    trigger: interval
    interval: 200s
    interval-mode: rate # 可选：rate（默认）/ delay
    handler: "com.w3.taskscheduler.jobs.handler.TestHandler"
```

### cron 还是 interval？

- `cron` 描述的是**墙钟网格上重复的时刻**：`0 */3 * * * ?` 表示“第 0 秒、分钟为 3 的整数倍”，所以只会落在 `20:33:00`、`20:36:00` …，与任务何时注册无关；`run-on-startup` 只是额外在启动时补跑一次，不会改变后续触发的网格相位。
- `interval` 描述的是**相对注册时刻的固定周期**：注册后一个 interval 才首次触发（默认不立即执行，需要启动即执行配 `run-on-startup`），之后按 `interval-mode` 推进（默认 `rate`：每次 = 上次计划触发时刻 + interval）。程序 `20:30:30` 启动并配置 `interval: 3m`（`run-on-startup: true` 时先在启动时补一次），周期触发即为 `20:33:30`、`20:36:30` …（秒级相位保留，不会回到整秒）；配置 `interval: 200s` 即可得到 cron 无法表达的每 200 秒周期。
- `interval-mode: delay` 时每次 = 上次**完成**时刻 + interval（节奏会被执行耗时推动）；本框架中业务在虚拟线程异步执行、触发只负责提交（微秒级），因此防重叠仍由 `allow-concurrent: false` 闸门负责，两种 mode 都不会因业务耗时重叠执行。

> 需要“从整点对齐的墙钟网格”用 `trigger: cron`；需要“从启动/注册时刻起每 N 秒/分/小时”用 `trigger: interval`。同一任务只能选一种 trigger，相关字段（cron / interval、interval-mode）必须与 trigger 一致，否则启动校验报错。

## 编写任务

每个任务对应一个处理器，支持两种写法：

**方式一：实现 `ScheduledTaskHandler` 接口（推荐）**

将处理器声明为 Spring Bean，可正常注入依赖；`handler` 配置支持 bean 名称、目标类名或实现类名三种匹配方式：

```java
@Service
public class MyTaskHandler implements ScheduledTaskHandler {

    @Override
    public void execute(TaskContext ctx) throws Exception {
        String executionId = ctx.executionId();
        Object batchSize = ctx.params().get("batch-size");
        // 业务逻辑...
    }
}
```

**方式二：普通类提供 `execute(TaskContext)` 方法**

通过反射调用；若类已注册为 Spring Bean 则优先使用 Bean，否则走无参构造实例化：

```java
public class MyTask {

    public void execute(TaskContext ctx) throws Exception {
        // 方法签名必须为 execute(TaskContext)
    }
}
```

`TaskContext` 提供了以下信息：

| 方法 | 说明 |
| --- | --- |
| `executionId()` | 每次触发唯一，可用于幂等键 |
| `task()` | 当前任务的 `TaskDefinition` |
| `triggeredAt()` | 计划触发时刻 |
| `params()` | 任务定义的 `params` 自定义参数 |

## 项目结构

```text
src/main/java/com/w3/taskscheduler
├── TaskSchedulerJobApplication.java   # Spring Boot 启动类
├── admin/                             # REST 管理接口：任务增删改查/启停/触发/reload（dto/ 请求响应模型）
├── config/                            # 运行配置：触发线程池、虚拟线程执行器、scheduler.* 属性
├── core/
│   ├── config/                        # 任务配置加载与校验（DB t_scheduler_job / YAML 兜底 / 任务源编排）
│   ├── exec/                          # 任务执行封装：并发闸门、超时中断、重试、终态记录幂等（recordOnce）
│   ├── history/                       # 执行记录事件发布与持久化（JPA 写入 t_job_execution）
│   ├── invoke/                        # 处理器调用（Spring 容器 Bean 优先，兼容 AOP 代理；普通类反射兜底）
│   ├── model/                         # 任务定义、上下文、执行结果（Outcome）、执行记录、状态枚举
│   ├── persistence/
│   │   ├── entity/                    # JPA 实体（SchedulerJobPO、JobExecutionPO）
│   │   └── repository/                # Spring Data JPA 仓库
│   └── scheduler/                     # 调度服务、任务注册中心、生命周期、处理器接口
├── jobs/
│   ├── handler/                       # 示例处理器（实现 ScheduledTaskHandler）
│   └── task/                          # 示例任务（普通类 + execute 方法）
└── logback/                           # 自定义 Logback 颜色转换规则

src/main/resources
├── application.yaml                   # 调度器运行配置
├── logback-spring.xml                 # 日志配置（控制台 UTF-8 输出、ANSI 颜色）
└── scheduler/tasks.yaml               # 任务定义

src/main/dist                          # 发布目录素材（启动脚本与 jdk 说明）
├── start.bat                          # Windows 启动脚本
├── start.sh                           # Linux/macOS 启动脚本
└── jdk/README.txt                     # Java 运行时放置说明

src/assembly/dist.xml                  # Maven Assembly 描述符：mvn package 生成 scheduler/ 发布目录
```

执行 `mvn package` 后，项目根目录会生成可分发目录：

```text
scheduler/
├── conf/scheduler/tasks.yaml   # 外部任务配置（启动时读取，可离线修改）
├── jdk/                        # 手动放入 Java 运行时（JDK/JRE）
├── server/task-scheduler.jar   # Spring Boot 可执行包
├── start.bat                   # Windows 启动脚本（自动切换 UTF-8 控制台）
└── start.sh                    # Linux/macOS 启动脚本
```

进入 `scheduler/` 目录直接运行 `start.bat` / `start.sh` 即可启动服务；脚本基于自身所在目录定位 `jdk`、`server` 与 `conf`，不依赖当前工作目录。

## REST 管理接口

任务数据管理走 HTTP（**只写 `t_scheduler_job`，不写 task.yaml**）。增删改与启停写库成功后自动调用 `reload()` 增量生效；`/api/reload` 手动刷新（DB 无变化时 no-op）。请求/响应字段与表列一致（snake_case），`params` 为 JSON 对象。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/tasks` | 任务列表（读 DB 全部行，含 `enable=false`） |
| GET | `/api/tasks/{id}` | 任务详情 |
| POST | `/api/tasks` | 新建任务（`id` 可不传，服务端生成；重复 409；不合法 400）→ 自动 reload |
| PUT | `/api/tasks/{id}` | 整体替换更新（404 / 400）→ 自动 reload |
| DELETE | `/api/tasks/{id}` | 删除任务（404）→ 自动 reload |
| POST | `/api/tasks/{id}/enable` | 启用：写 DB `enable=true` → 自动 reload |
| POST | `/api/tasks/{id}/disable` | 停用：写 DB `enable=false` → 自动 reload |
| POST | `/api/tasks/{id}/trigger` | 手动触发一次（不改 DB） |
| POST | `/api/reload` | 手动 reload |

**reload 语义**：按 `scheduler.task-source` 重读任务源（auto：DB 优先，空表/读库失败兜底 YAML；yaml：强制 YAML），与当前调度做**全量快照 diff**——新增注册、消失注销、调度相关字段（trigger/cron/interval/interval-mode/handler/超时/重试/并发/params）变化则注销重注册；仅 `name`/`run_on_startup` 变化不重排；`run_on_startup` 只在进程启动时补跑，reload 不补跑；源数据不合法或读源失败（非兜底场景）快速失败，**调度保持原状**不部分生效。

> 示例：
> ```bash
> # 新建任务（写 DB 并立即生效）
> curl -X POST http://127.0.0.1:8080/api/tasks -H 'Content-Type: application/json' -d '{
>   "name":"data_sync","enable":true,"trigger":"cron","cron":"*/5 * * * * ?",
>   "handler":"com.w3.taskscheduler.jobs.handler.DataSyncHandler","time_out":"60s",
>   "max_retries":3,"params":{"source":"api"}}'
> curl -X POST http://127.0.0.1:8080/api/tasks/{id}/disable   # 停用（持久化）
> curl -X POST http://127.0.0.1:8080/api/tasks/{id}/trigger   # 手动触发一次
> curl -X POST http://127.0.0.1:8080/api/reload               # 手动 reload
> ```

## 当前状态与路线图

**已实现**

- YAML 任务加载与校验（`trigger: cron|interval` 必填、字段与模式互斥/合法性、handler 非空）
- 任务注册与取消：cron（CronTrigger，可配时区）与固定间隔（interval / PeriodicTrigger）两种调度模式
- 固定间隔调度：`interval` 任务注册后一个 interval 才首次触发（不立即执行，可配 `run-on-startup: true` 在启动时补一次即时执行），推进方式可选 `interval-mode: rate`（默认，每次 = 上次计划触发时刻 + interval）或 `delay`（每次 = 上次完成时刻 + interval），支持 cron 表达不了的任意秒级周期（如 200s、2h）
- 启动即执行：`run-on-startup: true` 的任务在程序启动后立即执行一次（cron 与 interval 任务均适用）
- 虚拟线程执行、防重闸门、超时真正中断（FutureTask）、失败重试、异常隔离
- 执行记录生成，并通过 JPA 持久化到 `t_job_execution`
- 终态记录幂等：一次触发只落一条终态记录（recordOnce + AtomicBoolean），成功记录写入真实尝试次数
- Spring 容器 Handler 调用：按 bean 名称 / 目标类名 / 实现类名匹配，兼容 AOP 代理，普通类反射兜底
- 优雅停机（取消调度 → 等待虚拟线程收尾 → 超时强制中断）
- 运行时任务控制：启用 / 禁用 / 手动触发 / 注销（服务层内存态接口；REST 启停/增删改为持久化写 DB 路径，见上文 REST 管理接口）
- 运行时 reload：`SchedulerService.reload()` 按任务源重读并做全量快照 diff 增量生效（新增注册/消失注销/字段变重注册，无变化零改动；`run_on_startup` 仅启动补跑）
- REST 管理接口：任务查询 / 增删改 / 启停 / 手动触发 / 手动 reload（只写 DB，写后自动 reload）
- 发布目录：`mvn package` 生成 `scheduler/`，含启动脚本、外部任务配置与独立 JDK 目录

**规划中**

- 执行历史查询 API（目前执行记录只写不查）
- `execution-history-size` 属性接入（当前执行记录直接落库，该属性未使用）
- `scheduler.shutdown-timeout` 属性接入（当前优雅停机等待时长在 `SchedulerLifecycle` 中写死为 30s）
- 虚拟线程存活数监控与告警（计划提供可扩展的 notifier 通知接口，接入日志 / 钉钉 / 邮件等渠道）

## 已知限制

- 任务运行时状态不持久化：执行记录已通过 JPA 落库（`t_job_execution`），任务注册/启停状态保存在进程内存中；重启后任务定义按 `scheduler.task-source` 重新加载——默认先读 `t_scheduler_job`（`enable=true` 的行），表为空/读库失败时兜底读取 YAML。执行历史目前只写不查，查询 API 尚未实现。
- 不支持集群/分布式：并发闸门基于进程内 Semaphore，多实例部署会重复执行任务。
- 执行记录持久化依赖 PostgreSQL，未配置数据源时应用无法正常启动。
- 并发闸门 Map 的 taskId 条目只增不减：任务注销后不会清理，长期运行会积累无用条目。
- cron 仅支持 Spring 六字段秒级格式（秒 分 时 日 月 周）；需要任意秒级周期或“从注册时刻起按相位锚定”的调度请改用 `interval` 模式。
- `interval` 任务的执行记录中 `cron` 快照为空（执行记录目前只保存 cron 字段，未落库 interval 调度信息）。
- 超时中断为协作式：`FutureTask.cancel(true)` 会真正中断业务线程，但业务代码若不响应中断（忽略 `InterruptedException` 或阻塞调用不抛异常），任务仍可能继续执行；终态幂等保证此时也不会重复落记录。
- 失败/超时记录的 `attempts` 仍为 0：`recordOnce` 已调用 `noteAttempt`，但共享 `AtomicInteger` 计数器尚未接入 `runWithRetry` 的重试循环，目前仅成功记录能写入真实尝试次数。
- `retry-delay` 字段已定义，但当前重试实现未使用重试间隔。
