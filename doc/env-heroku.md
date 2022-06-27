# HEROKU
## env

The site is (currently) hosted on Heroku and has some specific things depending on it.
As well as some setup stuff.  

Should probably migrate it to my Hetzner box and CapRover in future, but paying my $7 a month for now.

Not much provisioning needed outside of a few env vars though. Document those here!

Two buildpacks:
`heroku/nodejs`
`heroku/clojure`

Env:
`LEIN_INCLUDE_IN_SLUG yes` (not actually necessary right now? it's for lein / trampoline)
`NODEJS_VERSION 15.0.1`
