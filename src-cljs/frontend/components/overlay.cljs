(ns frontend.components.overlay
  (:require [cemerick.url :as url]
            [clojure.set :as set]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.components.doc-viewer :as doc-viewer]
            [frontend.components.document-access :as document-access]
            [frontend.datascript :as ds]
            [frontend.models.doc :as doc-model]
            [frontend.overlay :refer [current-overlay overlay-visible? overlay-count]]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [goog.labs.userAgent.browser :as ua]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(defn main-menu-button [data owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            main-menu-learned? (get-in data state/main-menu-learned-path)]
        (html
          [:a.main-menu-button {:on-click (if (overlay-visible? data)
                                            #(cast! :overlay-menu-closed)
                                            #(cast! :main-menu-opened))
                                :role "button"
                                :class (when (overlay-visible? data)
                                         (concat
                                           ["bkg-light"]
                                           (if (< 1 (overlay-count data))
                                             ["back"]
                                             ["close"])))
                                :data-right (when-not main-menu-learned?
                                              (if (overlay-visible? data) "Close Menu" "Open Menu"))
                                :title (when main-menu-learned?
                                         (if (overlay-visible? data) "Close Menu" "Open Menu"))}
           (common/icon :menu)])))))

(defn auth-link [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            login-button-learned? (get-in app state/login-button-learned-path)]
        (html
         (if (:cust app)
           [:form.menu-item {:method "post" :action "/logout" :ref "logout-form" :role "button"}
            [:input {:type "hidden" :name "__anti-forgery-token" :value (utils/csrf-token)}]
            [:input {:type "hidden" :name "redirect-to" :value (-> (.-location js/window)
                                                                   (.-href)
                                                                   (url/url)
                                                                   :path)}]
            [:a.menu-item {:on-click #(.submit (om/get-node owner "logout-form"))
                           :role "button"}
             (common/icon :logout)
             [:span "Log out"]]]
           [:a.menu-item  {:href (auth/auth-url)
                           :role "button"
                           :on-click #(do
                                        (.preventDefault %)
                                        (cast! :login-button-clicked)
                                        (cast! :track-external-link-clicked {:path (auth/auth-url)
                                                                             :event "Signup Clicked"}))}
            (common/icon :login)
            [:span "Log in"]]))))))

(defn start [app owner]
  (reify
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when-let [chat-datoms (seq (filter #(= :document/privacy (:a %))
                                                       (:tx-data tx-report)))]
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            doc (doc-model/find-by-id @db (:document/id app))]
        (html
         [:div.menu-view
          [:div.menu-view-frame
           [:a.menu-item {:on-click #(cast! :overlay-info-toggled)
                          :role "button"}
            (common/icon :info)
            [:span "About"]]
           [:a.menu-item {:on-click #(cast! :newdoc-button-clicked)
                          :href "/"
                          :target "_self"
                          :role "button"}
            (common/icon :newdoc)
            [:span "New Document"]]
           [:a.menu-item {:on-click #(cast! :your-docs-opened)
                          :role "button"}
            (common/icon :clock)
            [:span "Your Documents"]]
           ;; TODO: should this use the permissions model? Would have to send some
           ;;       info about the document
           (if (auth/has-document-access? app (:document/id app))
             [:a.menu-item {:on-click #(cast! :sharing-menu-opened)
                            :role "button"}
              (common/icon :users)
              [:span "Sharing"]]

             [:a.menu-item {:on-click #(cast! :document-permissions-opened)
                            :role "button"}
              (common/icon :users)
              [:span "Request Access"]])

           ;; (if (auth/has-document-access? app (:document/id app))
           ;;   [:a.menu-item {:on-click #(cast! :invite-menu-opened)
           ;;                  :role "button"}
           ;;    (common/icon :users)
           ;;    [:span "Invite Collaborators"]]

           ;;   [:a.menu-item {:on-click #(cast! :document-permissions-opened)
           ;;                  :role "button"}
           ;;    (common/icon :users)
           ;;    [:span "Request Access"]])

           ;; (if (and (contains? (get-in app [:cust :flags]) :flags/private-docs)
           ;;          (auth/admin? @db doc {:cust/uuid (get-in app [:cust :uuid])}))
           ;;   [:a.menu-item {:on-click #(cast! :manage-permissions-opened)
           ;;                  :role "button"}
           ;;    (common/icon :users)
           ;;    [:span "Manage permissions and privacy"]])

           [:a.menu-item {:on-click #(cast! :shortcuts-menu-opened)
                          :class "mobile-hidden"
                          :role "button"}
            (common/icon :command)
            [:span "Shortcuts"]]
           [:a.menu-item {:href "/blog"
                          :target "_blank"
                          :role "button"}
            (common/icon :blog)
            [:span "Blog"]]
           (om/build auth-link app)]])))))

