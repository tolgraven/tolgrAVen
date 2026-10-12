(ns tolgraven.modules.chat.storage
  "Seed persistence owned by the chat module; portable declaration data.")

(def tables
  [{:table :chat_messages
    :sql "supabase/modules/chat_messages.sql"
    :seed-key :chat_messages
    :collections #{"chat"}
    :key [:message_id]
    :order [[:message_id :asc]]
    :jsonb #{:raw}
    :public-columns "message_id,ts,user_id,text"}])
