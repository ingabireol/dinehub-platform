{{/*
  Shared template helpers.

  The merge helper is the important one: a service's own values override the
  chart defaults, so a values file only has to state what genuinely differs for
  that service — which is what keeps the environment files readable.
*/}}

{{- define "dinehub.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "dinehub.fullname" -}}
{{- printf "%s" (default .Chart.Name .Values.nameOverride) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Labels every object carries. */}}
{{- define "dinehub.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/part-of: dinehub
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
dinehub.io/environment: {{ .Values.global.environment }}
{{- end -}}

{{/* Selector labels for one service. Immutable — changing these breaks upgrades. */}}
{{- define "dinehub.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .release }}
{{- end -}}

{{/*
  The image reference for a service.

  Fails the render when the tag is empty rather than defaulting to `latest`.
  A `latest` tag makes "what is running in production?" unanswerable and a
  rollback ambiguous, so the chart refuses to produce one.
*/}}
{{- define "dinehub.image" -}}
{{- $tag := .root.Values.global.image.tag -}}
{{- if not $tag -}}
{{- fail "global.image.tag is required. The pipeline sets it to <semver>-<sha>; there is deliberately no 'latest' default." -}}
{{- end -}}
{{- printf "%s/%s/%s:%s" .root.Values.global.image.registry .root.Values.global.image.repository .name $tag -}}
{{- end -}}

{{/* Merge the chart defaults with a service's own overrides. */}}
{{- define "dinehub.serviceValues" -}}
{{- $merged := deepCopy .root.Values.defaults -}}
{{- $merged = mergeOverwrite $merged (deepCopy .svc) -}}
{{- toYaml $merged -}}
{{- end -}}

{{/*
  Environment every Java service receives.

  Database and broker credentials come from Secrets by reference, never from a
  value — the chart has no way to see them, which is the point.
*/}}
{{- define "dinehub.commonEnv" -}}
- name: SPRING_PROFILES_ACTIVE
  value: {{ .root.Values.global.environment | quote }}
- name: SERVER_PORT
  value: {{ .svc.port | quote }}
- name: LOG_LEVEL
  value: {{ .root.Values.global.logLevel | quote }}
- name: SWAGGER_ENABLED
  value: {{ .root.Values.global.swaggerEnabled | quote }}
- name: JWT_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ .root.Values.global.secrets.jwt }}
      key: secret
{{- if ne (.svc.needsDatabase | default true) false }}
- name: DB_HOST
  value: {{ printf "%s-postgresql" (include "dinehub.fullname" .root) }}
- name: DB_PORT
  value: "5432"
- name: DB_NAME
  value: {{ .svc.database | quote }}
- name: DB_USER
  valueFrom:
    secretKeyRef:
      name: {{ .root.Values.global.secrets.database }}
      key: username
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .root.Values.global.secrets.database }}
      key: password
{{- end }}
{{- if ne (.svc.needsRabbit | default true) false }}
- name: RABBITMQ_HOST
  value: {{ printf "%s-rabbitmq" (include "dinehub.fullname" .root) }}
- name: RABBITMQ_PORT
  value: "5672"
- name: RABBITMQ_USER
  valueFrom:
    secretKeyRef:
      name: {{ .root.Values.global.secrets.rabbitmq }}
      key: username
- name: RABBITMQ_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .root.Values.global.secrets.rabbitmq }}
      key: password
{{- end }}
{{- if .svc.needsRedis }}
- name: REDIS_HOST
  value: {{ printf "%s-redis" (include "dinehub.fullname" .root) }}
- name: REDIS_PORT
  value: "6379"
{{- end }}
{{- range .svc.extraEnv }}
- name: {{ .name }}
  value: {{ .value | quote }}
{{- end }}
{{- end -}}

{{/* Service URLs the gateway routes to. */}}
{{- define "dinehub.gatewayRouteEnv" -}}
{{- $full := include "dinehub.fullname" .root -}}
{{- range $name, $svc := .root.Values.services }}
{{- if and $svc.enabled (ne $name "api-gateway") }}
- name: {{ $name | upper | replace "-" "_" }}_URL
  value: {{ printf "http://%s-%s:%v" $full $name $svc.port | quote }}
{{- end }}
{{- end }}
- name: CORS_ALLOWED_ORIGINS
  value: {{ printf "https://%s,http://%s" .root.Values.ingress.host .root.Values.ingress.host | quote }}
{{- end -}}
