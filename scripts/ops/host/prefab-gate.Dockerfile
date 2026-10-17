FROM docker:28-cli
RUN apk add --no-cache python3 git
WORKDIR /app
COPY prefab_gate.py prefab-gate.php buildkitd.toml ./
CMD ["python3", "/app/prefab_gate.py"]
