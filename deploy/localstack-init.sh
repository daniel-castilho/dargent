#!/usr/bin/env bash
# Idempotent LocalStack initialization for E6 outbox messaging.
# Creates SNS FIFO topic, SQS FIFO queue + DLQ, subscription, and redrive policy.
# Re-run safe: creates resources only if missing.

set -euo pipefail

AWS_ENDPOINT=${AWS_ENDPOINT_URL:-http://localhost:4566}
AWS_REGION=${AWS_REGION:-us-east-1}

echo "Waiting for LocalStack to be ready..."
until curl -sf "${AWS_ENDPOINT}/_localstack/health" | grep -q '"sns".*"running"'; do
    sleep 2
done
until curl -sf "${AWS_ENDPOINT}/_localstack/health" | grep -q '"sqs".*"running"'; do
    sleep 2
done
echo "LocalStack is ready."

TOPIC_NAME="dargent-payments-events.fifo"
QUEUE_NAME="dargent-payments-notify.fifo"
DLQ_NAME="dargent-payments-notify-dlq.fifo"

# Create FIFO topic
echo "Creating SNS FIFO topic: ${TOPIC_NAME}"
awslocal sns create-topic \
    --name "${TOPIC_NAME}" \
    --attributes FifoTopic=true,ContentBasedDeduplication=false \
    --region "${AWS_REGION}" \
    --endpoint-url "${AWS_ENDPOINT}" >/dev/null || true

TOPIC_ARN=$(awslocal sns list-topics --region "${AWS_REGION}" --endpoint-url "${AWS_ENDPOINT}" \
    --query "Topics[?contains(TopicArn, '${TOPIC_NAME}')].TopicArn" --output text)
echo "Topic ARN: ${TOPIC_ARN}"

# Create FIFO queue
echo "Creating SQS FIFO queue: ${QUEUE_NAME}"
awslocal sqs create-queue \
    --queue-name "${QUEUE_NAME}" \
    --attributes FifoQueue=true \
    --region "${AWS_REGION}" \
    --endpoint-url "${AWS_ENDPOINT}" >/dev/null || true

QUEUE_URL=$(awslocal sqs get-queue-url --queue-name "${QUEUE_NAME}" \
    --region "${AWS_REGION}" --endpoint-url "${AWS_ENDPOINT}" \
    --query "QueueUrl" --output text)
echo "Queue URL: ${QUEUE_URL}"

# Create DLQ
echo "Creating DLQ: ${DLQ_NAME}"
awslocal sqs create-queue \
    --queue-name "${DLQ_NAME}" \
    --attributes FifoQueue=true \
    --region "${AWS_REGION}" \
    --endpoint-url "${AWS_ENDPOINT}" >/dev/null || true

DLQ_URL=$(awslocal sqs get-queue-url --queue-name "${DLQ_NAME}" \
    --region "${AWS_REGION}" --endpoint-url "${AWS_ENDPOINT}" \
    --query "QueueUrl" --output text)
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url "${DLQ_URL}" \
    --attribute-names QueueArn --region "${AWS_REGION}" --endpoint-url "${AWS_ENDPOINT}" \
    --query "Attributes.QueueArn" --output text)
echo "DLQ URL: ${DLQ_URL}"
echo "DLQ ARN: ${DLQ_ARN}"

# Configure redrive policy (maxReceiveCount=5)
echo "Configuring redrive policy on ${QUEUE_NAME} -> ${DLQ_NAME}"
awslocal sqs set-queue-attributes \
    --queue-url "${QUEUE_URL}" \
    --attributes "RedrivePolicy={\"deadLetterTargetArn\":\"${DLQ_ARN}\",\"maxReceiveCount\":\"5\"}" \
    --region "${AWS_REGION}" \
    --endpoint-url "${AWS_ENDPOINT}"

# Subscribe queue to topic
echo "Subscribing ${QUEUE_NAME} to ${TOPIC_NAME}"
awslocal sns subscribe \
    --topic-arn "${TOPIC_ARN}" \
    --protocol sqs \
    --notification-endpoint "${QUEUE_URL}" \
    --region "${AWS_REGION}" \
    --endpoint-url "${AWS_ENDPOINT}" >/dev/null

echo "LocalStack initialization complete."