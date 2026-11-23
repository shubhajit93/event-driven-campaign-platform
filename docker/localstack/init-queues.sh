#!/bin/sh
# Creates every queue with its dead-letter queue and redrive policy, mirroring deploy/terraform/sqs.tf.
# Runs as a LocalStack ready hook in docker-compose, and from the integration tests.
set -eu

MAX_RECEIVE_COUNT="${MAX_RECEIVE_COUNT:-4}"   # must equal delivery.retry.max-attempts
VISIBILITY_TIMEOUT="${VISIBILITY_TIMEOUT:-30}"

create_queue() {
  name="$1"
  awslocal sqs create-queue --queue-name "${name}-dlq" > /dev/null
  dlq_url=$(awslocal sqs get-queue-url --queue-name "${name}-dlq" --query QueueUrl --output text)
  dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn \
    --query Attributes.QueueArn --output text)
  printf '{"RedrivePolicy":"{\\"deadLetterTargetArn\\":\\"%s\\",\\"maxReceiveCount\\":\\"%s\\"}","VisibilityTimeout":"%s"}' \
    "$dlq_arn" "$MAX_RECEIVE_COUNT" "$VISIBILITY_TIMEOUT" > "/tmp/${name}-attributes.json"
  awslocal sqs create-queue --queue-name "$name" --attributes "file:///tmp/${name}-attributes.json" > /dev/null
  echo "created $name (+ ${name}-dlq, maxReceiveCount=$MAX_RECEIVE_COUNT)"
}

create_queue delivery-email-requested
create_queue delivery-sms-requested
create_queue delivery-completed
