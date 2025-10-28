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
WORKDIR /usr/src/clj
COPY --from=0 /usr/src/app/ /usr/src/clj
ARG MAVEN_OPTS=${MAVEN_OPTS}
ENV MAVEN_OPTS=${MAVEN_OPTS}
ARG AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}
ENV AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}
ARG AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}
ENV AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}
RUN lein uberjar
EXPOSE 3000
CMD ["java", "-Dclojure.main.report=stderr", "-cp", "target/uberjar/tolgraven.jar", "clojure.main", "-m", "tolgraven.core"]
