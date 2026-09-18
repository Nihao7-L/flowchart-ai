#!/bin/bash
# 后端测试与静态检查（沙箱里 mvn 命令会坏，改用 java 直启 Maven launcher）
exec > /tmp/mvn.log 2>&1
M2="D:/maven-home/apache-maven-3.9.5-bin/apache-maven-3.9.5"
cd "F:/ProgramData/IDEA/flowchart" || exit 1
echo "START $(date)"
java -classpath "$M2/boot/plexus-classworlds-2.7.0.jar" \
  -Dclassworlds.conf="$M2/bin/m2.conf" \
  -Dmaven.home="$M2" \
  -Dmaven.multiModuleProjectDirectory="F:/ProgramData/IDEA/flowchart" \
  org.codehaus.plexus.classworlds.launcher.Launcher -B clean test
echo "TEST_EXIT=$?"
java -classpath "$M2/boot/plexus-classworlds-2.7.0.jar" \
  -Dclassworlds.conf="$M2/bin/m2.conf" \
  -Dmaven.home="$M2" \
  -Dmaven.multiModuleProjectDirectory="F:/ProgramData/IDEA/flowchart" \
  org.codehaus.plexus.classworlds.launcher.Launcher -B checkstyle:check
echo "CHECKSTYLE_EXIT=$?"
echo "DONE $(date)"
