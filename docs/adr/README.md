# Architecture decision records

Short records of decisions that were genuinely contested, written at the time,
including the options rejected and why.

The purpose is not documentation for its own sake. It is so that in eighteen
months, when someone asks "why on earth is it done this way", the answer exists
and is honest — including the cases where the reasoning has since stopped
applying, which is exactly when knowing the original reasoning matters most.

| ADR | Decision |
| --- | --- |
| [0001](0001-kubernetes-services-over-eureka.md) | Kubernetes Services instead of a discovery server |
| [0002](0002-build-once-deploy-many.md) | Build once, deploy the same image to every environment |
| [0003](0003-namespace-per-environment.md) | One cluster, a namespace per environment |
| [0004](0004-rabbitmq-for-domain-events.md) | RabbitMQ for domain events |
| [0005](0005-database-per-service.md) | A database per service |
| [0006](0006-maxunavailable-zero.md) | `maxUnavailable: 0`, because `helm --wait` depends on it |
