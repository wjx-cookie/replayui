把 Flashback 的 jar 放到这个目录。

文件名任意，不用改名。整个 libs/ 目录下的 .jar 都会被拿来编译，
所以你下载下来是什么名字就用什么名字。

从官方发布页下载（Modrinth / CurseForge / flashback.moulberry.com）。

注意：
- 用的是 modCompileOnly，jar 只参与编译，不会被打进产物
- Flashback 是 ARR 许可，不能分发其代码，所以 jar 不随项目提供
   （.gitignore 已排除 libs/*.jar，不会误提交）
- 本模组只依赖 Flashback 自 0.39.0 起就稳定的那几个 API，
   0.39.9 / 0.39.10 都能编译
- 这个目录只放 Flashback 一个 jar。放别的 jar 会把它也编进编译期依赖
- 如果目录是空的，构建会直接报错提示，而不是堆一堆"找不到符号"
