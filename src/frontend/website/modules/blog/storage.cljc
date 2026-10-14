(ns website.modules.blog.storage
  "Seed persistence owned by the blog module; portable declaration data.")

(def tables
  [{:table :blog_posts
    :sql "supabase/modules/blog_posts.sql"
    :seed-key :blog_posts
    :collections #{"blog-posts" "blog-post-ids"}
    :queries {"blog-posts" {:document-key :doc_id
                            :fields {:id :id
                                     :user :user_id
                                     :ts :ts
                                     :permalink :permalink
                                     :tags :tags}
                            :summary "doc_id,id,permalink,user_id,title,tags,score,ts"
                            :batch-field :id
                            :cache-document-field :id}}
    :key [:id]
    :order [[:id :asc]]
    :jsonb #{:raw :image_assets}
    :public-columns "doc_id,id,permalink,user_id,title,text,tags,score,ts,image_assets"
    :references #{:site_users}}
   {:table :blog_comments
    :sql "supabase/modules/blog_comments.sql"
    :seed-key :blog_comments
    :collections #{"blog-comments"}
    :queries {"blog-comments" {:document-key :id
                               :fields {:id :id
                                        :user :user_id
                                        :ts :ts
                                        :parent-post :parent_post
                                        :parent-comment :parent_comment}
                               :batch-field :parent-comment
                               :reply-count {:select "id,parent_comment"
                                             :parent-field :parent_comment}}}
    :key [:id]
    :order [[:ts :asc] [:id :asc]]
    :jsonb #{:path :raw}
    :public-columns "id,seq_id,parent_post,parent_comment,user_id,title,text,score,path,ts"
    :references #{:site_users :blog_posts}
    :feature :comments}
   {:table :comment_votes
    :sql "supabase/modules/comment_votes.sql"
    :key [:user_id :comment_id]
    :order [[:user_id :asc] [:comment_id :asc]]
    :references #{:site_users :blog_comments}
    :feature :comments}])