; (defn invite [app owner]
;   (reify
;     om/IRender
;     (render [_]
;       (let [cast! (om/get-shared owner :cast!)
;             invite-email (get-in app state/invite-email-path)]
;         (html
;          [:div.menu-view
;           [:div.menu-view-frame
;            [:article
;             [:h2 "Share this with your team."]
;             [:p "Send your teammates invites to come collaborate with you in this doc."]
;             (if-not (:cust app)
;               [:a.menu-button {:href (auth/auth-url)
;                                :on-click #(do
;                                             (.preventDefault %)
;                                             (cast! :track-external-link-clicked
;                                                    {:path (auth/auth-url)
;                                                     :event "Signup Clicked"
;                                                     :properties {:source "username-overlay"}}))
;                                :role "button"}
;                "Sign Up"]

;               [:form.menu-invite-form {:on-submit #(do (cast! :invite-submitted)
;                                                        false)
;                                        :on-key-down #(when (= "Enter" (.-key %))
;                                                        (cast! :email-invite-submitted)
;                                                        false)}
;                [:input {:type "text"
;                         :required "true"
;                         :data-adaptive ""
;                         :value (or invite-email "")
;                         :on-change #(cast! :invite-email-changed {:value (.. % -target -value)})}]
;                [:label {:data-placeholder "Teammate's Email"
;                         :data-placeholder-nil "What's your teammate's email?"
;                         :data-placeholder-forgot "Don't forget to submit."}]])
;             (when-let [response (first (get-in app (state/invite-responses-path (:document/id app))))]
;               [:div response])
;             ;; TODO: keep track of invites
;             ;; [:p "You've sent 3 invitations to this doc."]
;             ;; [:div.invite-recipient
;             ;;  [:div.invite-recipient-email "fake@email.com"]
;             ;;  [:a {:role "button"} "Resend"]]
;             ;; [:div.invite-recipient
;             ;;  [:div.invite-recipient-email "fake@email.com"]
;             ;;  [:a {:role "button"} "Resend"]]
;             ;; [:div.invite-recipient
;             ;;  [:div.invite-recipient-email "fake@email.com"]
;             ;;  [:a {:role "button"} "Resend"]]
;             ]]])))))

(defn private-sharing [app owner]
  (reify
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (seq (filter #(or (= :document/privacy (:a %))
                                           (= :permission/document (:a %))
                                           (= :access-grant/document (:a %))
                                           (= :access-request/document (:a %))
                                           (= :access-request/status (:a %)))
                                      (:tx-data tx-report)))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))

    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            doc-id (:document/id app)
            doc (doc-model/find-by-id @db doc-id)
            private? (= :document.privacy/private (:document/privacy doc))
            permission-grant-email (get-in app state/permission-grant-email-path)
            permissions (ds/touch-all '[:find ?t :in $ ?doc-id :where [?t :permission/document ?doc-id]] @db doc-id)
            access-grants (ds/touch-all '[:find ?t :in $ ?doc-id :where [?t :access-grant/document ?doc-id]] @db doc-id)
            access-requests (ds/touch-all '[:find ?t :in $ ?doc-id :where [?t :access-request/document ?doc-id]] @db doc-id)
            {pending-requests false denied-requests true} (group-by #(= :access-request.status/denied (:access-request/status %))
                                                                    access-requests)]
        (html
         [:article
          (when private?
            (list
             [:h2 "Share this idea."]
             [:p "This document is only visible to those who have been granted access."
              " Add a teammate's email to grant them access."]
             [:form.menu-invite-form {:on-submit #(do (cast! :permission-grant-submitted)
                                                      false)
                                      :on-key-down #(when (= "Enter" (.-key %))
                                                      (cast! :permission-grant-submitted)
                                                      false)}
              [:input {:type "text"
                       :required "true"
                       :data-adaptive ""
                       :value (or permission-grant-email "")
                       :on-change #(cast! :permission-grant-email-changed {:value (.. % -target -value)})}]
              [:label {:data-placeholder "Email"
                       :data-placeholder-nil "Type their email"
                       :data-placeholder-forgot "Don't forget to submit"}]]
             [:div.requested-access-list
              [:h4 "Requests"]
              (when (empty? pending-requests)
                "none")
              (for [r (sort-by :db/id pending-requests)]
                [:div (:access-request/cust r)
                 " "
                 [:a {:role "button"
                      :on-click #(cast! :access-request-granted {:request-id (:db/id r)
                                                                 :doc-id doc-id})}
                  (common/icon :check)]
                 " "
                 [:a {:role "button"
                      :on-click #(cast! :access-request-denied {:request-id (:db/id r)
                                                                :doc-id doc-id})}
                  (common/icon :times)]])

              [:h4 "Approved"]
              (when (and (empty? permissions)
                         (empty? access-grants))
                "none")
              (for [p (sort-by :db/id permissions)]
                [:div (:permission/cust p)])
              (for [g (sort-by :db/id access-grants)]
                [:div (:access-grant/email g)])

              [:h4 "Denied"]
              (when (empty? denied-requests)
                "none")
              (for [r (sort-by :db/id denied-requests)]
                [:div (:access-request/cust r)
                 " "
                 [:a {:role "button"
                      :on-click #(cast! :access-request-granted {:request-id (:db/id r)
                                                                 :doc-id doc-id})}
                  "Grant access"]])]))])))))

