@echo off
:: 切换到脚本所在的磁盘分区
chcp 65001 > nul
%~d0
:: 切换到脚本所在的文件夹路径
cd %~dp0

:: 执行 Java 命令
java -Dfile.encoding=UTF-8 -jar SolonCodeCLI.jar

:: 运行结束后暂停，防止窗口直接闪退，方便看报错或结果
pause