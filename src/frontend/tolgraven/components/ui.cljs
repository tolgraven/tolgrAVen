(ns tolgraven.components.ui
  "Compatibility exports; import the focused ui.* namespace for new callers."
  (:require
    [tolgraven.components.ui.boundary :as boundary]
    [tolgraven.components.ui.markdown :as markdown]
    [tolgraven.components.ui.layout :as layout]
    [tolgraven.components.ui.controls :as controls]
    [tolgraven.components.ui.inputs :as inputs]
    [tolgraven.components.ui.media :as media]
    [tolgraven.components.ui.overlays :as overlays]
    [tolgraven.components.ui.diagnostics :as diagnostics]
    [tolgraven.components.ui.notifications :as notifications]
    [tolgraven.components.ui.loading :as loading]))

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
