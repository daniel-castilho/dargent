#!/usr/bin/env bash
# Idempotent LocalStack initialization for E6 outbox messaging + E7 ledger consumer.
# Creates SNS FIFO topic, SQS FIFO notify queue + DLQ (payments), ledger queue + DLQ (E7),
# subscriptions, and redrive policies.
# Re-run safe: creates resources only if missing.

set -euo pipefail

AWS_ENDPOINT=${AWS_ENDPOINT_URL:-http://localhost:4566}
AWS_REGION=${AWS_REGION:-us-east-1}

echo "Waiting for LocalStack to be ready..."
until curl -sf "${AWS_ENDPOINT}/_localstack/health" | grep -Eq '"sns".*"(available|running)"'; do
    sleep 2
done
until curl -sf "${AWS_ENDPOINT}/_localstack/health" | grep -Eq '"sqs".*"(available|running)"'; do
    sleep 2
done
echo "LocalStack is ready."

# --- Payments topic + queue + DLQ (E6) ---

TOPIC_NAME="dargent-payments-events.fifo"
QUEUE_NAME="dargent-payments-notify.fifo"
DLQ_NAME="dargent-payments-notify-dlq.fifo"

echo "Creating SNS FIFO topic: ${TOPIC_NAME}"
awslocal sns create-topic \
    --name "${TOPIC_NAME}" \
    --attributes FifoTopic=true,ContentBasedDeduplication=false \
    --region "${AWS_REGION}" \
    --endpoint-url "${AWS_ENDPOINT}" >/dev/null || true

TOPIC_ARN=$(awslocal sns list-topics --region "${AWS_REGION}" --endpoint-url "${AWS_ENDPOINT}" \
    --query "Topics[?contains(TopicArn, '${TOPIC_NAME}')].TopicArn" --output text)
echo "Topic ARN: ${TOPIC_ARN}"

echo "Creating SQS FIFO queue: ${QUEUE_NAME}"
awslocal sqs create-queue \
    --queue-name "${QUEUE_NAME}" \
    --attributes FifoQueue=true \
    --region "${AWS_REGION}" \
    --endpoint-url "${AWS_ENDPOINT}" >/dev/null || true

QUEUE_URL=$(awslocal sqs get-queue-url --queue-name "${QUEUE_NAME}" \
    --region "${AWS_REGION}" --endpoint-url "${AWS_ENDPOINT}" \
    --query "QueueUrl" --output text)
QUEUE_ARN=$(awslocal sqs get-queue-attributes --queue-url "${QUEUE_URL}" \
    --attribute-names QueueArn --region "${AWS_REGION}" --endpoint-url "${AWS_ENDPOINT}" \
    --query "Attributes.QueueArn" --output text)
echo "Queue URL: ${QUEUE_URL}"
echo "Queue ARN: ${QUEUE_ARN}"

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

echo "Configuring redrive policy on ${QUEUE_NAME} -> ${DLQ_NAME}"
awslocal sqs set-queue-attributes \
    --queue-url "${QUEUE_URL}" \
    --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}" \
    --region "${AWS_REGION}" \
    --endpoint-url "${AWS_ENDPOINT}"

echo "Subscribing ${QUEUE_NAME} to ${TOPIC_NAME}"
EXISTING_SUB=$(awslocal sns list-subscriptions-by-topic --topic-arn "${TOPIC_ARN}" \
    --region "${AWS_REGION}" --endpoint-url "${AWS_ENDPOINT}" \
    --query "Subscriptions[?Endpoint=='${QUEUE_ARN}'].SubscriptionArn" --output text)
if [ -z "${EXISTING_SUB}" ]; then
    awslocal sns subscribe \
        --topic-arn "${TOPIC_ARN}" \
        --protocol sqs \
        --notification-endpoint "${QUEUE_ARN}" \
        --region "${AWS_REGION}" \
        --endpoint-url "${AWS_ENDPOINT}" >/dev/null
    echo "Subscription created."
