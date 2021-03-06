(ns frontend.components.plan
  (:require [cljs-time.format :as time-format]
            [clojure.string :as str]
            [datascript.core :as d]
            [frontend.components.common :as common]
            [frontend.components.inspector :as inspector]
            [frontend.datascript :as ds]
            [frontend.datetime :as datetime]
            [frontend.db :as fdb]
            [frontend.models.doc :as doc-model]
            [frontend.models.plan :as plan-model]
            [frontend.models.team :as team-model]
            [frontend.sente :as sente]
            [frontend.stripe :as stripe]
            [frontend.urls :as urls]
            [frontend.utils :as utils]
            [frontend.utils.date :refer (date->bucket)]
            [goog.dom]
            [goog.dom.Range]
            [goog.string :as gstring]
            [goog.string.format]
            [om.core :as om]
            [om.dom :as dom]
            [taoensso.sente])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn time-left [plan]
  (datetime/time-ago (- (:plan/trial-end plan)
                        (.getTime (js/Date.)))))

(defn format-access-date [date]
  (date->bucket date :sentence? true))

(defn active-history [{:keys [plan team-uuid doc-id] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "Plan Active History")
    om/IInitState (init-state [_] {:history nil})
    om/IDidMount
    (did-mount [_]
      (fdb/watch-doc-name-changes owner)
      ;; We're not using the controls here because the history state gets stale fast
      ;; May turn out to be a bad idea
      ;; Ideally, we'd have the history in our frontend db
      (sente/send-msg (om/get-shared owner :sente) [:team/plan-active-history {:team/uuid team-uuid}]
                      20000
                      (fn [res]
                        (if (sente/cb-success? res)
                          (om/set-state! owner :history (:history res))
                          (comment "do something about errors")))))
    om/IRenderState
    (render-state [_ {:keys [history]}]
      (let [{:keys [cast! db team-db]} (om/get-shared owner)
            doc (doc-model/find-by-id @db doc-id)]
        (html
         [:section.menu-view
          (if (nil? history)
            [:div.content.make.loading {:key "loading"} "Loading..."]
            [:div.content {:key "history"}
             (if (seq history)
               (for [{:keys [cust instant added?]} (reverse history)]
                 [:div.access-card.make {:key (str instant cust)}
                  [:div.access-avatar
                   [:img.access-avatar-img
                    {:src (utils/gravatar-url cust)}]]
                  [:div.access-details
                   [:span {:title cust}
                    cust]
                   [:span.access-status
                    (str "Was marked as" (if added? " active " " inactive ") (format-access-date instant) ".")]]])

               [:div.menu-empty.content {:key "empty"}
                [:p.make (common/icon :activity)]
                [:p.make "We haven't seen any activity on your team yet. It's refreshed every 8 hours."]
                [:a.make.feature-link {:href (urls/overlay-path doc "team-settings")
                                       :role "button"}
                 "Add a teammate."]])])])))))

(defn active-custs [{:keys [plan team-uuid doc-id] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "Plan Active Custs")
    om/IDidMount (did-mount [_] (fdb/watch-doc-name-changes owner))
    om/IRender
    (render [_]
      (let [{:keys [cast! db team-db]} (om/get-shared owner)
            active (:plan/active-custs plan)
            doc (doc-model/find-by-id @db doc-id)]
        (html
         [:section.menu-view
          (if (seq active)
            [:div.content {:key "active"}
             [:div.content.make
              (str (count active) " active user" (when (< 1 (count active)) "s") " on your plan")]
             ;; TODO: should make active a ref
             (for [email (sort active)]
               [:div.access-card.make {:key email}
                [:div.access-avatar
                 [:img.access-avatar-img
                  {:src (utils/gravatar-url email)}]]
                [:div.access-details
                 [:span {:title email}
                  email]
                 [:span.access-status
                  ""]]])]
            [:div.content {:key "empty"}
             [:div.menu-empty.content
              [:p.make (common/icon :activity)]
              [:p.make "We haven't seen any activity on your team yet. It's refreshed every 8 hours."]
              [:a.make.feature-link {:href (urls/overlay-path doc "team-settings")
                                     :role "button"}
               "Add a teammate."]]])])))))

