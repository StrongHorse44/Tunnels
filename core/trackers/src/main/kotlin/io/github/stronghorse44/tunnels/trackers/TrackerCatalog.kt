package io.github.stronghorse44.tunnels.trackers

/** What an embedded SDK is for. One SDK may fall into several. */
enum class TrackerCategory(val label: String) {
    ANALYTICS("analytics"),
    ADS("ads"),
    CRASH("crash"),
    ATTRIBUTION("attribution"),
    PUSH("push"),
    PROFILING("profiling"),
    LOCATION("location"),
    IDENTIFICATION("identification"),
    SUPPORT("support"),
    ;

    companion object {
        fun byLabel(label: String): TrackerCategory? = entries.firstOrNull { it.label == label }
    }
}

/**
 * One embedded SDK we can recognise. [classPrefixes] are dex type-descriptor prefixes
 * ("Lcom/vendor/sdk/"): a class whose descriptor starts with one belongs to this SDK.
 */
data class Tracker(
    val id: String,
    val name: String,
    val vendor: String,
    val categories: Set<TrackerCategory>,
    val classPrefixes: List<String>,
)

/**
 * Own curated list of well-known embedded SDKs. Categories describe what the SDK does, not a verdict:
 * push and crash reporting are listed so the user can see them, not because they are surveillance.
 */
object TrackerCatalog {
    private fun t(id: String, name: String, vendor: String, categories: Set<TrackerCategory>, vararg prefixes: String) =
        Tracker(id, name, vendor, categories, prefixes.toList())

    private val ANALYTICS = setOf(TrackerCategory.ANALYTICS)
    private val ADS = setOf(TrackerCategory.ADS)
    private val CRASH = setOf(TrackerCategory.CRASH)
    private val ATTRIBUTION = setOf(TrackerCategory.ATTRIBUTION)
    private val PUSH = setOf(TrackerCategory.PUSH)
    private val PROFILING = setOf(TrackerCategory.PROFILING)
    private val LOCATION = setOf(TrackerCategory.LOCATION)
    private val IDENTIFICATION = setOf(TrackerCategory.IDENTIFICATION)
    private val SUPPORT = setOf(TrackerCategory.SUPPORT)

