(ns pc.auth.google
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-time.coerce]
            [clj-time.format]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [pc.profile :as profile]
            [pc.util.base64 :as base64]
            [pc.util.jwt :as jwt]
            [slingshot.slingshot :refer (try+ throw+)]))

(defn redirect-uri []
  (str (url/map->URL
        {:protocol (if (profile/force-ssl?) "https" "http")
         :host (profile/hostname)
         :port (if (profile/force-ssl?)
                 (profile/https-port)
                 (profile/http-port))
         :path "/auth/google"})))

(defn fetch-code-info [code]
  (-> (http/post "https://accounts.google.com/o/oauth2/token"
                 {:form-params {:code code
                                :client_id (profile/google-client-id)
                                :client_secret (profile/google-client-secret)
                                :redirect_uri (redirect-uri)
                                :grant_type "authorization_code"}})
      :body
      (json/decode true)))

(defn parse-id-token [id-token]
  (:payload (jwt/decode id-token)))

;; https://developers.google.com/accounts/docs/OAuth2Login
(defn user-info-from-code [code]
  (-> code
      fetch-code-info
      :id_token
      parse-id-token
      ;; :sub is google's unique identifier for the user
      (select-keys [:email :email_verified :sub])))

(defn user-info-from-sub [sub]
  (try+
    (let [resp (-> (http/get (format "https://www.googleapis.com/plus/v1/people/%s" sub)
                             {:query-params {:key (profile/google-api-key)}})
                 :body
                 json/decode)]
      {:first-name (get-in resp ["name" "givenName"])
       :last-name (get-in resp ["name" "familyName"])
       :birthday (some-> resp (get "birthday") clj-time.format/parse clj-time.coerce/to-date)
       :gender (get resp "gender")
       :occupation (get resp "occupation")
       :avatar-url (some-> (get-in resp ["image" "url"])
                     url/url
                     (assoc :query {})
                     str
                     (java.net.URI.))})
    ;; some users don't have a google plus account
    (catch [:status 404] t
      (log/infof "No google plus info for %s" sub)
      {})))

(defn oauth-uri [csrf-token & {:keys [scopes redirect-path redirect-query redirect-subdomain redirect-csrf-token login-hint]
                               :or {scopes ["openid" "email"]
                                    redirect-path "/"}}]
  (let [state (url/url-encode (json/encode (merge {:csrf-token csrf-token
                                                   :redirect-path redirect-path}
                                                  (when redirect-query
                                                    {:redirect-query redirect-query})
                                                  (when redirect-subdomain
                                                    {:redirect-subdomain redirect-subdomain})
                                                  (when redirect-subdomain
                                                    {:redirect-csrf-token redirect-csrf-token}))))]
    (str (url/map->URL {:protocol "https"
                        :host "accounts.google.com"
                        :path "/o/oauth2/auth"
                        :query (merge {:client_id (profile/google-client-id)
                                       :response_type "code"
                                       :access_type "online"
                                       :scope (str/join " " scopes)
                                       :redirect_uri (redirect-uri)
                                       :state state}
                                      (when login-hint
                                        {:login_hint login-hint}))}))))
