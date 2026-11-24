# transit-frontend image: Caddy with the built pages, also the way in to the backend.
#   (cd frontend && npm ci && npx ng build) && docker build frontend
FROM caddy:2-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY dist/frontend/browser /srv
