# FIREBASE
## env
    
In lieu of a fancier backend with streaming GraphQL queries (really need to get on that,
can use cue-db GQL stuff as base...) I'm using Firebase for a lot of stuff, anything dynamic really.      
    
Blog posts, comments, user accounts (really wouldn't want to roll my own OAuth anyways so...) etc.
Once main content is moved from app-db to something external FB will probably host that as well.    
    
It's working well after some minor refactoring to improperly-for-re-frame but properly-for-fb
using straight subs for most everything (improper because kicking off sub has side effects...)      
    
Should spec up needed setup here.    
    
Also want the config to be pushed from local files, however it obviously contains secrets...
so read their docs and find out how to set that up hah.    
    
Ideally all that should remain is setting some var to specify admin account, then let that account
set others as admins or bloggers etc from an in-page UI.
