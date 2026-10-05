(ns tolgraven.schema.components
  "Input contracts shared by reusable UI primitives and their callers/tests.")

(def timeout-args [:tuple fn? [:and number? [:>= 0]]])
