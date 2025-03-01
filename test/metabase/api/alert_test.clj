(ns metabase.api.alert-test
  "Tests for `/api/alert` endpoints."
  (:require
   [clojure.test :refer :all]
   [medley.core :as m]
   [metabase.channel.email-test :as et]
   [metabase.channel.impl.http-test :as channel.http-test]
   [metabase.http-client :as client]
   [metabase.models.permissions :as perms]
   [metabase.models.permissions-group :as perms-group]
   [metabase.models.pulse :as models.pulse]
   [metabase.models.pulse-test :as pulse-test]
   [metabase.request.core :as request]
   [metabase.test :as mt]
   [metabase.test.fixtures :as fixtures]
   [metabase.test.mock.util :refer [pulse-channel-defaults]]
   [metabase.util :as u]
   [toucan2.core :as t2]))

(use-fixtures :once (fixtures/initialize :notifications))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Helper Fns & Macros                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- user-details [user-kwd]
  (-> user-kwd
      mt/fetch-user
      (select-keys [:email :first_name :is_qbnewb :is_superuser :last_name :common_name :locale])
      (assoc :id true, :date_joined true)))

(defn- pulse-card-details [card]
  (-> card
      (select-keys [:name :description :display :pivot_results])
      (update :display name)
      (update :collection_id boolean)
      (assoc :id true :include_csv false :include_xls false
             :format_rows true :pivot_results false
             :dashboard_card_id false
             :dashboard_id false :parameter_mappings nil)))

(defn- recipient-details [user-kwd]
  (-> user-kwd
      user-details
      (dissoc :is_qbnewb :is_superuser :date_joined :locale)))

(defn- alert-client
  [username]
  (comp mt/boolean-ids-and-timestamps (partial mt/user-http-request username)))

(defn- default-email-channel
  ([pulse-card]
   (default-email-channel pulse-card [(mt/fetch-user :rasta)]))

  ([pulse-card recipients]
   {:id            pulse-card
    :enabled       true
    :channel_type  "email"
    :schedule_type "hourly"
    :schedule_hour 12
    :schedule_day  "mon"
    :recipients    recipients
    :details       {}}))

(defn- alert-response [response]
  (-> response
      (m/dissoc-in [:creator :last_login])
      (m/dissoc-in [:card :collection])))

(defmacro ^:private with-test-email! [& body]
  `(mt/with-temporary-setting-values [~'site-url "https://metabase.com/testmb"]
     (mt/with-fake-inbox
       ~@body)))

(defmacro ^:private with-alert-setup!
  "Macro that will cleanup any created pulses and setups a fake-inbox to validate emails are sent for new alerts"
  [& body]
  `(mt/with-model-cleanup [:model/Pulse]
     (with-test-email!
       ~@body)))

(defn- do-with-alert-in-collection! [f]
  (pulse-test/with-pulse-in-collection! [db collection alert card]
    (assert (t2/exists? :model/PulseCard :card_id (u/the-id card), :pulse_id (u/the-id alert)))
    ;; Make this Alert actually be an alert
    (t2/update! :model/Pulse (u/the-id alert) {:alert_condition "rows"})
    (let [alert (t2/select-one :model/Pulse :id (u/the-id alert))]
      (assert (models.pulse/is-alert? alert))
      ;; Since Alerts do not actually go in Collections, but rather their Cards do, put the Card in the Collection
      (t2/update! :model/Card (u/the-id card) {:collection_id (u/the-id collection)})
      (let [card (t2/select-one :model/Card :id (u/the-id card))]
        (f db collection alert card)))))

