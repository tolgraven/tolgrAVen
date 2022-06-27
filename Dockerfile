FROM node:16
WORKDIR /usr/src/app
COPY package*.json ./
RUN npm install
COPY . .
CMD {"npm", "run", "build"}

FROM clojure:openjdk-8-lein-2.9.8
WORKDIR /usr/src/app
# COPY --from=0 /usr/src/app/resources/ /usr/src/app/resources
COPY --from=0 . .
COPY . .
RUN lein uberjar
EXPOSE 3000
CMD ["java", "-Dclojure.main.report=stderr", "-cp", "target/uberjar/tolgraven.jar", "clojure.main", "-m", "tolgraven.core"]
