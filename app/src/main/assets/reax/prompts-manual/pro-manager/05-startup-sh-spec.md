## 五、startup.sh 规范（重要）

创建项目/模块时，AIPM 会自动检测语言并生成 startup.sh，AI 手动编写时需遵循：

1. **必须包含安装依赖命令**（首次启动必需）：
    - Node：`npm install --force && npm run dev`
    - Python：`pip install -r requirements.txt && python app.py`
    - Java：`mvn spring-boot:run` 等
2. **脚本需可重复执行**（安装命令应幂等）

---
