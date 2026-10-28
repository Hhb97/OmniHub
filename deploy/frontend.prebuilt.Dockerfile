# Build context: repository root. Uses the dist folder built on the workstation (deploy-vm.ps1 -Prebuilt).
FROM nginx:1.27-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/prebuilt/web /usr/share/nginx/html
EXPOSE 80