(defmacro ^:private with-alert-in-collection!
  "Do `body` with a temporary Alert whose Card is in a Collection, setting the stage to write various tests below. (Make
  sure to grant All Users permissions to the Collection if needed.)"
  {:style/indent 1}
  [[db-binding collection-binding alert-binding card-binding] & body]
  ;; I'm always getting the order of these two mixed up, so let's try to check that here
  (when alert-binding
    (assert (not= alert-binding 'card)))
  (when card-binding
    (assert (not= card-binding 'alert)))
  `(do-with-alert-in-collection!
    (fn [~(or db-binding '_) ~(or collection-binding '_) ~(or alert-binding '_) ~(or card-binding '_)]
      ~@body)))

;; This stuff below is separate from `with-alert-in-collection` above!
(defn- do-with-alerts-in-a-collection!
  "Do `f` with the Cards associated with `alerts-or-ids` in a new temporary Collection. Grant perms to All Users to that
  Collection using `f`.

  (The name of this function is somewhat of a misnomer since the Alerts themselves aren't in Collections; it is their
  Cards that are. Alerts do not go in Collections; their perms are derived from their Cards.)"
  [grant-collection-perms-fn! alerts-or-ids f]
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp [:model/Collection collection]
      (grant-collection-perms-fn! (perms-group/all-users) collection)
      ;; Go ahead and put all the Cards for all of the Alerts in the temp Collection
      (when (seq alerts-or-ids)
        (doseq [alert (t2/select :model/Pulse :id [:in (set (map u/the-id alerts-or-ids))])
                :let  [card (#'models.pulse/alert->card alert)]]
          (t2/update! :model/Card (u/the-id card) {:collection_id (u/the-id collection)})))
      (f))))

(defmacro ^:private with-alerts-in-readable-collection! [alerts-or-ids & body]
  `(do-with-alerts-in-a-collection! perms/grant-collection-read-permissions! ~alerts-or-ids (fn [] ~@body)))

(defmacro ^:private with-alerts-in-writeable-collection! [alerts-or-ids & body]
  `(do-with-alerts-in-a-collection! perms/grant-collection-readwrite-permissions! ~alerts-or-ids (fn [] ~@body)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                       /api/alert/* AUTHENTICATION Tests                                        |
;;; +----------------------------------------------------------------------------------------------------------------+

;; We assume that all endpoints for a given context are enforced by the same middleware, so we don't run the same
;; authentication test on every single individual endpoint

(deftest auth-tests
  (is (= (get request/response-unauthentic :body)
         (client/client :get 401 "alert")))

  (is (= (get request/response-unauthentic :body)
         (client/client :put 401 "alert/13"))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                 GET /api/alert                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

;; by default, archived Alerts should be excluded

(deftest get-alerts-test
  (testing "archived alerts should be excluded"
    (is (= #{"Not Archived"}
           (with-alert-in-collection! [_ _ not-archived-alert]
             (with-alert-in-collection! [_ _ archived-alert]
               (t2/update! :model/Pulse (u/the-id not-archived-alert) {:name "Not Archived"})
               (t2/update! :model/Pulse (u/the-id archived-alert)     {:name "Archived", :archived true})
               (with-alerts-in-readable-collection! [not-archived-alert archived-alert]
                 (set (map :name (mt/user-http-request :rasta :get 200 "alert"))))))))))

(deftest get-alerts-test-2
  (testing "fetch archived alerts"
    (is (= #{"Archived"}
           (with-alert-in-collection! [_ _ not-archived-alert]
             (with-alert-in-collection! [_ _ archived-alert]
               (t2/update! :model/Pulse (u/the-id not-archived-alert) {:name "Not Archived"})
               (t2/update! :model/Pulse (u/the-id archived-alert)     {:name "Archived", :archived true})
               (with-alerts-in-readable-collection! [not-archived-alert archived-alert]
                 (set (map :name (mt/user-http-request :rasta :get 200 "alert" :archived true))))))))))

(deftest get-alerts-test-3
  (testing "fetch alerts by user ID -- should return alerts created by the user,
           or alerts for which the user is a known recipient"
    (with-alert-in-collection! [_ _ creator-alert]
      (with-alert-in-collection! [_ _ recipient-alert]
        (with-alert-in-collection! [_ _ other-alert]
          (with-alerts-in-readable-collection! [creator-alert recipient-alert other-alert]
            (t2/update! :model/Pulse (u/the-id creator-alert) {:name "LuckyCreator" :creator_id (mt/user->id :lucky)})
            (t2/update! :model/Pulse (u/the-id recipient-alert) {:name "LuckyRecipient"})
            (t2/update! :model/Pulse (u/the-id other-alert) {:name "Other"})
            (mt/with-temp [:model/User uninvolved-user {}
                           :model/PulseChannel pulse-channel {:pulse_id (u/the-id recipient-alert)}
                           :model/PulseChannelRecipient _ {:pulse_channel_id (u/the-id pulse-channel), :user_id (mt/user->id :lucky)}]
              (testing "Admin can see any alerts"
                (is (= #{"LuckyCreator" "LuckyRecipient" "Other"}
                       (set (map :name (mt/user-http-request :crowberto :get 200 "alert")))))
                (is (= #{"LuckyCreator" "LuckyRecipient"}
                       (set (map :name (mt/user-http-request :crowberto :get 200 "alert" :user_id (mt/user->id :lucky)))))))
              (testing "Regular Users will only see alerts they have created or recieve"
                (is (= #{"LuckyCreator" "LuckyRecipient"}
                       (set (map :name (mt/user-http-request :lucky :get 200 "alert")))))
                (is (= #{"LuckyRecipient" "Other"}
                       (set (map :name (mt/user-http-request :rasta :get 200 "alert" :user_id (mt/user->id :rasta))))))
                (is (= #{}
                       (set (map :name (mt/user-http-request (u/the-id uninvolved-user) :get 200 "alert")))))))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                               GET /api/alert/:id                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(deftest get-alert-test
  (testing "an alert can be fetched by ID"
    (with-alert-in-collection! [_ _ alert]
      (with-alerts-in-readable-collection! [alert]
        (is (= (u/the-id alert)
               (:id (mt/user-http-request :rasta :get 200 (str "alert/" (u/the-id alert)))))))))

  (testing "fetching a non-existing alert returns an error"
    (mt/user-http-request :rasta :get 404 (str "alert/" 123))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                POST /api/alert                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(deftest post-alert-test
  (is (=? {:errors          {:alert_condition "enum of rows, goal"}
           :specific-errors {:alert_condition ["should be either \"rows\" or \"goal\", received: \"not rows\""]}}
          (mt/user-http-request
           :rasta :post 400 "alert" {:alert_condition "not rows"
                                     :card            "foobar"}))))

(deftest post-alert-test-2
  (is (=? {:errors {:alert_first_only "boolean"}
           :specific-errors {:alert_first_only ["missing required key, received: nil"]}}
          (mt/user-http-request
           :rasta :post 400 "alert" {:alert_condition "rows"}))))

(deftest post-alert-test-3
  (is (=? {:errors
           {:card "value must be a map with the keys `id`, `include_csv`, `include_xls`, and `dashboard_card_id`."}
           :specific-errors
           {:card ["missing required key, received: nil"]}}
          (mt/user-http-request
           :rasta :post 400 "alert" {:alert_condition  "rows"
                                     :alert_first_only false}))))

(deftest post-alert-test-4
  (is (= {:errors {:channels "one or more map"} :specific-errors {:channels ["missing required key, received: nil"]}}
         (mt/user-http-request
          :rasta :post 400 "alert" {:alert_condition  "rows"
                                    :alert_first_only false
                                    :card             {:id 100, :include_csv false, :include_xls false, :dashboard_card_id nil}}))))

(deftest post-alert-test-5
  (is (= {:errors {:channels "one or more map"} :specific-errors {:channels ["invalid type, received: \"foobar\""]}}
         (mt/user-http-request
          :rasta :post 400 "alert" {:alert_condition  "rows"
                                    :alert_first_only false
                                    :card             {:id 100, :include_csv false, :include_xls false, :dashboard_card_id nil}
                                    :channels         "foobar"}))))

(deftest post-alert-test-6
  (is (= {:errors {:channels "one or more map"} :specific-errors {:channels [["invalid type, received: \"abc\""]]}}
         (mt/user-http-request
          :rasta :post 400 "alert" {:alert_condition  "rows"
                                    :alert_first_only false
                                    :card             {:id 100, :include_csv false, :include_xls false, :dashboard_card_id nil}
                                    :channels         ["abc"]}))))

(defn- new-alert-email [user body-map]
  (mt/email-to user {:subject "You set up an alert",
                     :body (merge {"https://metabase.com/testmb" true,
                                   "My question"                 true}
                                  body-map)}))

(defn- added-to-alert-email [user body-map]
  (mt/email-to user {:subject "Crowberto Corv added you to an alert",
                     :body (merge {"https://metabase.com/testmb" true,
                                   "now getting alerts" true}
                                  body-map)}))

(defn- unsubscribe-email [user body-map]
  (mt/email-to user {:subject "You unsubscribed from an alert",
                     :body (merge {"https://metabase.com/testmb" true}
                                  body-map)}))

(defn- default-alert [card]
  {:id                  true
   :name                nil
   :entity_id           true
   :creator_id          true
   :creator             (user-details :rasta)
   :created_at          true
   :updated_at          true
   :card                (pulse-card-details card)
   :alert_condition     "rows"
   :alert_first_only    false
   :alert_above_goal    nil
   :archived            false
   :channels            [(merge pulse-channel-defaults
                                {:channel_type  "email"
                                 :schedule_type "hourly"
                                 :schedule_hour nil
                                 :recipients    [(recipient-details :rasta)]
                                 :updated_at    true
                                 :pulse_id      true
                                 :id            true
                                 :created_at    true
                                 :channel_id    false})]
   :skip_if_empty       true
   :collection_id       false
   :collection_position nil
   :dashboard_id        false
   :parameters          []})

(def ^:private daily-email-channel
  {:enabled       true
   :channel_type  "email"
   :entity_id     true
   :schedule_type "daily"
   :schedule_hour 12
   :schedule_day  nil
   :recipients    []})

;; Check creation of a new rows alert with email notification
(deftest new-rows-with-email-test
  (mt/with-temp [:model/Card card {:name "My question"}]
    (is (= [(-> (default-alert card)
                (assoc-in [:card :include_csv] true)
                (assoc-in [:card :collection_id] true)
                (update-in [:channels 0] merge {:schedule_hour 12, :schedule_type "daily", :recipients []}))
            (new-alert-email :rasta {"has any results" true})]
           (mt/with-non-admin-groups-no-root-collection-perms
             (mt/with-temp [:model/Collection collection]
               (t2/update! :model/Card (u/the-id card) {:collection_id (u/the-id collection)})
               (with-alert-setup!
                 (perms/grant-collection-read-permissions! (perms-group/all-users) collection)
                 [(et/with-expected-messages 1
                    (alert-response
                     ((alert-client :rasta) :post 200 "alert"
                                            {:card             {:id (u/the-id card), :include_csv false, :include_xls false, :dashboard_card_id nil}
                                             :collection_id    (u/the-id collection)
                                             :alert_condition  "rows"
                                             :alert_first_only false
                                             :channels         [daily-email-channel]})))
                  (et/regex-email-bodies #"https://metabase.com/testmb"
                                         #"has any results"
                                         #"My question")])))))))

(defn- setify-recipient-emails [results]
  (update results :channels (fn [channels]
                              (map #(update % :recipients set) channels))))

;; An admin created alert should notify others they've been subscribed
(deftest notify-subscribed-test
  (mt/with-temp [:model/Card card {:name "My question"}]
    (is (= {:response (-> (default-alert card)
                          (assoc :creator (user-details :crowberto))
                          (assoc-in [:card :include_csv] true)
                          (update-in [:channels 0] merge {:schedule_hour 12
                                                          :schedule_type "daily"
                                                          :recipients    (set (map recipient-details [:rasta :crowberto]))}))
            :emails (merge (et/email-to :crowberto {:subject "You set up an alert"
                                                    :body    {"https://metabase.com/testmb"  true
                                                              "My question"                  true
                                                              "now getting alerts"           false
                                                              "confirmation that your alert" true}})
                           (added-to-alert-email :rasta
                                                 {"My question"                  true
                                                  "now getting alerts"           true
                                                  "confirmation that your alert" false}))}
           (with-alert-setup!
             (array-map
              :response (et/with-expected-messages 2
                          (-> ((alert-client :crowberto) :post 200 "alert"
                                                         {:card             {:id (u/the-id card), :include_csv false, :include_xls false, :dashboard_card_id nil}
                                                          :alert_condition  "rows"
                                                          :alert_first_only false
                                                          :channels         [(assoc daily-email-channel
                                                                                    :details       {:emails nil}
                                                                                    :recipients    (mapv mt/fetch-user [:crowberto :rasta]))]})
                              setify-recipient-emails
                              alert-response))
              :emails (et/regex-email-bodies #"https://metabase.com/testmb"
                                             #"now getting alerts"
                                             #"confirmation that your alert"
                                             #"My question")))))))

;; Check creation of a below goal alert
(deftest below-goal-alert-test
  (is (= (new-alert-email :rasta {"goes below its goal" true})
         (mt/with-non-admin-groups-no-root-collection-perms
           (mt/with-temp [:model/Collection collection {}
                          :model/Card       card {:name          "My question"
                                                  :display       "line"
                                                  :collection_id (u/the-id collection)}]
             (perms/grant-collection-read-permissions! (perms-group/all-users) collection)
             (with-alert-setup!
               (et/with-expected-messages 1
                 (mt/user-http-request
                  :rasta :post 200 "alert"
                  {:card             {:id (u/the-id card), :include_csv false, :include_xls false, :dashboard_card_id nil}
                   :alert_condition  "goal"
                   :alert_above_goal false
                   :alert_first_only false
                   :channels         [daily-email-channel]}))
               (et/regex-email-bodies #"https://metabase.com/testmb"
                                      #"goes below its goal"
                                      #"My question")))))))

;; Check creation of a above goal alert
(deftest above-goal-alert-test
  (is (= (new-alert-email :rasta {"meets its goal" true})
         (mt/with-non-admin-groups-no-root-collection-perms
           (mt/with-temp [:model/Collection collection {}
                          :model/Card       card {:name          "My question"
                                                  :display       "bar"
                                                  :collection_id (u/the-id collection)}]
             (perms/grant-collection-read-permissions! (perms-group/all-users) collection)
             (with-alert-setup!
               (et/with-expected-messages 1
                 (mt/user-http-request
                  :rasta :post 200 "alert"
                  {:card             {:id (u/the-id card), :include_csv false, :include_xls false, :dashboard_card_id nil}
                   :collection_id    (u/the-id collection)
                   :alert_condition  "goal"
                   :alert_above_goal true
                   :alert_first_only false
                   :channels         [daily-email-channel]}))
               (et/regex-email-bodies #"https://metabase.com/testmb"
                                      #"meets its goal"
                                      #"My question")))))))

(defn- default-http-channel
  [id]
  {:enabled       true
   :channel_type  "http"
   :channel_id    id
   :details       {}
   :schedule_type "daily"
   :schedule_hour 12
   :schedule_day  nil})

(deftest create-alert-with-http-channel-test
  (testing "Creating an alert with a HTTP channel"
    (mt/with-model-cleanup [:model/Pulse]
      (channel.http-test/with-server [url [channel.http-test/get-200]]
        (mt/with-temp [:model/Channel channel {:type    :channel/http
                                               :details {:auth-method "none"
                                                         :url         (str url (:path channel.http-test/get-200))}}
                       :model/Card    {card-id :id} {}]
          (let [pulse (mt/user-http-request :crowberto :post 200 "alert"
                                            {:alert_condition  "rows"
                                             :alert_first_only false
                                             :card             {:id card-id, :include_csv false, :include_xls false, :dashboard_card_id nil}
                                             :channels         [(default-http-channel (:id channel))]})]
            (is (=? {:pulse_id     (:id pulse)
                     :channel_type :http
                     :channel_id   (:id channel)}
                    (t2/select-one :model/PulseChannel :pulse_id (:id pulse))))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                               PUT /api/alert/:id                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(deftest put-alert-test-2
  (is (= {:errors {:alert_condition "nullable enum of rows, goal"},
          :specific-errors {:alert_condition ["should be either \"rows\" or \"goal\", received: \"not rows\""]}}
         (mt/user-http-request
          :rasta :put 400 "alert/1" {:alert_condition "not rows"})))

  (is (= {:errors {:alert_first_only "nullable boolean"}
          :specific-errors {:alert_first_only ["should be a boolean, received: 1000"]}}
         (mt/user-http-request
          :rasta :put 400 "alert/1" {:alert_first_only 1000})))

  (is (= {:errors
          {:card "nullable value must be a map with the keys `id`, `include_csv`, `include_xls`, and `dashboard_card_id`."}
          :specific-errors
          {:card
           ["value must be a map with the keys `include_csv`, `include_xls`, and `dashboard_card_id`., received: \"foobar\""]}}
         (mt/user-http-request
          :rasta :put 400 "alert/1" {:alert_condition  "rows"
                                     :alert_first_only false
                                     :card             "foobar"})))

  (is (= {:errors {:channels "nullable one or more map"}
          :specific-errors {:channels ["invalid type, received: \"foobar\""]}}
         (mt/user-http-request
          :rasta :put 400 "alert/1" {:alert_condition  "rows"
                                     :alert_first_only false
                                     :card             {:id 100, :include_csv false, :include_xls false, :dashboard_card_id nil}
                                     :channels         "foobar"})))

  (is (= {:errors {:channels "nullable one or more map"}
          :specific-errors {:channels [["invalid type, received: \"abc\""]]}}
         (mt/user-http-request
          :rasta :put 400 "alert/1" {:name             "abc"
                                     :alert_condition  "rows"
                                     :alert_first_only false
                                     :card             {:id 100, :include_csv false, :include_xls false, :dashboard_card_id nil}
                                     :channels         ["abc"]}))))

(defn default-alert-req
  ([card pulse-card-or-id]
   (default-alert-req card pulse-card-or-id {} []))
  ([card pulse-card-or-id alert-map users]
   (merge {:card             {:id (u/the-id card), :include_csv false, :include_xls false, :dashboard_card_id nil}
           :alert_condition  "rows"
           :alert_first_only false
           :channels         [(if (seq users)
                                (default-email-channel (u/the-id pulse-card-or-id) users)
                                (default-email-channel (u/the-id pulse-card-or-id)))]
           :skip_if_empty    false}
          alert-map)))

(defn basic-alert []
  {:alert_condition  "rows"
   :alert_first_only false
   :creator_id       (mt/user->id :rasta)
   :name             nil})

(defn recipient [pulse-channel-or-id username-keyword]
  (let [user (mt/fetch-user username-keyword)]
    {:user_id          (u/the-id user)
     :pulse_channel_id (u/the-id pulse-channel-or-id)}))

(defn pulse-card [alert-or-id card-or-id]
  {:pulse_id (u/the-id alert-or-id)
   :card_id  (u/the-id card-or-id)
   :position 0})

(defn pulse-channel [alert-or-id]
  {:pulse_id (u/the-id alert-or-id)})

(defn- alert-url [alert-or-id]
  (format "alert/%d" (u/the-id alert-or-id)))

(deftest update-alerts-admin-test
  (testing "Admin users can update any alert"
    (mt/with-temp [:model/Pulse                 alert (basic-alert)
                   :model/Card                  card  {}
                   :model/PulseCard             _     (pulse-card alert card)
                   :model/PulseChannel          pc    (pulse-channel alert)
                   :model/PulseChannelRecipient _     (recipient pc :rasta)]
      (is (= (default-alert card)
             (mt/with-model-cleanup [:model/Pulse]
               (alert-response
                ((alert-client :crowberto) :put 200 (alert-url alert)
                                           (default-alert-req card pc))))))))

  (testing "Admin users can update any alert, changing the related alert attributes"
    (mt/with-temp [:model/Pulse                 alert (basic-alert)
                   :model/Card                  card  {}
                   :model/PulseCard             _     (pulse-card alert card)
                   :model/PulseChannel          pc    (pulse-channel alert)
                   :model/PulseChannelRecipient _     (recipient pc :rasta)]
      (is (= (assoc (default-alert card)
                    :alert_first_only true
                    :alert_above_goal true
                    :alert_condition  "goal")
             (mt/with-model-cleanup [:model/Pulse]
               (alert-response
                ((alert-client :crowberto) :put 200 (alert-url alert)
                                           (default-alert-req card (u/the-id pc) {:alert_first_only true, :alert_above_goal true, :alert_condition "goal"}
                                                              [(mt/fetch-user :rasta)]))))))))

  (testing "Admin users can add a recipient, that recipient should be notified"
    (mt/with-temp [:model/Pulse                 alert (basic-alert)
                   :model/Card                  card  {}
                   :model/PulseCard             _     (pulse-card alert card)
                   :model/PulseChannel          pc    (pulse-channel alert)
                   :model/PulseChannelRecipient _     (recipient pc :crowberto)]
      (is (= [(-> (default-alert card)
                  (assoc-in [:channels 0 :recipients] (set (map recipient-details [:crowberto :rasta]))))
              (et/email-to :rasta {:subject "Crowberto Corv added you to an alert"
                                   :body    {"https://metabase.com/testmb" true, "now getting alerts" true}})]
             (with-alert-setup!
               [(et/with-expected-messages 1
                  (alert-response
                   (setify-recipient-emails
                    ((alert-client :crowberto) :put 200 (alert-url alert)
                                               (default-alert-req card pc {} [(mt/fetch-user :crowberto)
                                                                              (mt/fetch-user :rasta)])))))
                (et/regex-email-bodies #"https://metabase.com/testmb"
                                       #"now getting alerts")]))))))

(deftest update-alerts-non-admin-test
  (testing "Non-admin users can update alerts they created"
    (mt/with-temp [:model/Pulse                 alert (basic-alert)
                   :model/Card                  card  {}
                   :model/PulseCard             _     (pulse-card alert card)
                   :model/PulseChannel          pc    (pulse-channel alert)
                   :model/PulseChannelRecipient _     (recipient pc :rasta)]
      (is (= (-> (default-alert card)
                 (assoc-in [:card :collection_id] true))
             (with-alerts-in-writeable-collection! [alert]
               (mt/with-model-cleanup [:model/Pulse]
                 (alert-response
                  ((alert-client :rasta) :put 200 (alert-url alert)
                                         (default-alert-req card pc)))))))))

  (testing "Non-admin users cannot change the recipients of an alert"
    (mt/with-temp [:model/Pulse                 alert (basic-alert)
                   :model/Card                  card  {}
                   :model/PulseCard             _     (pulse-card alert card)
                   :model/PulseChannel          pc    (pulse-channel alert)
                   :model/PulseChannelRecipient _     (recipient pc :rasta)]
      (is (= (str "Non-admin users without monitoring or subscription permissions "
                  "are not allowed to modify the channels for an alert")
             (with-alerts-in-writeable-collection! [alert]
               (mt/with-model-cleanup [:model/Pulse]
                 ((alert-client :rasta) :put 403 (alert-url alert)
                                        (default-alert-req card pc {} [(mt/fetch-user :crowberto)])))))))))

(deftest admin-users-remove-recipient-test
  (testing "admin users can remove a recipieint, that recipient should be notified"
    (mt/with-temp [:model/Pulse                 alert (basic-alert)
                   :model/Card                  card  {}
                   :model/PulseCard             _     (pulse-card alert card)
                   :model/PulseChannel          pc    (pulse-channel alert)
                   :model/PulseChannelRecipient _     (recipient pc :crowberto)
                   :model/PulseChannelRecipient _     (recipient pc :rasta)]
      (with-alert-setup!
        (testing "API response"
          (is (= (-> (default-alert card)
                     (assoc-in [:channels 0 :recipients] [(recipient-details :crowberto)]))
                 (-> (mt/with-expected-messages 1
                       ((alert-client :crowberto) :put 200 (alert-url alert)
                                                  (default-alert-req card (u/the-id pc) {} [(mt/fetch-user :crowberto)])))
                     alert-response))))
        (testing "emails"
          (is (= (mt/email-to :rasta {:subject "You’ve been unsubscribed from an alert"
                                      :body    {"https://metabase.com/testmb"          true
                                                "letting you know that Crowberto Corv" true}})
                 (mt/regex-email-bodies #"https://metabase.com/testmb"
                                        #"letting you know that Crowberto Corv"))))))))

(deftest update-alert-permissions-test
  (testing "Non-admin users cannot update alerts for cards in a collection they don't have access to"
    (is (= "You don't have permissions to do that."
           (mt/with-non-admin-groups-no-root-collection-perms
             (mt/with-temp [:model/Pulse                 alert (assoc (basic-alert) :creator_id (mt/user->id :crowberto))
                            :model/Card                  card  {}
                            :model/PulseCard             _     (pulse-card alert card)
                            :model/PulseChannel          pc    (pulse-channel alert)
                            :model/PulseChannelRecipient _     (recipient pc :rasta)]
               (mt/with-non-admin-groups-no-root-collection-perms
                 (with-alert-setup!
                   ((alert-client :rasta) :put 403 (alert-url alert)
                                          (default-alert-req card pc)))))))))

  (testing "Non-admin users can't edit alerts if they're not in the recipient list"
    (is (= "You don't have permissions to do that."
           (mt/with-non-admin-groups-no-root-collection-perms
             (mt/with-temp [:model/Pulse                 alert (basic-alert)
                            :model/Card                  card  {}
                            :model/PulseCard             _     (pulse-card alert card)
                            :model/PulseChannel          pc    (pulse-channel alert)
                            :model/PulseChannelRecipient _     (recipient pc :crowberto)]
               (with-alert-setup!
                 ((alert-client :rasta) :put 403 (alert-url alert)
                                        (default-alert-req card pc))))))))

  (testing "Non-admin users can update alerts in collection they have view permisisons"
    (mt/with-non-admin-groups-no-root-collection-perms
      (with-alert-in-collection! [_ collection alert card]
        (mt/with-temp [:model/PulseCard pc (pulse-card alert card)]
          (perms/grant-collection-read-permissions! (perms-group/all-users) collection)

          (mt/user-http-request :rasta :put 200 (alert-url alert)
                                (dissoc (default-alert-req card pc {} []) :card :channels))

          (testing "but not allowed to edit the card"
            (mt/user-http-request :rasta :put 403 (alert-url alert)
                                  (dissoc (default-alert-req card pc {} []) :channels))))))))

(deftest update-alert-enable-http-channel-test
  (mt/with-temp [:model/Pulse     alert (basic-alert)
                 :model/Card      card  {}
                 :model/PulseCard _     (pulse-card alert card)
                 :model/Channel   {channel-id :id} {:type    :channel/http
                                                    :details {:auth-method "none"
                                                              :url         "https://metabasetest.com"}}]
    (testing "PUT /api/channel/:id can enable a HTTP channel for an alert"
      (is (=? {:channels [{:channel_type "http"
                           :channel_id   channel-id
                           :enabled true}]}
              (mt/user-http-request :crowberto :put 200 (alert-url alert)
                                    (default-alert-req card (u/the-id alert) {:channels [(default-http-channel channel-id)]} nil))))

      (testing "make sure it's in the database"
        (is (=? {:pulse_id     (:id alert)
                 :channel_type :http
                 :channel_id   channel-id}
                (t2/select-one :model/PulseChannel :pulse_id (:id alert))))))))

(deftest alert-event-test
  (mt/with-premium-features #{:audit-app}
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-temp [:model/Collection collection {}
                     :model/Card       card {:name          "My question"
                                             :display       "bar"
                                             :collection_id (u/the-id collection)}]
        (perms/grant-collection-read-permissions! (perms-group/all-users) collection)
        (with-alert-setup!
          (et/with-expected-messages 1
            (let [alert-details {:card             {:id (u/the-id card), :include_csv false, :include_xls false, :dashboard_card_id nil}
                                 :collection_id    (u/the-id collection)
                                 :alert_condition  "goal"
                                 :alert_above_goal true
                                 :alert_first_only false
                                 :channels         [daily-email-channel]}
                  alert         (mt/user-http-request :rasta :post 200 "alert" alert-details)]
              (testing "Creating alert also logs event."
                (is (= {:topic    :alert-create
                        :user_id  (mt/user->id :rasta)
                        :model    "Card"
                        :model_id (u/the-id alert)
                        :details  {:archived     false
                                   :name         "My question"
                                   :card_id      (u/the-id card)
                                   :parameters   []
                                   :channel      ["email"]
                                   :schedule     ["daily"]
                                   :recipients   [[]]}}
                       (mt/latest-audit-log-entry :alert-create (u/the-id alert)))))
              (testing "Updating alert also logs event."
                (mt/user-http-request :crowberto :put 200 (alert-url alert) alert-details)
                (is (= {:topic    :alert-update
                        :user_id  (mt/user->id :crowberto)
                        :model    "Card"
                        :model_id (u/the-id alert)
                        :details  {:archived   true
                                   :name       "My question"
                                   :card_id    (u/the-id card)
                                   :parameters []
                                   :channel    ["email"]
                                   :schedule   ["daily"]
                                   :recipients [[]]}}
                       (mt/latest-audit-log-entry :alert-update (u/the-id alert))))))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            GET /alert/question/:id                                             |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- basic-alert-query []
  {:name          "Foo"
   :dataset_query {:database (mt/id)
                   :type     :query
                   :query    {:source-table (mt/id :checkins)
                              :aggregation  [["count"]]
                              :breakout     [[:field (mt/id :checkins :date) {:temporal-unit :hour}]]}}})

(defn- alert-question-url [card-or-id & [archived]]
  (if archived
    (format "alert/question/%d?archived=%b" (u/the-id card-or-id) archived)
    (format "alert/question/%d" (u/the-id card-or-id))))

(defn- api:alert-question-count [user-kw card-or-id & [archived]]
  (count ((alert-client user-kw) :get 200 (alert-question-url card-or-id archived))))

(deftest get-alert-question-test
  (mt/with-temp [:model/Card                 card  (basic-alert-query)
                 :model/Pulse                alert {:alert_condition  "rows"
                                                    :alert_first_only false
                                                    :alert_above_goal nil
                                                    :skip_if_empty    true
                                                    :name             nil}
                 :model/PulseCard             _    (pulse-card alert card)
                 :model/PulseChannel          pc   (pulse-channel alert)
                 :model/PulseChannelRecipient _    (recipient pc :rasta)]
    (mt/with-non-admin-groups-no-root-collection-perms
      (with-alert-setup!
        (with-alerts-in-readable-collection! [alert]
          (is (= [(-> (default-alert card)
                      (assoc :can_write false)
                      (update-in [:channels 0] merge {:schedule_hour 15, :schedule_type "daily"})
                      (assoc-in [:card :collection_id] true))]
                 (map alert-response
                      ((alert-client :rasta) :get 200 (alert-question-url card))))))))))

(deftest get-alert-question-test-2
  (testing "Non-admin users shouldn't see alerts they created if they're no longer recipients"
    (mt/with-temp [:model/Card                  card  (basic-alert-query)
                   :model/Pulse                 alert (assoc (basic-alert) :alert_above_goal true)
                   :model/PulseCard             _     (pulse-card alert card)
                   :model/PulseChannel          pc    (pulse-channel alert)
                   :model/PulseChannelRecipient pcr   (recipient pc :rasta)
                   :model/PulseChannelRecipient _     (recipient pc :crowberto)]
      (with-alerts-in-readable-collection! [alert]
        (with-alert-setup!
          (is (= 1
                 (count ((alert-client :rasta) :get 200 (alert-question-url card)))))
          (t2/delete! :model/PulseChannelRecipient :id (u/the-id pcr))
          (is (zero? (api:alert-question-count :rasta card))))))))

(deftest get-alert-question-test-3
  (testing "Non-admin users should not see others alerts, admins see all alerts"
    (mt/with-temp [:model/Card                  card    (basic-alert-query)
                   :model/Pulse                 alert-1 (assoc (basic-alert)
                                                               :alert_above_goal false)
                   :model/PulseCard             _       (pulse-card alert-1 card)
                   :model/PulseChannel          pc-1    (pulse-channel alert-1)
                   :model/PulseChannelRecipient _       (recipient pc-1 :rasta)
                   ;; A separate admin created alert
                   :model/Pulse                 alert-2 (assoc (basic-alert)
                                                               :alert_above_goal false
                                                               :creator_id       (mt/user->id :crowberto))
                   :model/PulseCard             _       (pulse-card alert-2 card)
                   :model/PulseChannel          pc-2    (pulse-channel alert-2)
                   :model/PulseChannelRecipient _       (recipient pc-2 :crowberto)
                   :model/PulseChannel          _       (assoc (pulse-channel alert-2) :channel_type "slack")]
      (with-alerts-in-readable-collection! [alert-1 alert-2]
        (with-alert-setup!
          (is (= 1
                 (api:alert-question-count :rasta card)))
          (is (= 2
                 (api:alert-question-count :crowberto card))))))))

(deftest get-alert-question-test-4
  (testing "Archived alerts are excluded by default, unless `archived` parameter is sent"
    (mt/with-temp [:model/Card                  card    (basic-alert-query)
                   :model/Pulse                 alert-1 (assoc (basic-alert)
                                                               :alert_above_goal false
                                                               :archived         true)
                   :model/PulseCard             _       (pulse-card alert-1 card)
                   :model/PulseChannel          pc-1    (pulse-channel alert-1)
                   :model/PulseChannelRecipient _       (recipient pc-1 :rasta)
                   ;; A separate admin created alert
                   :model/Pulse                 alert-2 (assoc (basic-alert)
                                                               :alert_above_goal false
                                                               :archived         true
                                                               :creator_id       (mt/user->id :crowberto))
                   :model/PulseCard             _       (pulse-card alert-2 card)
                   :model/PulseChannel          pc-2    (pulse-channel alert-2)
                   :model/PulseChannelRecipient _       (recipient pc-2 :crowberto)
                   :model/PulseChannel          _       (assoc (pulse-channel alert-2) :channel_type "slack")]
      (with-alerts-in-readable-collection! [alert-1 alert-2]
        (with-alert-setup!
          (is (zero? (api:alert-question-count :rasta card)))
          (is (zero? (api:alert-question-count :crowberto card)))
          (is (= 1
                 (api:alert-question-count :rasta card true)))
          (is (= 2
                 (api:alert-question-count :crowberto card true))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         PUT /api/alert/:id/unsubscribe                                         |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- alert-unsubscribe-url [alert-or-id]
  (format "alert/%d/subscription" (u/the-id alert-or-id)))

(defn- api:unsubscribe! [user-kw expected-status-code alert-or-id]
  (mt/user-http-request user-kw :delete expected-status-code (alert-unsubscribe-url alert-or-id)))

(defn- recipient-emails [results]
  (->> results
       first
       :channels
       first
       :recipients
       (map :email)
       set))

(deftest unsubscribe-tests
  (testing "Alert has two recipients, and non-admin unsubscribes"
    (mt/with-temp [:model/Card                  card  (basic-alert-query)
                   :model/Pulse                 alert (basic-alert)
                   :model/PulseCard             _     (pulse-card alert card)
                   :model/PulseChannel          pc    (pulse-channel alert)
                   :model/PulseChannelRecipient _     (recipient pc :rasta)
                   :model/PulseChannelRecipient _     (recipient pc :crowberto)]
      (with-alerts-in-readable-collection! [alert]
        (with-alert-setup!
          (is (= #{"crowberto@metabase.com" "rasta@metabase.com"}
                 (recipient-emails (mt/user-http-request
                                    :rasta :get 200 (alert-question-url card)))))
          (et/with-expected-messages 1
            (api:unsubscribe! :rasta 204 alert))
          (is (= #{"crowberto@metabase.com"}
                 (recipient-emails (mt/user-http-request
                                    :crowberto :get 200 (alert-question-url card)))))
          (is (= (unsubscribe-email :rasta {"Foo" true})
                 (et/regex-email-bodies #"https://metabase.com/testmb"
                                        #"Foo"))))))))

(deftest unsubscribe-tests-2
  (testing "Alert has two recipients, and admin unsubscribes"
    (mt/with-temp [:model/Card                  card  (basic-alert-query)
                   :model/Pulse                 alert (basic-alert)
                   :model/PulseCard             _     (pulse-card alert card)
                   :model/PulseChannel          pc    (pulse-channel alert)
                   :model/PulseChannelRecipient _     (recipient pc :rasta)
                   :model/PulseChannelRecipient _     (recipient pc :crowberto)]
      (with-alerts-in-readable-collection! [alert]
        (with-alert-setup!
          (is (= #{"crowberto@metabase.com" "rasta@metabase.com"}
                 (recipient-emails (mt/user-http-request :rasta :get 200 (alert-question-url card)))))
          (et/with-expected-messages 1
            (api:unsubscribe! :crowberto 204 alert))
          (is (= #{"rasta@metabase.com"}
                 (recipient-emails (mt/user-http-request :crowberto :get 200 (alert-question-url card)))))
          (is (= (unsubscribe-email :crowberto {"Foo" true})
                 (et/regex-email-bodies #"https://metabase.com/testmb"
                                        #"Foo"))))))))

(deftest unsubscribe-tests-3
  (testing "Alert should be archived if the last recipient unsubscribes"
    (mt/with-temp [:model/Card                  card  (basic-alert-query)
                   :model/Pulse                 alert (basic-alert)
                   :model/PulseCard             _     (pulse-card alert card)
                   :model/PulseChannel          pc    (pulse-channel alert)
                   :model/PulseChannelRecipient _     (recipient pc :rasta)]
      (with-alerts-in-readable-collection! [alert]
        (with-alert-setup!
          (et/with-expected-messages 1 (api:unsubscribe! :rasta 204 alert))
          (is (t2/select-one-fn :archived :model/Pulse :id (u/the-id alert)))
          (is (= (unsubscribe-email :rasta {"Foo" true})
                 (et/regex-email-bodies #"https://metabase.com/testmb"
                                        #"Foo"))))))))

(deftest unsubscribe-tests-4
  (testing "Alert should not be archived if there is a slack channel"
    (mt/with-temp [:model/Card                  card  (basic-alert-query)
                   :model/Pulse                 alert (basic-alert)
                   :model/PulseCard             _     (pulse-card alert card)
                   :model/PulseChannel          pc-1  (assoc (pulse-channel alert) :channel_type :email)
                   :model/PulseChannel          _     (assoc (pulse-channel alert) :channel_type :slack)
                   :model/PulseChannelRecipient _     (recipient pc-1 :rasta)]
      (with-alerts-in-readable-collection! [alert]
        (with-alert-setup!
          (et/with-expected-messages 1 (api:unsubscribe! :rasta 204 alert))
          (is (not (t2/select-one-fn :archived :model/Pulse :id (u/the-id alert))))
          (is (= (unsubscribe-email :rasta {"Foo" true})
                 (et/regex-email-bodies #"https://metabase.com/testmb"
                                        #"Foo"))))))))

(deftest unsubscribe-tests-5
  (testing "If email is disabled, users should be unsubscribed"
    (mt/with-temp [:model/Card                  card  (basic-alert-query)
                   :model/Pulse                 alert (basic-alert)
                   :model/PulseCard             _     (pulse-card alert card)
                   :model/PulseChannel          pc-1  (assoc (pulse-channel alert) :channel_type :email)
                   :model/PulseChannel          _pc-2 (assoc (pulse-channel alert) :channel_type :slack)
                   :model/PulseChannelRecipient _     (recipient pc-1 :rasta)]
      (with-alerts-in-readable-collection! [alert]
        (with-alert-setup!
          (et/with-expected-messages 1
            ((alert-client :crowberto)
             :put 200 (alert-url alert) (assoc-in (default-alert-req card pc-1) [:channels 0 :enabled] false)))
          (is (not (t2/select-one-fn :archived :model/Pulse :id (u/the-id alert))))
          (is (= (et/email-to :rasta {:subject "You’ve been unsubscribed from an alert",
                                      :body    {"https://metabase.com/testmb"          true,
                                                "letting you know that Crowberto Corv" true}})
                 (et/regex-email-bodies #"https://metabase.com/testmb"
                                        #"letting you know that Crowberto Corv"))))))))

(deftest unsubscribe-tests-6
  (testing "Re-enabling email should send users a subscribe notification"
    (mt/with-temp [:model/Card                  card  (basic-alert-query)
                   :model/Pulse                 alert (basic-alert)
                   :model/PulseCard             _     (pulse-card alert card)
                   :model/PulseChannel          pc-1  (assoc (pulse-channel alert) :channel_type :email, :enabled false)
                   :model/PulseChannel          _pc-2 (assoc (pulse-channel alert) :channel_type :slack)
                   :model/PulseChannelRecipient _     (recipient pc-1 :rasta)]
      (with-alerts-in-readable-collection! [alert]
        (with-alert-setup!
          (et/with-expected-messages 1
            ((alert-client :crowberto)
             :put 200 (alert-url alert) (assoc-in (default-alert-req card pc-1) [:channels 0 :enabled] true)))
          (is (not (t2/select-one-fn :archived :model/Pulse :id (u/the-id alert))))
          (is (= (et/email-to :rasta {:subject "Crowberto Corv added you to an alert",
                                      :body    {"https://metabase.com/testmb"    true,
                                                "now getting alerts about .*Foo" true}})
                 (et/regex-email-bodies #"https://metabase.com/testmb"
                                        #"now getting alerts about .*Foo"))))))))

(deftest alert-unsubscribe-event-test
  (testing "Alert has two recipients, and non-admin unsubscribes"
    (mt/with-premium-features #{:audit-app}
      (mt/with-temp [:model/Card                  card  (basic-alert-query)
                     :model/Pulse                 alert (basic-alert)
                     :model/PulseCard             _     (pulse-card alert card)
                     :model/PulseChannel          pc    (pulse-channel alert)
                     :model/PulseChannelRecipient _     (recipient pc :rasta)]
        (api:unsubscribe! :rasta 204 alert)
        (is (= {:topic    :alert-unsubscribe
                :user_id  (mt/user->id :rasta)
                :model    "Pulse"
                :model_id nil
                :details  {:email "rasta@metabase.com"}}
               (mt/latest-audit-log-entry :alert-unsubscribe)))))))