(defn format-stripe-cents
  "Formats Stripe's currency values into ordinary dollar format
   500 -> $5
   489 -> $4.89"
  [cents]
  (let [abs-cents (Math/abs cents)
        pennies (mod abs-cents 100)
        dollars (/ (- abs-cents pennies) 100)]
    (if (pos? pennies)
      (gstring/format "$%s%d.%02d" (if (neg? cents) "-" "") dollars pennies)
      (gstring/format "$%s%d" (if (neg? cents) "-" "") dollars))))

(defn invoice-component [{:keys [invoice-id team-uuid]} owner]
  (reify
    om/IDisplayName (display-name [_] "Invoice")
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/watch-doc-name-changes owner)
      (fdb/add-entity-listener (om/get-shared owner :team-db)
                               invoice-id
                               (om/get-state owner :listener-key)
                               (fn [tx-report]
                                 (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener (om/get-shared owner :team-db)
                                  invoice-id
                                  (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [invoice (d/touch (d/entity @(om/get-shared owner :team-db) invoice-id))]
        (html
          [:tr.invoice.make
           [:td.invoice-date {:title (datetime/medium-date (:invoice/date invoice))}
            (datetime/month-day (:invoice/date invoice))]
           [:td.invoice-id
            [:a {:href (urls/invoice-url team-uuid invoice-id)
                 :target "_blank"}
             (str "#" (:db/id invoice))]]
           [:td.invoice-total
            (format-stripe-cents (:invoice/total invoice))]
           [:td.invoice-status
            (cond (:invoice/paid? invoice)
                  "Paid"
                  (:invoice/next-payment-attempt invoice)
                  (str "Charging " (datetime/month-day (:invoice/next-payment-attempt invoice)))
                  :else "Unpaid")]])))))

(defn invoices [{:keys [plan team-uuid doc-id] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "Invoices")
    om/IDidMount (did-mount [_] (fdb/watch-doc-name-changes owner))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            doc (doc-model/find-by-id @db doc-id)
            sorted-invoices (->> plan
                              :plan/invoices
                              ;; don't show $0 invoices
                              (filter #(not (zero? (:invoice/total %))))
                              (sort-by :invoice/date)
                              reverse)]
        (html
          [:section.menu-view
           (if (< 0 (count sorted-invoices))

             [:table.invoices-table
              [:thead.invoices-head
               [:tr.make
                [:th.invoice-date "date"]
                [:th.invoice-id "invoice"]
                [:th.invoice-total "total"]
                [:th.invoice-status "status"]]]
              [:tbody.invoices-body
               (for [invoice sorted-invoices]
                 (om/build invoice-component {:invoice-id (:db/id invoice)
                                              :team-uuid team-uuid}
                           {:key :invoice-id}))]]

             [:div.menu-empty.content
              [:p.make (common/icon :docs)]
              [:p.make "We'll list your first invoice here when it's ready."]
              [:a.make.feature-link {:role "button"
                                     :href (urls/plan-submenu-path doc "activity")}
               "View team activity."]])])))))

(defn payment [{:keys [plan team-uuid doc-id] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "Plan payment")
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)]
        (html
         [:section.menu-view.credit-card
          [:div.content.make
           [:div.disabled-input {:data-after "Credit Card"}
            [:span.secret-card-number "•••• •••• •••• "]
            (str  (:credit-card/last4 plan))]]
          [:div.content.make
           [:div.disabled-input {:data-after "Expires"}
            (str (:credit-card/exp-month plan) "/" (:credit-card/exp-year plan))]]
          [:div.content.make
           [:div.menu-buttons
            [:a.menu-button {:role "button"
                             :on-click #(cast! :change-card-clicked)}
             "Change card."]]]])))))

