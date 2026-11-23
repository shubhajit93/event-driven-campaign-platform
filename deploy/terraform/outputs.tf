output "repository_urls" {
  description = "ECR repository URL per service, for CI to push to."
  value       = { for name, repo in aws_ecr_repository.service : name => repo.repository_url }
}

output "db_secret_arns" {
  description = "Secrets Manager ARN per service database credential."
  value       = { for name, secret in aws_secretsmanager_secret.db : name => secret.arn }
}
