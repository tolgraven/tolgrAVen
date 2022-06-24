### TYPESENSE
# tolgrAVen website
## And something of a template/framework for spawning other projects

Typesense specific stuff, the five vars etc

Based on CapRover being up and running...

Install typesense-cli,
`git clone https://github.com/AlexBV117/typesense-cli.git`
`npm run typesense-cli`
`npm link` for global access
make an api key limited to search:

```
typesense --key --new \
          '{
            "description": "Search-only blog key.",
            "actions": ["documents:search"],
            "collections": ["blog-posts", "blog-comments"]
          }'
```          
add the collections like:


```          
curl "https://typesense.box.tolgraven.se:443/collections" \
           -X POST \
           -H "Content-Type: application/json" \
           -H "X-TYPESENSE-API-KEY: $DA_KEY" \
           -d '{
         "name": "blog-comments",
         "fields": [
           {"name": "text", "type": "string" },
           {"name": "title", "type": "string", "optional": true },
           {"name": "user", "type": "string", "optional": true },
           {"name": "username", "type": "string", "optional": true },
           {"name": "date", "type": "string", "optional": true },
           {"name": "parent-post", "type": "int32", "optional": true },
           {"name": "parent-comment", "type": "string", "optional": true },
           {"name": "ts", "type": "int64" }
         ],
         "default_sorting_field": "ts"
       }'
```          

Ideally this would set up on backend when run with init params and auto pushed to
firestore...
