FROM node
WORKDIR /usr/src/app
COPY ./ /usr/src/app
RUN npm install; npm run build

FROM clojure:lein
WORKDIR /clj
COPY ./ /clj
COPY --from=0 /usr/src/app/resources/ /clj/resources
RUN lein uberjar
EXPOSE 3000
CMD ["java", "-Dclojure.main.report=stderr", "-cp", "target/uberjar/tolgraven.jar", "clojure.main", "-m", "tolgraven.core"]
