# Messaging: one queue per channel plus the results queue. Each has a DLQ attached through a redrive
# policy, so a message that keeps failing is parked for inspection and replay instead of being lost.
locals {
  queues = toset(["delivery-email-requested", "delivery-sms-requested", "delivery-completed"])
}

resource "aws_sqs_queue" "dlq" {
  for_each = local.queues

  name                      = "${each.key}-dlq"
  message_retention_seconds = 1209600 # 14 days, the SQS maximum
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "main" {
  for_each = local.queues

  name                       = each.key
  visibility_timeout_seconds = var.visibility_timeout_seconds
  receive_wait_time_seconds  = 20 # long polling: fewer empty receives, lower cost
  message_retention_seconds  = 345600
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = var.max_receive_count
  })
}

# Only the matching source queue may dead-letter into each DLQ.
resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  for_each  = local.queues
  queue_url = aws_sqs_queue.dlq[each.key].id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.main[each.key].arn]
  })
}

# Least-privilege access, attached to each service's IRSA role.
locals {
  consume = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:GetQueueUrl"]
  produce = ["sqs:SendMessage", "sqs:GetQueueAttributes", "sqs:GetQueueUrl"]
}

data "aws_iam_policy_document" "campaign_service" {
  statement {
    sid       = "PublishDeliveryRequests"
    actions   = local.produce
    resources = [aws_sqs_queue.main["delivery-email-requested"].arn, aws_sqs_queue.main["delivery-sms-requested"].arn]
  }
  statement {
    sid       = "ConsumeDeliveryResults"
    actions   = local.consume
    resources = [aws_sqs_queue.main["delivery-completed"].arn]
  }
}

data "aws_iam_policy_document" "delivery_worker" {
  statement {
    sid       = "ConsumeDeliveryRequests"
    actions   = local.consume
    resources = [aws_sqs_queue.main["delivery-email-requested"].arn, aws_sqs_queue.main["delivery-sms-requested"].arn]
  }
  statement {
    sid       = "PublishDeliveryResults"
    actions   = local.produce
    resources = [aws_sqs_queue.main["delivery-completed"].arn]
  }
}

resource "aws_iam_policy" "campaign_service_sqs" {
  name   = "${var.environment}-campaign-service-sqs"
  policy = data.aws_iam_policy_document.campaign_service.json
}

resource "aws_iam_policy" "delivery_worker_sqs" {
  name   = "${var.environment}-delivery-worker-sqs"
  policy = data.aws_iam_policy_document.delivery_worker.json
}
