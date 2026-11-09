# 프론트엔드 저장소의 delivery/에 복사하고, 빌드한 APK만 이 폴더로 옮긴다.
ARG NGINX_IMAGE=nginx:stable-alpine
FROM ${NGINX_IMAGE}
COPY default.conf /etc/nginx/conf.d/default.conf
COPY index.html itip-demo.apk /usr/share/nginx/html/
EXPOSE 8080
