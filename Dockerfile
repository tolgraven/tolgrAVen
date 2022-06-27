FROM node:16
WORKDIR /app
COPY ./ /app
RUN npm install; npm run build

FROM clojure:openjdk-8-lein-2.9.8
WORKDIR /clj
COPY ./ /clj
COPY --from=0 /app/resources/ /clj/resources
RUN lein uberjar
EXPOSE 3000
CMD ["java", "-Dclojure.main.report=stderr", "-cp", "target/uberjar/tolgraven.jar", "clojure.main", "-m", "tolgraven.core"]
