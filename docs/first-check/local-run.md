# 本地启动、演示数据与测试

## 1. 准备工具

需要 JDK 21、Maven 3.9、Python 3。网页不需要 npm、Node 或前端打包。PowerShell 进入目标仓库，不能在其他课程项目执行这些命令。

```powershell
java -version
mvn -version
python --version
```

本次电脑默认 Java 是 17，实际验证使用 `C:\Users\YOLO\.jdks\ms-21.0.12.1`。其他电脑改成自己的路径：

```powershell
$env:JAVA_HOME = 'C:\Users\YOLO\.jdks\ms-21.0.12.1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -f third-party/clonefactory/pom.xml clean install
mvn clean verify
```

第一条 Maven 命令把仓库自带 CloneFactory 安装到本机 Maven 缓存；首次或修改处理器后执行。第二条编译、运行测试、生成 JAR 和 JaCoCo。`mvn test` 只运行测试，完整报告用 `verify`。若使用自定义 `-Dmaven.repo.local=...`，两条命令必须使用同一个缓存目录。

## 2. 启动隔离演示

```powershell
.\scripts\start-demo.ps1
```

输入自己的管理员用户名和密码（至少 8 字符，UTF-8 最多 72 字节）。脚本通过环境变量传给 Java，不把密码写入命令参数或文件。保持这个终端运行，打开 <http://127.0.0.1:18080>，登录后可手工创建用户、角色、系统与权限。端口被占用可用 `-Port 18081`，后续脚本也改对应地址。不得停止未知进程来抢端口。

`demo` profile 强制使用名为 first-check 的 H2 内存数据库和 create-drop，只监听 127.0.0.1，关闭 H2 控制台。不要与 prod profile 同时启用。未设置 JWT 密钥时进程内随机生成，不使用固定开发密钥；prod 配置仍要求通过环境变量提供专用密钥。

非 PowerShell 环境可在当前终端安全设置 `PERMISSION_BOOTSTRAP_USERNAME` 和 `PERMISSION_BOOTSTRAP_PASSWORD`，然后运行：

```text
java -jar target/nified-permission-system-0.0.1-SNAPSHOT.jar --spring.profiles.active=demo
```

脚本不自动下载 JDK，不自动部署，不连接现有服务器。

## 3. 一键准备和验证演示数据

另开终端，进入同一仓库：

```powershell
python scripts/demo-data.py --verify
# 如果改过端口：
# python scripts/demo-data.py --base-url http://127.0.0.1:18081 --verify
```

按提示输入管理员凭据，再设置两个普通演示用户共用的临时密码。脚本仅允许本机地址，通过管理员 API 创建数据；没有直接改数据库的后门。密码输入时不回显，报告不保存密码或 JWT。自动化也可通过 `PERMISSION_DEMO_PASSWORD` 等环境变量传入，但不要写入公共脚本或提交环境文件。

| 数据 | 初始状态 |
|---|---|
| demo_finance | 启用的财务演示系统 |
| expense:list | 查看固定报销示例的真实后端权限 |
| demo:detail | 仅用于展示权限并集，没有实现明细业务接口 |
| demo_viewer | 启用；拥有 expense:list |
| demo_reviewer | 启用；拥有 expense:list、demo:detail |
| demo_alice | 启用；上述两个角色，刚才输入的演示密码 |
| demo_bob | 启用；没有角色，刚才输入的演示密码 |

重复运行会把保留的 demo_* 数据恢复到上述状态，重设两个用户密码并撤销他们的旧会话。它不会删除全部用户或修改管理员密码；如果 expense:list 已归属其他系统，脚本报错而不覆盖。应在专用 demo 进程运行，不拿它初始化已有业务数据库。

`--verify` 实际执行 14 项 HTTP 场景。验证结束（包括断言失败）后尝试恢复演示基线；结果保存到 `docs/first-check/evidence/http-verification.json`。不加 `--verify` 只准备数据。若网络中断导致恢复失败，服务恢复后重跑即可。

## 4. 重启与持久化边界

Ctrl+C 停止演示后，H2 数据随 Java 进程消失。启动脚本再次创建管理员，运行数据脚本恢复基线。浏览器旧 JWT 在重启后不能使用，需重新登录。即使设置了固定 JWT 密钥，旧会话记录已消失，仍返回 401。

管理员重复初始化“不重置密码”的规则适用于同一个仍保留数据的数据库；H2 重启是全新数据库，不能把两者混为一谈。持久化 MySQL 配置与 SQL 已提供，但本轮没有连接或运行它们。

## 5. Postman

导入 `docs/postman/unified-permission-system.postman_collection.json`。为避免 expense:list 冲突，另起一个全新 demo 进程，只初始化管理员，不先执行数据脚本。填写本地环境变量 `baseUrl`、`adminUsername`、`adminPassword`、`password`，按集合顺序执行。管理请求用 adminToken，业务请求用普通用户 accessToken。重新执行前重启内存数据库并清空 demoSuffix。

Postman 导出环境可能含真实密码和 JWT，不应提交。集合的结构和两种 Token 约定已更新；HTTP 自动验收以 Python 脚本和 JUnit 为证据，不把它们冒充 Postman GUI 操作结果。
