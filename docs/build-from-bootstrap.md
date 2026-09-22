# 从 bootstrap 构建说明

本仓库历史上部分 Java 源码仅存在于已部署 JAR 中。为恢复 `mvn clean package` 能力，采用 **bootstrap JAR + 增量编译** 策略。

## 前置条件

- JDK **17**（`/www/server/java/jdk-17.0.8`）
- `scripts/bootstrap/server-0.0.1-SNAPSHOT.jar` — 与线上一致的参考 JAR（更新大版本后需同步替换）

## 构建命令

```bash
./scripts/mvn-jdk17.sh clean package
# 或跳过测试
./scripts/mvn-jdk17.sh clean package -DskipTests
```

## 机制

1. `generate-sources` 阶段从 bootstrap JAR 解压 `BOOT-INF/classes` 到 `target/classes`
2. `maven-compiler-plugin` 排除 CFR 反编译、尚未清理的类型错误源码（见 `pom.xml` 中 `<excludes>`）
3. 其余源码（含 `UserWallet`、`StickerProperties` 等）正常编译并覆盖 bootstrap 中的 class
4. `src/main/resources/application.yml` 参与打包（含 sticker 清晰度配置）

## 后续

反编译恢复的 `.java` 应逐步手工清理并移出 `<excludes>`，最终目标是不依赖 bootstrap 亦可全量编译。