(defn public-sharing [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            invite-email (get-in app state/invite-email-path)]
        (html
         [:article
          [:h2 "Share this idea."]
          [:p "This document is visible to everyone. Try emailing friends and asking them to collaborate on it with you."]
          (if-not (:cust app)
            [:a.menu-button {:href (auth/auth-url)
                             :on-click #(do
                                          (.preventDefault %)
                                          (cast! :track-external-link-clicked
                                                 {:path (auth/auth-url)
                                                  :event "Signup Clicked"
                                                  :properties {:source "username-overlay"}}))
                             :role "button"}
             "Sign Up"]

            [:form.menu-invite-form {:on-submit #(do (cast! :invite-submitted)
                                                     false)
                                     :on-key-down #(when (= "Enter" (.-key %))
                                                     (cast! :email-invite-submitted)
                                                     false)}
             [:input {:type "text"
                      :required "true"
                      :data-adaptive ""
                      :value (or invite-email "")
                      :on-change #(cast! :invite-email-changed {:value (.. % -target -value)})}]
             [:label {:data-placeholder "Email"
                      :data-placeholder-nil "Type their email"
                      :data-placeholder-forgot "Don't forget to submit"}]])
          (when-let [response (first (get-in app (state/invite-responses-path (:document/id app))))]
            [:div response])
          ;; TODO: keep track of invites
          ])))))

(defn sharing [app owner]
  (reify
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (seq (filter #(or (= :document/privacy (:a %)))
                                      (:tx-data tx-report)))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))

    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            invite-email (get-in app state/invite-email-path)
            doc-id (:document/id app)
            doc (doc-model/find-by-id @db doc-id)
            private? (= :document.privacy/private (:document/privacy doc))]
        (html
         [:div.menu-view
          [:div.menu-view-frame

           (if private?
             (om/build private-sharing app)
             (om/build public-sharing app))

           [:form.privacy-select
            [:input {:type "radio"
                     :id "privacy-public"
                     :name "privacy"
                     :checked (not private?)
                     :onChange #(cast! :document-privacy-changed
                                       {:doc-id doc-id
                                        :setting :document.privacy/public})}]
            [:label.menu-item {:for "privacy-public" :role "button"}
             (common/icon :globe)
             [:span "Public"]]
            [:input {:type "radio"
                     :id "privacy-private"
                     :name "privacy"
                     :checked private?
                     :onChange #(cast! :document-privacy-changed
                                       {:doc-id doc-id
                                        :setting :document.privacy/private})}]
            [:label.menu-item {:for "privacy-private" :role "button"}
             (common/icon :lock)
             [:span "Private"]]]

           ]])))))