(defn info [{:keys [plan team-uuid doc-id] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "Plan Info")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            submit-fn #(do (when-not (str/blank? (om/get-state owner :new-email))
                             (cast! :billing-email-changed {:email (om/get-state owner :new-email)
                                                            :plan-id (:db/id plan)}))
                           (om/set-state! owner :editing-email? false)
                           (om/set-state! owner :new-email ""))]
        (html
         [:section.menu-view
          [:div.content.make
           [:form.menu-invite-form
            [:input {:type "text"
                     :required "true"
                     :data-adaptive ""
                     :value (or (when (seq (om/get-state owner :new-email))
                                  (om/get-state owner :new-email))
                                (:plan/billing-email plan)
                                "")
                     :on-change #(om/set-state! owner :new-email (.. % -target -value))}]
            [:label {:data-placeholder "We'll send your invoices here"
                     :data-placeholder-nil "We need an email to send invoices"}]]]
          [:div.content.make
           [:div.menu-buttons
            [:a.menu-button {:role "button"
                             :on-click #(do (submit-fn)
                                          (utils/stop-event %))}
             "Save information."]]]])))))

(defn discount [{:keys [plan team-uuid doc-id] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "Plan Discount")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            {:keys [coupon/duration-in-months
                    coupon/stripe-id coupon/percent-off]} (:discount/coupon plan)]
        (html
         [:section.menu-view
          [:div.content.make
           "You have the " stripe-id " coupon, which gives you " percent-off "% off for the first "
           duration-in-months " months."]
          (when (:discount/end plan)
            [:div.content.make
             "Your discount expires on " (datetime/month-day (:discount/end plan)) "."])])))))

(defn activity-summary [{:keys [plan team-uuid doc-id] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "Plan Activity Summary")
    om/IDidMount (did-mount [_] (fdb/watch-doc-name-changes owner))
    om/IRender
    (render [_]
      (let [{:keys [db cast!]} (om/get-shared owner)
            doc (doc-model/find-by-id @db doc-id)]
        (html
         (case (count (:plan/active-custs plan))
           0 [:span
              [:a {:role "button"
                   :href (urls/plan-submenu-path doc "active")}
               "No users"]
              " are active on your team, yet."]
           1 [:span
              [:a {:role "button"
                   :href (urls/plan-submenu-path doc "active")}
               "One user"]
              " is active on your team."]
           [:span
            [:a {:role "button"
                 :href (urls/plan-submenu-path doc "active")}
             (str (count (:plan/active-custs plan)) " users")]

            " are active on your team."]))))))

(defn paid-summary [{:keys [plan team-uuid doc-id] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "Plan Paid Summary")
    om/IRender
    (render [_]
      (html
       [:div
        (if (plan-model/in-trial? plan)
          [:div.content.make
           [:h4
            "Your team plan starts " (datetime/month-day (:plan/next-period-start plan))
            " for " (format-stripe-cents (plan-model/cost plan)) "."]]
          [:div.content.make
           [:h4
            "Your team plan renews " (datetime/month-day (:plan/next-period-start plan))
            " for " (format-stripe-cents (plan-model/cost plan)) "."]])
        [:div.content.make
         [:p
          (om/build activity-summary data)
          " Your plan renews monthly. "
          ;; discount
          (when-let [coupon (:discount/coupon plan)]
            (str "The " (:coupon/percent-off coupon) "% " (:coupon/stripe-id coupon)
                 " discount is included in the renewal price. "))
          ;; trial days
          (when (plan-model/in-trial? plan)
            (str "The " (time-left plan) " left in your trial are included in the start date."))]]]))))

