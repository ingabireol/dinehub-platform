# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# DineHub web — Angular built to static files, served by Nginx.
# ---------------------------------------------------------------------------

# --- Build -----------------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /build

# Manifests first, so the dependency install is a cached layer that survives
# every source change.
COPY web/package.json web/package-lock.json* ./
# `npm ci` when there is a lockfile — it is reproducible and fails on a mismatch,
# which `npm install` does not.
RUN --mount=type=cache,target=/root/.npm \
    if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; \
    else npm install --no-audit --no-fund; fi

COPY web/ ./
RUN npm run build

# --- Runtime ---------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

# The unprivileged image already runs as UID 101 and listens on 8080. Using it
# rather than patching the standard image means there is no step that can be
# forgotten.
USER root
RUN apk add --no-cache curl gettext && rm -rf /var/cache/apk/*

COPY --from=build /build/dist/dinehub-web/browser /usr/share/nginx/html
COPY web/nginx.conf /etc/nginx/templates/nginx.conf.template
COPY web/security-headers.conf /etc/nginx/conf.d/security-headers.conf

# The image contains no hostname. GATEWAY_URL is substituted at container start,
# which is what lets the same artefact run locally, in dev and in production.
ENV GATEWAY_URL=http://api-gateway:8080

USER 101
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -fsS http://localhost:8080/healthz || exit 1

# envsubst only on the variables we name, so nginx's own $variables survive.
CMD ["/bin/sh", "-c", "envsubst '${GATEWAY_URL}' < /etc/nginx/templates/nginx.conf.template > /tmp/nginx.conf && exec nginx -c /tmp/nginx.conf -g 'daemon off;'"]

LABEL org.opencontainers.image.title="dinehub-web" \
      org.opencontainers.image.description="DineHub Angular frontend" \
      org.opencontainers.image.source="https://github.com/olivier-ingabire/dinehub-platform" \
      org.opencontainers.image.licenses="MIT"
