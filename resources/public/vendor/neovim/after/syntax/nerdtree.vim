" NERDTree's syntax reload clears the devicons FileType conceal rules.
" Apply them after the tree syntax, keeping brackets in actual filenames visible.
syntax match hideBracketsInNerdTree "\[" contained conceal containedin=NERDTreeFlags,NERDTreeLinkFile,NERDTreeLinkDir
syntax match hideBracketsInNerdTree "\]" contained conceal containedin=NERDTreeFlags,NERDTreeLinkFile,NERDTreeLinkDir
setlocal conceallevel=3 concealcursor=nvic
