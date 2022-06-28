# DOCS

Using Codox for doc generation. It runs through lein with the build task and puts html files in
`resources/docs/codox` out of which `<body>` is extracted and served from backend and put in page.
The links are then transformed with js to correspond to our relative path docs/codox/:doc
Css for component taken from theirs but rebuilt to use flexbox and whatnot.    
    
But also want it indexed in Typesense so will have to do something on back-end boot for that.
Either parsing page or using a custom writer to output edn then transform that to json and push
to typesense?
