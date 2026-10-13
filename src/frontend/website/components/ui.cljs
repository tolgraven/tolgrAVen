(ns website.components.ui
  "Compatibility exports; import the focused ui.* namespace for new callers."
  (:require
    [website.components.ui.boundary :as boundary]
    [website.components.ui.markdown :as markdown]
    [website.components.ui.layout :as layout]
    [website.components.ui.controls :as controls]
    [website.components.ui.inputs :as inputs]
    [website.components.ui.media :as media]
    [website.components.ui.overlays :as overlays]
    [website.components.ui.diagnostics :as diagnostics]
    [website.components.ui.notifications :as notifications]
    [website.components.ui.loading :as loading]))

(def <safe> boundary/<safe>)
(def <md->div> markdown/<md->div>)
(def <observe-sticky> layout/<observe-sticky>)
(def <zoom-to-modal> overlays/<zoom-to-modal>)
(def <inset> layout/<inset>)
(def <fading-bg-heading> layout/<fading-bg-heading>)
(def <button> controls/<button>)
(def <toggle-for-path> controls/<toggle-for-path>)
(def <toggle> controls/<toggle>)
(def <burger> controls/<burger>)
(def <float-img> media/<float-img>)
(def <auto-layout-text-imgs> media/<auto-layout-text-imgs>)
(def <material-toggle> controls/<material-toggle>)
(def <minimize> controls/<minimize>)
(def <close> controls/<close>)
(def <formatted-data> diagnostics/<formatted-data>)
(def <input-text-2> inputs/<input-text-2>)
(def <input-text> inputs/<input-text>)
(def <input-text-styled> inputs/<input-text-styled>)
(def format-log-message diagnostics/format-log-message)
(def <log> diagnostics/<log>)
(def <modal> overlays/<modal>)
(def <hud-modal> notifications/<hud-modal>)
(def <hud-message> notifications/<hud-message>)
(def <hud> notifications/<hud>)
(def <input-toggle> controls/<input-toggle>)
(def <loading-spinner> loading/<loading-spinner>)
(def <link-img-title> media/<link-img-title>)
(def <fading> layout/<fading>)
(def <with-heading> layout/<with-heading>)
(def <carousel-idx-btns> media/<carousel-idx-btns>)
(def <carousel> media/<carousel>)
(def <carousel-normal> media/<carousel-normal>)