(defn info [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.menu-view
           [:div.menu-view-frame
            [:article
             [:h2 "What is Precursor?"]
             [:p "Precursor is a no-nonsense prototyping tool.
                 Use it for wireframing, sketching, and brainstorming. "
                 ;; TODO finish wiring up invite stuff -dk (12/09/14)
                 ;; [:a {:on-click #(cast! :invite-menu-opened)
                 ;;      :role "button"}
                 ;;  "Invite"]
                 [:a {:on-click #(cast! :invite-link-clicked)
                      :role "button"
                      :title "In chat, type \"/invite their@email.com\""}
                  "Invite"]
                 " your team to collaborate instantly.
                 Have feedback or a great idea?
                 Say "
                 [:a {:href "mailto:hi@prcrsr.com?Subject=I%20have%20feedback" :title "We love feedback, good or bad."}
                  "hi@prcrsr.com"]
                 " or on "
                 [:a {:href "https://twitter.com/prcrsr_app"
                      :on-click #(analytics/track "Twitter link clicked" {:location "info overlay"})
                      :title "@prcrsr_app"
                      :target "_blank"}
                  "Twitter"]
                 "."]
             (if (:cust app)
               [:a.menu-button {:on-click #(cast! :overlay-menu-closed) :role "button"} "Okay"]
               (list
                 [:p "Sign up and we'll even keep track of all your docs.
                     Never lose a great idea again!"]
                 [:a.menu-button {:href (auth/auth-url)
                                  :on-click #(do
                                               (.preventDefault %)
                                               (cast! :track-external-link-clicked
                                                      {:path (auth/auth-url)
                                                       :event "Signup Clicked"
                                                       :properties {:source "username-overlay"}}))
                                  :role "button"}
                  "Sign Up"]))]
            [:footer {:class "mobile-hidden"}
             (common/mixpanel-badge)]]])))))

(defn shortcuts [app owner]
  (reify
    om/IInitState (init-state [_] {:copy-paste-works? (ua/isChrome)})
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.menu-view
          [:div.menu-view-frame
           [:article
            [:h2 "Move fast, make things."]
            [:div.shortcuts-item
             [:div.shortcuts-key "S"]
             [:div.shortcuts-result "Select"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "R"]
             [:div.shortcuts-result "Rectangle"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "C"]
             [:div.shortcuts-result "Circle"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "L"]
             [:div.shortcuts-result "Line"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "P"]
             [:div.shortcuts-result "Pen"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "T"]
             [:div.shortcuts-result "Text"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "1"]
             [:div.shortcuts-result "Snap to origin"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "?"]
             [:div.shortcuts-result "Reopen this menu"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "Delete"]
             [:div.shortcuts-result "Delete selected"]]
            [:div.shortcuts-item
             [:div.shortcuts-key (common/icon :command)]
             [:div.shortcuts-key "Z"]
             [:div.shortcuts-result "Undo"]]
            (when (om/get-state owner [:copy-paste-works?])
              (list
               [:div.shortcuts-item
                [:div.shortcuts-key (common/icon :command)]
                [:div.shortcuts-key "C"]
                [:div.shortcuts-result "Copy"]]
               [:div.shortcuts-item
                [:div.shortcuts-key (common/icon :command)]
                [:div.shortcuts-key "V"]
                [:div.shortcuts-result "Paste"]]))]]])))))

(defn username [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.menu-view
          [:div.menu-view-frame
           [:article
            [:h2 "Let's change that name."]
            [:p "Sign up to change how your name appears in chat.
                Let your team know who you are while you collaborate together."]
            [:a.menu-button {:href (auth/auth-url)
                             :on-click #(do
                                          (.preventDefault %)
                                          (cast! :track-external-link-clicked
                                                 {:path (auth/auth-url)
                                                  :event "Signup Clicked"
                                                  :properties {:source "username-overlay"}}))
                             :role "button"}
             "Sign Up"]]]])))))

(def overlay-components
  {:info {:component info}
   :shortcuts {:title "Shortcuts"
               :component shortcuts}
   :start {:title "Precursor"
           :component start}
   ; :invite {:title "Invite Collaborators"
   ;          :component invite}
   :sharing {:title "Sharing"
             :component sharing}
   :username {:component username}
   :doc-viewer {:title "Recent Documents"
                :component doc-viewer/doc-viewer}
   :document-permissions {:title "Request Access"
                          :component document-access/permission-denied-overlay}
   :manage-permissions {:title "Manage Access"
                        :component document-access/manage-permissions-overlay}})


(defn overlay [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            overlay-components (map #(get overlay-components %) (get-in app state/overlays-path))
            title (:title (last overlay-components))]
        (html
          [:div.app-overlay {:on-click #(cast! :overlay-closed)}
           [:div.app-overlay-background]
            [:div.app-overlay-menu {:on-click #(.stopPropagation %)}
             [:div.menu-header
              (for [component overlay-components]
               [:h4 {:title title} (:title component)])]
             [:div.menu-body
              (for [component overlay-components]
               (om/build (:component component) app))]]])))))
