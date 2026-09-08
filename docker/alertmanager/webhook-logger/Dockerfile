FROM python:3.12-alpine
# E16 S1: one-file stdlib stub — no dependencies, non-root.
WORKDIR /app
COPY webhook_logger.py .
USER nobody
EXPOSE 9095
CMD ["python3", "webhook_logger.py", "9095"]