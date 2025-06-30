FROM eclipse-temurin:24-jdk AS build

WORKDIR /build/app
COPY src /build/app/src
COPY pom.xml assembly.xml /build/app/
# Files from assembly
COPY cantaloupe.properties.sample delegates.rb.sample CHANGES.md LICENSE.txt LICENSE-3RD-PARTY.txt README.md UPGRADING.md /build/app/
RUN buildDeps='maven ca-certificates ffmpeg libopenjp2-tools liblcms2-dev libpng-dev libzstd-dev libtiff-dev libjpeg-dev zlib1g-dev libwebp-dev libimage-exiftool-perl libgrokj2k1 grokj2k-tools' \
  && apt-get update -qq \
  && apt-get upgrade -y \
  && apt-get install -y --no-install-recommends $buildDeps 
RUN mvn -ntp -DskipTests=true -Djar.phase=package clean package 

FROM eclipse-temurin:24-jre

WORKDIR /app

#ffmpeg  liblcms2-2 libpng16-16 libzstd1 libtiff-tools libjpeg8 zlib1g libwebp7' \
RUN runtimeDeps='ca-certificates unzip curl xz-utils' \
  && apt-get update -qq \
  && apt-get upgrade -y \
  && apt-get install -y --no-install-recommends $runtimeDeps \
  && apt-get clean -y \
  && adduser --uid 1001 --home /app --no-create-home cantaloupe 
RUN wget -O openslide.xz https://github.com/openslide/openslide-bin/releases/download/v4.0.0.8/openslide-bin-4.0.0.8-linux-x86_64.tar.xz \
  && tar -xf openslide.xz \
  && cp openslide-bin-4.0.0.8-linux-x86_64/lib/libopenslide.so.1.0.0 /usr/local/lib \
  && ln -s /usr/local/lib/libopenslide.so.1.0.0 /usr/local/lib/libopenslide.so \
  && rm openslide.xz \
  && rm -rf openslide-bin-4.0.0.8-linux-x86_64 \
  && ldconfig

COPY --from=build /build/app/* /app

HEALTHCHECK CMD curl -s http://localhost:8182/health | jq .color | grep -q GREEN

USER cantaloupe

ENTRYPOINT [ "java", "-Dcantaloupe.config=/etc/cantaloupe/cantaloupe.properties", "-Djava.library.path=/usr/local/lib", "-cp", "/app/Cantaloupe-5.0.7.jar", "edu.illinois.library.cantaloupe.StandaloneEntry" ]
