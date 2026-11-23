output "repository_urls" {
  description = "ECR repository URL per service, for CI to push to."
  value       = { for name, repo in aws_ecr_repository.service : name => repo.repository_url }
}

output "db_secret_arns" {
  description = "Secrets Manager ARN per service database credential."
  value       = { for name, secret in aws_secretsmanager_secret.db : name => secret.arn }
}

output "queue_urls" {
  description = "SQS queue URL per queue."
  value       = { for name, q in aws_sqs_queue.main : name => q.url }
}

output "dlq_urls" {
  description = "Dead-letter queue URL per queue, for inspection and redrive."
  value       = { for name, q in aws_sqs_queue.dlq : name => q.url }
}

output "sqs_policy_arns" {
  description = "IAM policies to attach to each service's IRSA role."
  value = {
    "campaign-service" = aws_iam_policy.campaign_service_sqs.arn
    "delivery-worker"  = aws_iam_policy.delivery_worker_sqs.arn
  }
}