else
    echo "Subscription already exists (${EXISTING_SUB})."
fi

# --- Ledger queue + DLQ (E7) ---

LEDGER_QUEUE_NAME="dargent-payments-ledger.fifo"
LEDGER_DLQ_NAME="dargent-payments-ledger-dlq.fifo"

echo "Creating SQS FIFO queue: ${LEDGER_QUEUE_NAME}"
awslocal sqs create-queue \
    --queue-name "${LEDGER_QUEUE_NAME}" \
    --attributes FifoQueue=true \
    --region "${AWS_REGION}" \
    --endpoint-url "${AWS_ENDPOINT}" >/dev/null || true

LEDGER_QUEUE_URL=$(awslocal sqs get-queue-url --queue-name "${LEDGER_QUEUE_NAME}" \
    --region "${AWS_REGION}" --endpoint-url "${AWS_ENDPOINT}" \
    --query "QueueUrl" --output text)
LEDGER_QUEUE_ARN=$(awslocal sqs get-queue-attributes --queue-url "${LEDGER_QUEUE_URL}" \
    --attribute-names QueueArn --region "${AWS_REGION}" --endpoint-url "${AWS_ENDPOINT}" \
    --query "Attributes.QueueArn" --output text)
echo "Ledger Queue URL: ${LEDGER_QUEUE_URL}"
echo "Ledger Queue ARN: ${LEDGER_QUEUE_ARN}"

echo "Creating DLQ: ${LEDGER_DLQ_NAME}"
awslocal sqs create-queue \
    --queue-name "${LEDGER_DLQ_NAME}" \
    --attributes FifoQueue=true \
    --region "${AWS_REGION}" \
    --endpoint-url "${AWS_ENDPOINT}" >/dev/null || true

LEDGER_DLQ_URL=$(awslocal sqs get-queue-url --queue-name "${LEDGER_DLQ_NAME}" \
    --region "${AWS_REGION}" --endpoint-url "${AWS_ENDPOINT}" \
    --query "QueueUrl" --output text)
LEDGER_DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url "${LEDGER_DLQ_URL}" \
    --attribute-names QueueArn --region "${AWS_REGION}" --endpoint-url "${AWS_ENDPOINT}" \
    --query "Attributes.QueueArn" --output text)
echo "Ledger DLQ URL: ${LEDGER_DLQ_URL}"
echo "Ledger DLQ ARN: ${LEDGER_DLQ_ARN}"

echo "Configuring redrive policy on ${LEDGER_QUEUE_NAME} -> ${LEDGER_DLQ_NAME}"
awslocal sqs set-queue-attributes \
    --queue-url "${LEDGER_QUEUE_URL}" \
    --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${LEDGER_DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}" \
    --region "${AWS_REGION}" \
    --endpoint-url "${AWS_ENDPOINT}"

echo "Subscribing ${LEDGER_QUEUE_NAME} to ${TOPIC_NAME}"
EXISTING_LEDGER_SUB=$(awslocal sns list-subscriptions-by-topic --topic-arn "${TOPIC_ARN}" \
    --region "${AWS_REGION}" --endpoint-url "${AWS_ENDPOINT}" \
    --query "Subscriptions[?Endpoint=='${LEDGER_QUEUE_ARN}'].SubscriptionArn" --output text)
if [ -z "${EXISTING_LEDGER_SUB}" ]; then
    awslocal sns subscribe \
        --topic-arn "${TOPIC_ARN}" \
        --protocol sqs \
        --notification-endpoint "${LEDGER_QUEUE_ARN}" \
        --region "${AWS_REGION}" \
        --endpoint-url "${AWS_ENDPOINT}" >/dev/null
    echo "Ledger subscription created."
else
    echo "Ledger subscription already exists (${EXISTING_LEDGER_SUB})."
fi

echo "LocalStack initialization complete."