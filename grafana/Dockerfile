# transit-grafana image: Grafana with this folder's dashboards and the server's data sources. For the server only; at
# home otel-lgtm (docker-compose.yml) shows the same dashboards.
#   docker build grafana
FROM grafana/grafana:13.3.2
COPY provisioning/datasources.yaml /etc/grafana/provisioning/datasources/transit-postgres.yaml
COPY server/tempo-datasource.yaml /etc/grafana/provisioning/datasources/tempo.yaml
COPY server/dashboards.yaml /etc/grafana/provisioning/dashboards/transit.yaml
COPY dashboards /etc/grafana/dashboards
# Behind Caddy at /grafana; nobody in without a login, and no sign-up.
ENV GF_SERVER_ROOT_URL=http://localhost:3000/grafana/ \
    GF_SERVER_SERVE_FROM_SUB_PATH=true \
    GF_AUTH_ANONYMOUS_ENABLED=false \
    GF_USERS_ALLOW_SIGN_UP=false \
    GF_USERS_ALLOW_ORG_CREATE=false \
    GF_SECURITY_COOKIE_SECURE=true \
    GF_SECURITY_DISABLE_GRAVATAR=true \
    GF_METRICS_ENABLED=false \
    GF_PUBLIC_DASHBOARDS_ENABLED=false \
    GF_SNAPSHOTS_EXTERNAL_ENABLED=false \
    GF_PLUGINS_PREINSTALL_DISABLED=true \
    GF_ANALYTICS_REPORTING_ENABLED=false \
    GF_ANALYTICS_CHECK_FOR_UPDATES=false \
    GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES=false \
    GF_NEWS_NEWS_FEED_ENABLED=false
