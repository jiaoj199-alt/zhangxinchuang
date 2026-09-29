FROM python:3.12-slim

WORKDIR /app

# 掌心窗公开版 server 是零第三方依赖的 Python 标准库服务。
# Railway 使用本 Dockerfile 时不需要额外 Build Command / Start Command。
COPY . .

ENV LINJIAN_HOST=0.0.0.0

CMD ["python", "linjian_server.py"]
