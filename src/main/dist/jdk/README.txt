此目录用于存放 Java 运行时（JDK 或 JRE），手动放置。

要求：
- Windows: 存在 jdk\bin\java.exe
- Linux/macOS: 存在 jdk/bin/java

建议直接把 JDK 解压后的内容放到这里，例如：
scheduler/
|-- conf/
|   `-- scheduler/
|           `-- tasks.yaml    (外部任务配置，启动时读取)
|-- jdk/
|   |-- bin/
|   |   |-- java.exe (或 java)
|   |   `-- ...
|   `-- ...
|-- server/
|   `-- task-scheduler.jar
|-- start.bat
`-- start.sh