    val all: List<Tracker> = listOf(
        // Google
        t("firebase_analytics", "Firebase Analytics", "Google", ANALYTICS, "Lcom/google/firebase/analytics/", "Lcom/google/android/gms/measurement/"),
        t("firebase_crashlytics", "Firebase Crashlytics", "Google", CRASH, "Lcom/google/firebase/crashlytics/", "Lcom/crashlytics/android/"),
        t("firebase_messaging", "Firebase Cloud Messaging", "Google", PUSH, "Lcom/google/firebase/messaging/"),
        t("firebase_perf", "Firebase Performance", "Google", PROFILING, "Lcom/google/firebase/perf/"),
        t("firebase_inappmessaging", "Firebase In-App Messaging", "Google", ANALYTICS, "Lcom/google/firebase/inappmessaging/"),
        t("google_admob", "Google Mobile Ads (AdMob)", "Google", ADS, "Lcom/google/android/gms/ads/", "Lcom/google/ads/mediation/"),
        t("google_ad_id", "Google Advertising ID", "Google", IDENTIFICATION, "Lcom/google/android/gms/ads/identifier/"),
        t("google_app_set", "Google App Set ID", "Google", IDENTIFICATION, "Lcom/google/android/gms/appset/"),
        t("google_analytics", "Google Analytics (legacy)", "Google", ANALYTICS, "Lcom/google/android/gms/analytics/", "Lcom/google/analytics/tracking/"),
        t("google_tag_manager", "Google Tag Manager", "Google", ANALYTICS, "Lcom/google/android/gms/tagmanager/", "Lcom/google/tagmanager/"),
        t("play_install_referrer", "Play Install Referrer", "Google", ATTRIBUTION, "Lcom/android/installreferrer/"),
        // Meta
        t("facebook_app_events", "Meta App Events", "Meta", setOf(TrackerCategory.ANALYTICS, TrackerCategory.ATTRIBUTION), "Lcom/facebook/appevents/"),
        t("facebook_audience_network", "Meta Audience Network", "Meta", ADS, "Lcom/facebook/ads/"),
        // Attribution
        t("appsflyer", "AppsFlyer", "AppsFlyer", setOf(TrackerCategory.ATTRIBUTION, TrackerCategory.ANALYTICS), "Lcom/appsflyer/"),
        t("adjust", "Adjust", "Adjust", setOf(TrackerCategory.ATTRIBUTION, TrackerCategory.ANALYTICS), "Lcom/adjust/sdk/"),
        t("branch", "Branch", "Branch Metrics", ATTRIBUTION, "Lio/branch/"),
        t("kochava", "Kochava", "Kochava", ATTRIBUTION, "Lcom/kochava/"),
        t("singular", "Singular", "Singular", ATTRIBUTION, "Lcom/singular/sdk/"),
        t("tenjin", "Tenjin", "Tenjin", ATTRIBUTION, "Lcom/tenjin/android/"),
        t("tune", "TUNE (MobileAppTracking)", "TUNE", ATTRIBUTION, "Lcom/tune/", "Lcom/mobileapptracker/"),
        t("airbridge", "Airbridge", "AB180", ATTRIBUTION, "Lio/airbridge/"),
        t("openinstall", "openinstall", "openinstall", ATTRIBUTION, "Lcom/fm/openinstall/"),
        t("tiktok_business", "TikTok Business SDK", "ByteDance", setOf(TrackerCategory.ANALYTICS, TrackerCategory.ATTRIBUTION), "Lcom/tiktok/appevents/"),
        // Crash reporting
        t("sentry", "Sentry", "Functional Software", CRASH, "Lio/sentry/"),
        t("bugsnag", "Bugsnag", "SmartBear", CRASH, "Lcom/bugsnag/"),
        t("acra", "ACRA", "ACRA (open source)", CRASH, "Lorg/acra/"),
        t("appcenter", "App Center", "Microsoft", setOf(TrackerCategory.CRASH, TrackerCategory.ANALYTICS), "Lcom/microsoft/appcenter/"),
        t("hockeyapp", "HockeyApp", "Microsoft", CRASH, "Lnet/hockeyapp/android/"),
        t("instabug", "Instabug", "Instabug", setOf(TrackerCategory.CRASH, TrackerCategory.SUPPORT, TrackerCategory.PROFILING), "Lcom/instabug/"),
        t("bugly", "Bugly", "Tencent", CRASH, "Lcom/tencent/bugly/"),
        t("rollbar", "Rollbar", "Rollbar", CRASH, "Lcom/rollbar/"),
        t("raygun", "Raygun", "Raygun", CRASH, "Lcom/raygun/raygun4android/"),
        t("embrace", "Embrace", "Embrace", setOf(TrackerCategory.CRASH, TrackerCategory.PROFILING), "Lio/embrace/android/"),
        t("crittercism", "Apteligent (Crittercism)", "VMware", CRASH, "Lcom/crittercism/"),
        t("splunk_mint", "Splunk MINT", "Splunk", CRASH, "Lcom/splunk/mint/"),
        t("fabric", "Fabric", "Twitter", setOf(TrackerCategory.CRASH, TrackerCategory.ANALYTICS), "Lio/fabric/sdk/android/"),
        t("bugfender", "Bugfender", "Beenario", CRASH, "Lcom/bugfender/sdk/"),
        // Performance / session replay
        t("datadog", "Datadog RUM", "Datadog", setOf(TrackerCategory.PROFILING, TrackerCategory.CRASH), "Lcom/datadog/android/"),
        t("newrelic", "New Relic Mobile", "New Relic", setOf(TrackerCategory.PROFILING, TrackerCategory.CRASH), "Lcom/newrelic/agent/"),
        t("dynatrace", "Dynatrace OneAgent", "Dynatrace", PROFILING, "Lcom/dynatrace/"),
        t("appdynamics", "AppDynamics", "Cisco", PROFILING, "Lcom/appdynamics/eumagent/"),
        t("smartlook", "Smartlook", "Smartlook", PROFILING, "Lcom/smartlook/"),
        t("uxcam", "UXCam", "UXCam", PROFILING, "Lcom/uxcam/"),
        t("fullstory", "FullStory", "FullStory", PROFILING, "Lcom/fullstory/"),
        t("appsee", "Appsee", "Appsee", PROFILING, "Lcom/appsee/"),
        t("contentsquare", "Contentsquare", "Contentsquare", setOf(TrackerCategory.PROFILING, TrackerCategory.ANALYTICS), "Lcom/contentsquare/android/"),
        t("glassbox", "Glassbox", "Glassbox", PROFILING, "Lcom/clarisite/mobile/"),
        t("microsoft_clarity", "Microsoft Clarity", "Microsoft", PROFILING, "Lcom/microsoft/clarity/"),
        // Product analytics
        t("amplitude", "Amplitude", "Amplitude", ANALYTICS, "Lcom/amplitude/"),
        t("mixpanel", "Mixpanel", "Mixpanel", ANALYTICS, "Lcom/mixpanel/android/"),
        t("segment", "Segment", "Twilio", ANALYTICS, "Lcom/segment/analytics/"),
        t("rudderstack", "RudderStack", "RudderStack", ANALYTICS, "Lcom/rudderstack/android/"),
        t("flurry", "Flurry", "Yahoo", setOf(TrackerCategory.ANALYTICS, TrackerCategory.ADS), "Lcom/flurry/"),
        t("countly", "Countly", "Countly", ANALYTICS, "Lly/count/android/sdk/"),
        t("mparticle", "mParticle", "mParticle", ANALYTICS, "Lcom/mparticle/"),
        t("heap", "Heap", "Heap", ANALYTICS, "Lcom/heapanalytics/android/"),
        t("pendo", "Pendo", "Pendo", ANALYTICS, "Lsdk/pendo/io/"),
        t("tealium", "Tealium", "Tealium", ANALYTICS, "Lcom/tealium/"),
        t("adobe_experience", "Adobe Experience Platform", "Adobe", ANALYTICS, "Lcom/adobe/marketing/mobile/", "Lcom/adobe/mobile/"),
        t("matomo", "Matomo (Piwik)", "Matomo", ANALYTICS, "Lorg/matomo/sdk/", "Lorg/piwik/sdk/"),
        t("snowplow", "Snowplow", "Snowplow", ANALYTICS, "Lcom/snowplowanalytics/"),
        t("posthog", "PostHog", "PostHog", ANALYTICS, "Lcom/posthog/"),
        t("gameanalytics", "GameAnalytics", "GameAnalytics", ANALYTICS, "Lcom/gameanalytics/sdk/"),
        t("devtodev", "devtodev", "devtodev", ANALYTICS, "Lcom/devtodev/"),
        t("comscore", "comScore", "comScore", ANALYTICS, "Lcom/comscore/"),
        t("nielsen", "Nielsen App SDK", "Nielsen", ANALYTICS, "Lcom/nielsen/app/sdk/"),
        t("chartbeat", "Chartbeat", "Chartbeat", ANALYTICS, "Lcom/chartbeat/androidsdk/"),
        t("optimizely", "Optimizely", "Optimizely", ANALYTICS, "Lcom/optimizely/ab/"),
        t("taplytics", "Taplytics", "Taplytics", ANALYTICS, "Lcom/taplytics/sdk/"),
        t("apptimize", "Apptimize", "Apptimize", ANALYTICS, "Lcom/apptimize/"),
        t("aws_pinpoint", "Amazon Pinpoint", "Amazon", setOf(TrackerCategory.ANALYTICS, TrackerCategory.PUSH), "Lcom/amazonaws/mobileconnectors/pinpoint/"),
        t("amplify_analytics", "AWS Amplify Analytics", "Amazon", ANALYTICS, "Lcom/amplifyframework/analytics/"),
        t("umeng", "Umeng", "Alibaba", setOf(TrackerCategory.ANALYTICS, TrackerCategory.PUSH), "Lcom/umeng/"),
        t("tencent_mta", "Tencent Mobile Analytics", "Tencent", ANALYTICS, "Lcom/tencent/stat/"),
        t("baidu_mobstat", "Baidu Mobile Stat", "Baidu", ANALYTICS, "Lcom/baidu/mobstat/"),
        t("huawei_analytics", "Huawei Analytics Kit", "Huawei", ANALYTICS, "Lcom/huawei/hms/analytics/"),
        t("appmetrica", "AppMetrica", "Yandex", setOf(TrackerCategory.ANALYTICS, TrackerCategory.ATTRIBUTION), "Lcom/yandex/metrica/", "Lio/appmetrica/analytics/"),
        // Engagement / push
        t("braze", "Braze (Appboy)", "Braze", setOf(TrackerCategory.ANALYTICS, TrackerCategory.PUSH), "Lcom/braze/", "Lcom/appboy/"),
        t("onesignal", "OneSignal", "OneSignal", PUSH, "Lcom/onesignal/"),
        t("airship", "Airship", "Airship", setOf(TrackerCategory.PUSH, TrackerCategory.ANALYTICS), "Lcom/urbanairship/"),
        t("clevertap", "CleverTap", "CleverTap", setOf(TrackerCategory.ANALYTICS, TrackerCategory.PUSH), "Lcom/clevertap/android/"),
        t("moengage", "MoEngage", "MoEngage", setOf(TrackerCategory.ANALYTICS, TrackerCategory.PUSH), "Lcom/moengage/"),
        t("leanplum", "Leanplum", "CleverTap", setOf(TrackerCategory.ANALYTICS, TrackerCategory.PUSH), "Lcom/leanplum/"),
        t("localytics", "Localytics", "Upland", setOf(TrackerCategory.ANALYTICS, TrackerCategory.PUSH), "Lcom/localytics/android/", "Lcom/localytics/androidx/"),
        t("swrve", "Swrve", "Swrve", setOf(TrackerCategory.ANALYTICS, TrackerCategory.PUSH), "Lcom/swrve/sdk/"),
        t("pushwoosh", "Pushwoosh", "Pushwoosh", PUSH, "Lcom/pushwoosh/"),
        t("batch", "Batch", "Batch", setOf(TrackerCategory.PUSH, TrackerCategory.ANALYTICS), "Lcom/batch/android/"),
        t("iterable", "Iterable", "Iterable", setOf(TrackerCategory.PUSH, TrackerCategory.ANALYTICS), "Lcom/iterable/iterableapi/"),
        t("salesforce_mc", "Salesforce Marketing Cloud", "Salesforce", setOf(TrackerCategory.PUSH, TrackerCategory.ANALYTICS), "Lcom/salesforce/marketingcloud/", "Lcom/exacttarget/etpushsdk/"),
        t("sailthru", "Sailthru Mobile (Carnival)", "Marigold", setOf(TrackerCategory.PUSH, TrackerCategory.ANALYTICS), "Lcom/sailthru/mobile/sdk/", "Lcom/carnival/sdk/"),
        t("pusher_beams", "Pusher Beams", "Pusher", PUSH, "Lcom/pusher/pushnotifications/"),
        t("azure_notification_hubs", "Azure Notification Hubs", "Microsoft", PUSH, "Lcom/microsoft/windowsazure/messaging/"),
        t("huawei_push", "Huawei Push Kit", "Huawei", PUSH, "Lcom/huawei/hms/push/"),
        t("jpush", "JPush", "Aurora Mobile", PUSH, "Lcn/jpush/android/"),
        t("getui", "Getui", "Getui", PUSH, "Lcom/igexin/"),
        t("mipush", "Mi Push", "Xiaomi", PUSH, "Lcom/xiaomi/mipush/"),
        t("heytap_push", "HeyTap Push", "OPPO", PUSH, "Lcom/heytap/msp/push/"),
        t("vivo_push", "vivo Push", "vivo", PUSH, "Lcom/vivo/push/"),
        t("tencent_xg", "Tencent TPNS (XG Push)", "Tencent", PUSH, "Lcom/tencent/android/tpush/"),
        t("alicloud_push", "Alibaba Cloud Push", "Alibaba", PUSH, "Lcom/alibaba/sdk/android/push/"),
        // Ads
        t("applovin", "AppLovin MAX", "AppLovin", ADS, "Lcom/applovin/"),
        t("unity_ads", "Unity Ads", "Unity", ADS, "Lcom/unity3d/ads/", "Lcom/unity3d/services/"),
        t("ironsource", "ironSource", "Unity", ADS, "Lcom/ironsource/"),
        t("vungle", "Vungle", "Liftoff", ADS, "Lcom/vungle/"),
        t("chartboost", "Chartboost", "Chartboost", ADS, "Lcom/chartboost/"),
        t("inmobi", "InMobi", "InMobi", ADS, "Lcom/inmobi/"),
        t("criteo", "Criteo", "Criteo", ADS, "Lcom/criteo/"),
        t("smaato", "Smaato", "Smaato", ADS, "Lcom/smaato/"),
        t("pubmatic", "PubMatic OpenWrap", "PubMatic", ADS, "Lcom/pubmatic/"),
        t("mintegral", "Mintegral", "Mintegral", ADS, "Lcom/mbridge/", "Lcom/mintegral/"),
        t("pangle", "Pangle", "ByteDance", ADS, "Lcom/bytedance/sdk/openadsdk/"),
        t("tapjoy", "Tapjoy", "Tapjoy", ADS, "Lcom/tapjoy/"),
        t("fyber", "Fyber (Digital Turbine)", "Digital Turbine", ADS, "Lcom/fyber/"),
        t("adcolony", "AdColony", "Digital Turbine", ADS, "Lcom/adcolony/sdk/"),
        t("mopub", "MoPub", "Twitter", ADS, "Lcom/mopub/"),
        t("amazon_ads", "Amazon Publisher Services", "Amazon", ADS, "Lcom/amazon/device/ads/"),
        t("startapp", "Start.io (StartApp)", "Start.io", ADS, "Lcom/startapp/"),
        t("ogury", "Ogury", "Ogury", ADS, "Lio/presage/", "Lcom/ogury/"),
        t("moloco", "Moloco", "Moloco", ADS, "Lcom/moloco/sdk/"),
        t("verizon_ads", "Verizon Media Ads", "Verizon", ADS, "Lcom/verizon/ads/"),
        t("bidmachine", "BidMachine", "BidMachine", ADS, "Lio/bidmachine/"),
        t("appnexus", "AppNexus (Xandr)", "Microsoft", ADS, "Lcom/appnexus/opensdk/"),
        t("teads", "Teads", "Teads", ADS, "Ltv/teads/"),
        t("prebid", "Prebid Mobile", "Prebid.org", ADS, "Lorg/prebid/mobile/"),
        t("hyprmx", "HyprMX", "HyprMX", ADS, "Lcom/hyprmx/"),
        t("kidoz", "KIDOZ", "KIDOZ", ADS, "Lcom/kidoz/sdk/"),
        t("appodeal", "Appodeal", "Appodeal", ADS, "Lcom/appodeal/ads/"),
        t("adform", "Adform", "Adform", ADS, "Lcom/adform/sdk/"),
        t("taboola", "Taboola", "Taboola", ADS, "Lcom/taboola/android/"),
        t("outbrain", "Outbrain", "Outbrain", ADS, "Lcom/outbrain/OBSDK/"),
        t("mytarget", "myTarget", "VK", ADS, "Lcom/my/target/"),
        t("yandex_ads", "Yandex Mobile Ads", "Yandex", ADS, "Lcom/yandex/mobile/ads/"),
        t("huawei_ads", "Huawei Ads Kit", "Huawei", ADS, "Lcom/huawei/hms/ads/"),
        t("snap_adkit", "Snap Audience Network", "Snap", ADS, "Lcom/snap/adkit/"),
        t("kakao_adfit", "Kakao AdFit", "Kakao", ADS, "Lcom/kakao/adfit/"),
        t("pubnative", "PubNative (Verve)", "Verve", ADS, "Lnet/pubnative/"),
        t("pollfish", "Pollfish", "Prodege", ADS, "Lcom/pollfish/"),
        // Location
        t("mapbox_telemetry", "Mapbox Telemetry", "Mapbox", setOf(TrackerCategory.LOCATION, TrackerCategory.ANALYTICS), "Lcom/mapbox/android/telemetry/"),
        t("foursquare_pilgrim", "Foursquare Pilgrim / Movement", "Foursquare", LOCATION, "Lcom/foursquare/pilgrim/", "Lcom/foursquare/movement/"),
        t("radar", "Radar", "Radar Labs", LOCATION, "Lio/radar/sdk/"),
        t("cuebiq", "Cuebiq", "Cuebiq", LOCATION, "Lcom/cuebiq/"),
        t("huq", "Huq", "Huq Industries", LOCATION, "Lio/huq/sourcekit/"),
        t("xmode", "X-Mode (Outlogic)", "Outlogic", LOCATION, "Lio/xmode/"),
        t("placer", "Placer", "Placer.ai", LOCATION, "Lcom/placer/client/"),
        t("inmarket", "inMarket", "inMarket", LOCATION, "Lcom/inmarket/"),
        t("gimbal", "Gimbal", "Infillion", LOCATION, "Lcom/gimbal/android/"),
        t("tutela", "Tutela", "Comlinkdata", setOf(TrackerCategory.LOCATION, TrackerCategory.ANALYTICS), "Lcom/tutelatechnologies/"),
        t("opensignal", "Opensignal", "Opensignal", setOf(TrackerCategory.LOCATION, TrackerCategory.ANALYTICS), "Lcom/opensignal/"),
        t("baidu_location", "Baidu Location", "Baidu", LOCATION, "Lcom/baidu/location/"),
        t("amap_location", "AMap Location", "Alibaba", LOCATION, "Lcom/amap/api/location/"),
        t("tencent_location", "Tencent Location", "Tencent", LOCATION, "Lcom/tencent/map/geolocation/"),
        // Device identification / fraud
        t("sift", "Sift", "Sift", IDENTIFICATION, "Lsiftscience/android/"),
        t("fingerprintjs", "Fingerprint", "FingerprintJS", IDENTIFICATION, "Lcom/fingerprintjs/android/"),
        t("seon", "SEON", "SEON", IDENTIFICATION, "Lio/seon/androidsdk/"),
        t("forter", "Forter", "Forter", IDENTIFICATION, "Lcom/forter/mobile/"),
        t("riskified", "Riskified", "Riskified", IDENTIFICATION, "Lcom/riskified/android_sdk/"),
        t("kount", "Kount", "Kount", IDENTIFICATION, "Lcom/kount/api/"),
        t("threatmetrix", "ThreatMetrix", "LexisNexis", IDENTIFICATION, "Lcom/threatmetrix/TrustDefender/"),
        t("iovation", "iovation", "TransUnion", IDENTIFICATION, "Lcom/iovation/mobile/android/"),
        // Support / feedback
        t("intercom", "Intercom", "Intercom", setOf(TrackerCategory.SUPPORT, TrackerCategory.ANALYTICS), "Lio/intercom/android/", "Lcom/intercom/"),
        t("zendesk", "Zendesk", "Zendesk", SUPPORT, "Lzendesk/", "Lcom/zendesk/"),
        t("helpshift", "Helpshift", "Helpshift", SUPPORT, "Lcom/helpshift/"),
        t("freshchat", "Freshchat", "Freshworks", SUPPORT, "Lcom/freshchat/consumer/sdk/"),
        t("apptentive", "Apptentive", "Alchemer", setOf(TrackerCategory.SUPPORT, TrackerCategory.ANALYTICS), "Lcom/apptentive/android/"),
        t("qualtrics", "Qualtrics", "Qualtrics", setOf(TrackerCategory.SUPPORT, TrackerCategory.ANALYTICS), "Lcom/qualtrics/digital/"),
        t("medallia", "Medallia Digital", "Medallia", setOf(TrackerCategory.SUPPORT, TrackerCategory.ANALYTICS), "Lcom/medallia/digital/"),
        t("wootric", "Wootric", "InMoment", SUPPORT, "Lcom/wootric/androidsdk/"),
    )

    private val byId: Map<String, Tracker> = all.associateBy { it.id }

    fun byId(id: String): Tracker? = byId[id]

    /** Display name for an id, falling back to the id itself for entries removed from a later catalog. */
    fun nameOf(id: String): String = byId[id]?.name ?: id
}
