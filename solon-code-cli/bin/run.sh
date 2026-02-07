#!/bin/bash
# 切换到脚本所在的文件夹路径
cd "$(dirname "$0")"

# 运行 jar 包
java -Dfile.encoding=UTF-8 -jar SolonCodeCLI.jar