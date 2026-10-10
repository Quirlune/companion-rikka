# 维护导航

当前规则集中在 [ARCHITECTURE](ARCHITECTURE.md)，具体修改入口和验收方法见 [DEVELOPMENT](DEVELOPMENT.md)。不要把历史“某代理拥有某文件”当作永久权限。

旧版本审计与阶段分工全文保留于 [历史记录](history/maintainability-through-0.57.txt)。发现看似重复的链路时，先查当前路由、调用方与旧执行版本的读取需求；只有证实不被使用的实现才删除。不要删除为了恢复旧存档而保留的解码字段，也不要为旧入口继续新增功能。
