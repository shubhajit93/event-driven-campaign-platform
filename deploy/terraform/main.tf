# Container registry: one repository per service, immutable tags (a tag always means the same image),
# scan on push, and lifecycle rules so old images do not pile up storage cost.
resource "aws_ecr_repository" "service" {
  for_each = var.services

  name                 = "${var.environment}/${each.key}"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
  }
}

resource "aws_ecr_lifecycle_policy" "service" {
  for_each   = aws_ecr_repository.service
  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after 7 days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 7
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep only the most recent tagged images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.images_to_keep
        }
        action = { type = "expire" }
      }
    ]
  })
}

# Database credentials: generated once, stored in Secrets Manager, and synced into the cluster
# (for example by External Secrets Operator). They never appear in manifests or CI logs.
resource "random_password" "db" {
  for_each = var.services

  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "db" {
  for_each = var.services

  name                    = "${var.environment}/${each.key}/db"
  recovery_window_in_days = var.environment == "prod" ? 30 : 0
}

resource "aws_secretsmanager_secret_version" "db" {
  for_each = var.services

  secret_id = aws_secretsmanager_secret.db[each.key].id
  secret_string = jsonencode({
    username = replace(each.key, "-", "_")
    password = random_password.db[each.key].result
  })
}
