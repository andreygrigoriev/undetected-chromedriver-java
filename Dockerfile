FROM --platform=linux/amd64 eclipse-temurin:25-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg xvfb xauth fonts-liberation fonts-noto-color-emoji \
    && curl -fsSL https://dl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main" > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends google-chrome-stable \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --shell /bin/bash uc
WORKDIR /app
RUN chown uc:uc /app
USER uc

COPY --chown=uc:uc mvnw pom.xml ./
COPY --chown=uc:uc .mvn .mvn
RUN ./mvnw -B dependency:go-offline

COPY --chown=uc:uc src src
COPY --chown=uc:uc --chmod=755 docker/entrypoint.sh /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["./mvnw", "-B", "verify"]
