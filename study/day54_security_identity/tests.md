# 必须会背会写的验收

- 写出 access/refresh token 的字段、校验顺序、轮换和撤销模型。
- 为题库详情、管理员发布和任务查询分别写对象级、功能级和 owner 授权判断。
- 写出 CORS、CSRF、Bearer 和 cookie session 的差异表；列出密码、token、答案和凭据的日志禁区。

# 额外测试与追问

- 设计 refresh token 重放、密钥轮换和用户主动退出的处理。
- 构造一个 IDOR 越权请求并指出 Service 层的修复位置。
- 说明文件上传、URL 抓取和 PDF 解析的 SSRF/资源消耗防线。
