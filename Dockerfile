FROM node
WORKDIR /usr/src/app
COPY ./ /usr/src/app
RUN npm run init && \
    npm run build

FROM clojure:lein
# install node + npm + npx
RUN apt-get update && \
    apt-get install -y curl && \
    curl -fsSL https://deb.nodesource.com/setup_current.x -o nodesource_setup.sh && \
    bash nodesource_setup.sh && \
    apt-get install -y nodejs && \
    npm install -g shadow-cljs && \
    apt-get clean
WORKDIR /usr/src/app
ARG MAVEN_OPTS=${MAVEN_OPTS}
ENV MAVEN_OPTS=${MAVEN_OPTS}
RUN cd checkouts/re-frame-firebase && \
    lein install; \
    cd ../..; \
    lein uberjar
EXPOSE 3000
CMD ["java", "-Dclojure.main.report=stderr", "-cp", "target/uberjar/tolgraven.jar", "clojure.main", "-m", "tolgraven.core"]