(defn trial-summary [{:keys [plan team-uuid doc-id] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "Plan Trial Summary")
    om/IRender
    (render [_]
      (html
       [:div
        (if (plan-model/in-trial? plan)
          [:div.content.make
           [:h4
            "Your have " (time-left plan) " left in your trial."]]
          [:div.content.make
           [:h4 "Your free trial has expired."]])
        [:div.content.make
         [:p
          (om/build activity-summary data)
          " Your plan will cost " (format-stripe-cents (plan-model/cost plan)) "/mo. "
          ;; discount
          (when-let [coupon (:discount/coupon plan)]
            (str "The " (:coupon/percent-off coupon) "% " (:coupon/stripe-id coupon)
                 " discount is included in the cost. "))
          ;; trial days
          (if (plan-model/in-trial? plan)
            "We won't start charging until your trial ends."
            "Add payment below to keep using Precursor with your team.")]]]))))

(defn start [{:keys [plan team-uuid doc-id] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "Plan Start")
    om/IDidMount
    (did-mount [_]
      (fdb/watch-doc-name-changes owner)
      (when-not (:plan/paid? plan)
        (stripe/load-checkout)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            doc (doc-model/find-by-id @db doc-id)]
        (html
         [:section.menu-view
          [:div.divider.make]
          (if (:plan/paid? plan)
            (om/build paid-summary data {:react-key "paid-summary"})
            (om/build trial-summary data {:react-key "trial-summary"}))
          [:div.divider.make]
          (if-not (:plan/paid? plan)
            (list
             [:a.vein.make {:on-click #(cast! :start-plan-clicked)}
              (common/icon :credit)
              [:span "Add payment"]]
             (when (plan-model/trial-over? plan)
               [:a.vein.make {:on-click #(cast! :extend-trial-clicked)}
                (common/icon :plus)
                [:span "Extend trial"]]))
            (list
             [:a.vein.make {:href (urls/plan-submenu-path doc "info")}
              (common/icon :info)
              [:span "Information"]]
             [:a.vein.make {:href (urls/plan-submenu-path doc "payment")}
              (common/icon :credit)
              [:span "Payment"]]
             [:a.vein.make {:href (urls/plan-submenu-path doc "invoices")}
              (common/icon :docs)
              [:span "Invoices"]]
             [:a.vein.make {:href (urls/plan-submenu-path doc "activity")}
              (common/icon :activity)
              [:span "Activity"]]
             (when (plan-model/active-discount? plan)
               [:a.vein.make {:href (urls/plan-submenu-path doc "discount")}
                (common/icon :heart)
                [:span "Discount"]])
             (when (neg? (:plan/account-balance plan))
               [:div.content.make.store-credit
                [:span (str "You have " (format-stripe-cents (Math/abs (:plan/account-balance plan))) " of credit for future payments.")]])))])))))

(def plan-components
  {:start start
   :info info
   :payment payment
   :invoices invoices
   :activity active-history
   :active active-custs
   :discount discount})

(defn plan-menu* [{:keys [plan-id team-uuid doc-id submenu] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "Plan Menu*")
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-entity-listener (om/get-shared owner :team-db)
                               plan-id
                               (om/get-state owner :listener-key)
                               (fn [tx-report]
                                 (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener (om/get-shared owner :team-db)
                                  plan-id
                                  (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)
            plan (ds/touch+ (d/entity @team-db plan-id))
            component-key (or submenu :start)
            component (get plan-components component-key)]
        (if (:plan/trial-end plan) ;; wait for plan to load
          (om/build component (assoc data :plan plan) {:react-key component-key})
          (dom/div {:className "loading"}))))))

(defn plan-menu [app owner {:keys [submenu]}]
  (reify
    om/IDisplayName (display-name [_] "Plan Menu")
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-attribute-listener (om/get-shared owner :team-db)
                                  :team/plan
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-attribute-listener (om/get-shared owner :team-db)
                                     :team/plan
                                     (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)
            team (team-model/find-by-uuid @team-db (get-in app [:team :team/uuid]))]
        (if (:team/plan team)
          (om/build plan-menu* {:plan-id (:db/id (:team/plan team))
                                :team-uuid (:team/uuid team)
                                :doc-id (:document/id app)
                                :submenu submenu}
                    {:react-key "plan-menu*"})
          (dom/div {:className "loading"}))))))
