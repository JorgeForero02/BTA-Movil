# Environment for the full verification (scripts/verify.sh --full) on Windows or on any
# machine without Linux: JDK 21 for the patchers (ASM9), JDK 17 for the tests, and the tools
# used by the scripts under source/.
FROM eclipse-temurin:17-jdk AS jdk17
FROM eclipse-temurin:21-jdk
COPY --from=jdk17 /opt/java/openjdk /opt/jdk17
RUN apt-get update -qq \
 && apt-get install -y -qq --no-install-recommends zip unzip python3 nodejs git \
 && rm -rf /var/lib/apt/lists/*
