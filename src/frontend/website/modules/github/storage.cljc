(ns website.modules.github.storage
  "Durable public commit archive and private synchronization checkpoints.")

(def columns "owner,repo,sha,message,authored_at,committed_at,author_name,author_login,avatar_url,html_url,category,subcategory,indexed_at")
(def tables
  [{:table :github_repositories
    :sql "supabase/modules/github_repositories.sql"
    :key [:owner :repo]
    :order [[:owner :asc] [:repo :asc]]
    :jsonb #{:state}
    :public-columns "owner,repo,is_public,complete,commit_count,updated_at"}
   {:table :github_commits
    :sql "supabase/modules/github_commits.sql"
    :key [:owner :repo :sha]
    :order [[:committed_at :desc] [:sha :asc]]
    :references #{:github_repositories}
    :public-columns columns}])
