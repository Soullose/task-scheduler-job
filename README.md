# task-scheduler

基于 Spring 调度与虚拟线程的轻量级进程内任务调度框架。通过 YAML 定义任务，使用 Spring 6 秒级 Cron 定时触发，在虚拟线程中执行业务逻辑，并提供防重、超时、重试、执行历史与优雅停机等能力。

## 功能特性

- **YAML 任务定义**：任务在 `classpath:scheduler/tasks.yaml` 中声明，启动时自动加载、校验并注册。
- **秒级 Cron 调度**：基于 Spring 6 六字段 Cron 表达式（秒 分 时 日 月 周），支持配置调度时区。
- **虚拟线程执行**：任务业务逻辑运行在虚拟线程中，不占用平台触发线程，适合 IO 密集型任务。
- **并发防重**：通过 Semaphore 闸门控制，默认不允许同一任务重叠执行（可按任务或全局覆盖）。
- **超时中断**：单次执行超过 `timeout` 自动中断并记录 `TIMEOUT`。
- **失败重试**：按 `max-retries` 顺序重试；任务异常被隔离，不影响调度器与其他任务。
- **内存执行历史**：记录每次执行的完整状态（RUNNING / SUCCESS / FAILED / TIMEOUT / SKIPPED / INTERRUPTED）。
- **优雅停机**：容器关闭时取消调度、等待虚拟线程收尾，超时后强制中断。
- **处理器反射调用**：优先从 Spring 容器获取处理器 Bean，兜底通过无参构造实例化。

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

启动后，调度器会自动加载 `src/main/resources/scheduler/tasks.yaml` 中的 3 个示例任务并按各自的 Cron 触发：

- `data_sync`：每秒触发，模拟数据同步（示例执行约 2 秒）；
- `sample_task`：每 2 秒触发，输出 executionId 与参数；
- `cache_cleanup`：每 3 秒触发，输出日志。

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
| `scheduler.execution-history-size` | `1000` | 内存执行历史容量（当前实现暂未接入该属性，容量写死 1000） |
| `scheduler.task-config-location` | 无（示例为 `classpath:scheduler/tasks.yaml`） | 任务定义文件位置 |

## 任务定义

任务在 `tasks.yaml` 中声明，字段说明如下：

| 字段 | 说明 |
| --- | --- |
| `name` | 任务名称，用于日志与执行记录 |
| `enabled` | 是否启用；启动时只注册 `enabled: true` 的任务 |
| `cron` | Spring 6 秒级 Cron（六字段：秒 分 时 日 月 周） |
| `handler` | 处理器全限定类名 |
| `timeout` | 单次执行超时（如 `60s`），超时中断并记录 `TIMEOUT` |
| `max-retries` | 失败后的最大重试次数（默认 `0`，即不重试） |
| `retry-delay` | 重试间隔（字段已定义，当前实现未使用） |
| `allow-concurrent` | 任务级覆盖全局并发设置 |
| `params` | 自定义参数，通过 `TaskContext.params()` 获取 |

> `taskId` 无需配置：加载时由系统为每条任务生成 UUID。

示例：

```yaml
tasks:
  - name: "data_sync"
    enabled: true
    cron: "* * * * * ?"
    handler: "com.w3.taskscheduler.handler.DataSyncHandler"
    timeout: 60s
    max-retries: 3
    retry-delay: 5s
    allow-concurrent: false
    params:
      source: "api"
      batch-size: 100

  - name: "sample_task"
    enabled: true
    cron: "0/2 * * * * ?"
    handler: "com.w3.taskscheduler.task.SampleTask"
    params:
      format: "pdf"

  - name: "cache_cleanup"
    enabled: true
    cron: "0/3 * * * * ?"
    handler: "com.w3.taskscheduler.handler.TestHandler"
```

## 编写任务

每个任务对应一个处理器，支持两种写法：

**方式一：实现 `ScheduledTaskHandler` 接口（推荐）**

将处理器声明为 Spring Bean，可正常注入依赖：

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
├── config/                            # 运行配置：触发线程池、虚拟线程执行器、scheduler.* 属性
├── core/
│   ├── config/                        # 任务配置加载与校验（YAML -> TaskDefinition）
│   ├── exec/                          # 任务执行封装：并发闸门、超时、重试、执行记录
│   ├── history/                       # 执行记录事件发布与持久化（JPA 写入 t_job_execution）
│   ├── invoke/                        # 处理器反射调用（Spring Bean 优先）
│   ├── model/                         # 任务定义、上下文、执行记录、状态枚举
│   ├── persistence/
│   │   ├── entity/                    # JPA 实体（JobExecutionPO）
│   │   └── repository/                # Spring Data JPA 仓库（ExecutionRecordRepository）
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

## 当前状态与路线图

**已实现**

- YAML 任务加载与校验（cron 合法性、handler 非空）
- CronTrigger 任务注册与取消、可配时区
- 虚拟线程执行、防重闸门、超时中断、失败重试、异常隔离
- 执行记录生成，并通过 JPA 持久化到 `t_job_execution`
- 优雅停机（取消调度 → 等待虚拟线程收尾 → 超时强制中断）
- 运行时任务控制：启用 / 禁用 / 手动触发 / 注销（服务层接口已实现）
- 发布目录：`mvn package` 生成 `scheduler/`，含启动脚本、外部任务配置与独立 JDK 目录

**规划中**

- `SchedulerService.reload()`：重载 YAML 并增量生效（当前为空实现）
- REST 管理接口：任务启停、触发、注销与执行记录查询暴露为 HTTP API（服务层已就绪，尚未接入）
- 执行历史查询 API（目前执行记录只写不查）
- `execution-history-size` 属性接入（当前执行记录直接落库，该属性未使用）
- `scheduler.shutdown-timeout` 属性接入（当前优雅停机等待时长在 `SchedulerLifecycle` 中写死为 30s）
- 虚拟线程存活数监控与告警（计划提供可扩展的 notifier 通知接口，接入日志 / 钉钉 / 邮件等渠道）

## 已知限制

- 任务运行时状态不持久化：执行记录已通过 JPA 落库（`t_job_execution`），但任务注册/启停状态仍保存在进程内存中，重启后按 YAML 重新加载；执行历史目前只写不查，查询 API 尚未实现。
- 不支持集群/分布式：并发闸门基于进程内 Semaphore，多实例部署会重复执行任务。
- 执行记录持久化依赖 PostgreSQL，未配置数据源时应用无法正常启动。
- 并发闸门 Map 的 taskId 条目只增不减：任务注销后不会清理，长期运行会积累无用条目。
- cron 仅支持 Spring 6 秒级格式。
- 超时中断不彻底：超时后 `future.cancel(true)` 只是标记取消，不会真正中断已运行的虚拟线程；业务代码可能继续执行到结束并补写一条记录，依赖业务自身响应中断。
- 失败记录的 `attempts` 恒为 0：`noteAttempt` 未被调用，失败/超时记录的尝试次数与成功记录口径不一致。
- `retry-delay` 字段已定义，但当前重试实现未使用重试间隔。
