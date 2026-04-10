#!/usr/bin/env node

const fs = require("fs");
const path = require("path");
const admin = require("../functions/node_modules/firebase-admin");
const serviceAccount = require("../resources/firebase/tolgraven-8fd35-firebase-adminsdk-x98e7-51cbc4d188.json");

const collections = [
  "auth",
  "blog-comments",
  "blog-post-ids",
  "blog-posts",
  "chat",
  "imagor",
  "instagram",
  "secrets",
  "strapi",
  "strava",
  "typesense",
  "users",
];

const normalizeValue = (value) => {
  if (value && typeof value.toMillis === "function") {
    return { __type: "timestamp", millis: value.toMillis() };
  }

  if (Array.isArray(value)) {
    return value.map(normalizeValue);
  }

  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([k, v]) => [k, normalizeValue(v)]),
    );
  }

  return value;
};

async function main() {
  const target = process.argv[2] || path.join(process.cwd(), "tmp", "firebase-live-export.json");

  admin.initializeApp({
    credential: admin.credential.cert(serviceAccount),
  });

  const db = admin.firestore();
  db.settings({ preferRest: true });

  const output = {};

  for (const collection of collections) {
    const snapshot = await db.collection(collection).get();
    output[collection] = snapshot.docs.map((doc) => ({
      id: doc.id,
      data: normalizeValue(doc.data()),
    }));
  }

  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, JSON.stringify(output, null, 2));
  process.stdout.write(`${target}\n`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
