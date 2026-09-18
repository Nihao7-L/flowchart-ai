#!/bin/bash
# 后端启动（沙箱里 mvn 命令的 MSYS 路径转换会坏，改用 java 直启 Maven launcher）
exec > /tmp/backend.log 2>&1
M2="D:/maven-home/apache-maven-3.9.5-bin/apache-maven-3.9.5"
# 沙箱注入了 SERVER__PORT=61150（= WorkBuddy 服务代理端口，已被宿主占用），会被 Spring 松散绑定成
# server.port → 启动必报 "Port 61150 was already in use"。只在启动 shell 里 unset，不动项目配置。
unset SERVER__PORT SERVER_PORT
cd "F:/ProgramData/IDEA/flowchart" || exit 1
echo "START $(date)"
java -classpath "$M2/boot/plexus-classworlds-2.7.0.jar" \
  -Dclassworlds.conf="$M2/bin/m2.conf" \
  -Dmaven.home="$M2" \
  -Dmaven.multiModuleProjectDirectory="F:/ProgramData/IDEA/flowchart" \
  org.codehaus.plexus.classworlds.launcher.Launcher -B spring-boot:run
echo "EXIT=$? $(date)"
